package com.eatthepath.pool;

import io.netty.util.concurrent.*;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;

public class DefaultAsyncObjectPool<T> implements AsyncObjectPool<T> {

    private final PooledObjectLifecycleManager<T> objectLifecycleManager;
    private final OrderedEventExecutor executor;
    private final int capacity;

    private boolean isClosed = false;

    private final Set<Future<T>> pendingCreateFutures = new HashSet<>();
    private final Queue<Promise<T>> pendingAcquisitionPromises = new ArrayDeque<>();

    private final Set<T> allObjects = new HashSet<>();
    private final Queue<T> idleObjects = new ArrayDeque<>();

    public DefaultAsyncObjectPool(final PooledObjectLifecycleManager<T> objectLifecycleManager, final OrderedEventExecutor executor, final int capacity) {
        this.objectLifecycleManager = objectLifecycleManager;
        this.executor = executor;
        this.capacity = capacity;
    }

    public Future<T> acquire() {
        final Promise<T> acquirePromise = new DefaultPromise<>(executor);

        doInEventLoop(() -> acquireWithinEventExecutor(acquirePromise));

        return acquirePromise;
    }

    private void acquireWithinEventExecutor(final Promise<T> acquirePromise) {
        assert executor.inEventLoop();

        if (isClosed) {
            acquirePromise.tryFailure(new IllegalStateException("Cannot borrow an object because the pool is closed"));
            return;
        }

        // We always want to create new objects if we have spare capacity. Once the pool is full, we'll start
        // looking for idle, pre-existing objects.
        if (allObjects.size() + pendingCreateFutures.size() < capacity) {
            final Future<T> createFuture = objectLifecycleManager.create();
            pendingCreateFutures.add(createFuture);

            createFuture.addListener((GenericFutureListener<Future<T>>) future -> {
                pendingCreateFutures.remove(createFuture);

                if (future.isSuccess()) {
                    final T object = future.getNow();

                    allObjects.add(object);
                    acquirePromise.trySuccess(object);
                } else {
                    acquirePromise.tryFailure(future.cause());

                    // If we failed to create an object, this is the end of the line for this acquisition
                    // attempt, and callers won't be able to release the object (since they didn't get one
                    // in the first place). Move on to the next acquisition attempt if one is present.
                    handleNextAcquisition();
                }
            });
        } else {
            final T objectFromIdlePool = idleObjects.poll();

            if (objectFromIdlePool != null) {
                if (objectLifecycleManager.validate(objectFromIdlePool)) {
                    acquirePromise.trySuccess(objectFromIdlePool);
                } else {
                    // The object from the idle pool isn't usable; discard it and create a new one instead
                    discardObject(objectFromIdlePool);
                    acquireWithinEventExecutor(acquirePromise);
                }
            } else {
                // We don't have any objects ready to go, and don't have any more capacity to create new
                // objects. Add this acquisition to the queue waiting for objects to become available.
                pendingAcquisitionPromises.add(acquirePromise);
            }
        }
    }

    public void release(final T object) {
        doInEventLoop(() -> releaseWithinEventExecutor(object));
    }

    private void releaseWithinEventExecutor(final T object) {
        assert executor.inEventLoop();

        idleObjects.add(object);
        handleNextAcquisition();
    }

    private void discardObject(final T object) {
        assert executor.inEventLoop();

        idleObjects.remove(object);
        allObjects.remove(object);

        objectLifecycleManager.destroy(object);
    }

    private void handleNextAcquisition() {
        assert executor.inEventLoop();

        if (!pendingAcquisitionPromises.isEmpty()) {
            acquireWithinEventExecutor(pendingAcquisitionPromises.poll());
        }
    }

    public Future<Void> close() {
        final Promise<Void> closePromise = new DefaultPromise<>(executor);

        doInEventLoop(() -> closeWithinEventExecutor(closePromise));

        return closePromise;
    }

    private void closeWithinEventExecutor(final Promise<Void> closePromise) {
        assert executor.inEventLoop();

        isClosed = true;

        for (final Promise<T> acquisitionPromise : pendingAcquisitionPromises) {
            acquisitionPromise.tryFailure(new IllegalStateException("Pool closed before an object could be acquired"));
        }

        final PromiseCombiner createFutureCombiner = new PromiseCombiner(executor);

        for (final Future<T> createFuture : pendingCreateFutures) {
            createFutureCombiner.add(createFuture);
        }

        final Promise<Void> allCreateFuturesFinishedPromise = new DefaultPromise<>(executor);
        createFutureCombiner.finish(allCreateFuturesFinishedPromise);

        allCreateFuturesFinishedPromise.addListener(future -> {
            final PromiseCombiner destroyObjectsCombiner = new PromiseCombiner(executor);

            for (final T object : allObjects) {
                destroyObjectsCombiner.add(objectLifecycleManager.destroy(object));
            }

            final Promise<Void> allObjectsDestroyedPromise = new DefaultPromise<>(executor);
            destroyObjectsCombiner.finish(allObjectsDestroyedPromise);

            allObjectsDestroyedPromise.addListener(new PromiseNotifier<>(closePromise));
        });
    }

    private void doInEventLoop(final Runnable action) {
        if (executor.inEventLoop()) {
            action.run();
        } else {
            executor.submit(action);
        }
    }
}
