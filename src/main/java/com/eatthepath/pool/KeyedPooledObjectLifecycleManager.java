package com.eatthepath.pool;

import io.netty.util.concurrent.Future;

public interface KeyedPooledObjectLifecycleManager<K, T> {

    Future<T> create(K key);

    boolean validate(K key, T object);

    Future<Void> destroy(K key, T object);
}
