package com.eatthepath.pool;

import io.netty.util.concurrent.Future;

/**
 * Constructs new objects for use in an asynchronous object pool.
 *
 * @param <T> the type of object created by this factory
 */
interface PooledObjectLifecycleManager<T> {

    /**
     * Asynchronously creates a new object for use in an asynchronous object pool. Newly-created objects are assumed to
     * be valid; if a newly-created object would not pass this lifecycle manager's {@link #validate(Object)} check, this
     * method should return a failed future instead.
     *
     * @return a {@code Future} that will complete when a new pooled object has been created
     */
    Future<T> create();

    /**
     * Tests whether the given object is usable and should be available to borrowers. Implementations of this method
     * must not block.
     *
     * @param object the object to test
     *
     * @return {@code true} if the object is usable and should be available to borrowers or {@code false} otherwise
     */
    boolean validate(T object);

    /**
     * Asynchronously destroys an object evicted from an asynchronous object pool.
     *
     * @param object the object to destroy
     *
     * @return a {@code Future} that will complete when the given object has been destroyed
     */
    Future<Void> destroy(T object);
}
