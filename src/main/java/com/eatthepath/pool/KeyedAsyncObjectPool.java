package com.eatthepath.pool;

import io.netty.util.concurrent.Future;

public interface KeyedAsyncObjectPool<K, T> {

    Future<T> acquire(K key);

    void release(K key, T object);

    Future<Void> close();
}
