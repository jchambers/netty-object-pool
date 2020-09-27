package com.eatthepath.pool;

import io.netty.util.concurrent.*;

import java.util.HashMap;
import java.util.Map;

public class DefaultKeyedAsyncObjectPool<K, T> implements KeyedAsyncObjectPool<K, T> {

    private final OrderedEventExecutor executor;
    private final KeyedPooledObjectLifecycleManager<K, T> objectLifecycleManager;
    private final int capacityPerKey;

    private final Map<K, AsyncObjectPool<T>> poolsByKey = new HashMap<>();

    private boolean isClosed = false;

    private class DelegatingPooledObjectLifecycleManager implements PooledObjectLifecycleManager<T> {
        private final K key;

        public DelegatingPooledObjectLifecycleManager(final K key) {
            this.key = key;
        }

        @Override
        public Future<T> create() {
            return objectLifecycleManager.create(key);
        }

        @Override
        public boolean validate(final T object) {
            return objectLifecycleManager.validate(key, object);
        }

        @Override
        public Future<Void> destroy(final T object) {
            return objectLifecycleManager.destroy(key, object);
        }
    }

    public DefaultKeyedAsyncObjectPool(final KeyedPooledObjectLifecycleManager<K, T> objectLifecycleManager,
                                       final OrderedEventExecutor executor,
                                       final int capacityPerKey) {

        this.objectLifecycleManager = objectLifecycleManager;
        this.executor = executor;
        this.capacityPerKey = capacityPerKey;
    }

    @Override
    public Future<T> acquire(final K key) {
        final Promise<T> acquirePromise = new DefaultPromise<>(executor);

        doInEventLoop(() -> acquireWithinEventExecutor(key, acquirePromise));

        return acquirePromise;
    }

    private void acquireWithinEventExecutor(final K key, final Promise<T> acquirePromise) {
        assert executor.inEventLoop();

        if (isClosed) {
            acquirePromise.tryFailure(new IllegalStateException("Cannot borrow an object because the pool is closed"));
        } else {
            final AsyncObjectPool<T> poolForKey = poolsByKey.computeIfAbsent(key, (k) ->
                    new DefaultAsyncObjectPool<>(new DelegatingPooledObjectLifecycleManager(k), executor, capacityPerKey));

            poolForKey.acquire().addListener(new PromiseNotifier<>(acquirePromise));
        }
    }

    @Override
    public void release(final K key, final T object) {
        doInEventLoop(() -> releaseWithinEventLoop(key, object));
    }

    private void releaseWithinEventLoop(final K key, final T object) {
        assert executor.inEventLoop();

        final AsyncObjectPool<T> poolForKey = poolsByKey.get(key);

        if (poolForKey == null) {
            throw new IllegalArgumentException("Cannot release object for non-existent key: " + key);
        }

        poolForKey.release(object);
    }

    @Override
    public Future<Void> close() {
        final Promise<Void> closePromise = new DefaultPromise<>(executor);

        doInEventLoop(() -> closeWithinEventExecutor(closePromise));

        return closePromise;
    }

    private void closeWithinEventExecutor(final Promise<Void> closePromise) {
        assert executor.inEventLoop();

        isClosed = true;

        final PromiseCombiner poolCloseCombiner = new PromiseCombiner(executor);

        for (final AsyncObjectPool<T> pool : poolsByKey.values()) {
            poolCloseCombiner.add(pool.close());
        }

        poolCloseCombiner.finish(closePromise);
    }

    private void doInEventLoop(final Runnable action) {
        if (executor.inEventLoop()) {
            action.run();
        } else {
            executor.submit(action);
        }
    }
}
