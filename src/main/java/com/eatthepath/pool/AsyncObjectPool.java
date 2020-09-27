package com.eatthepath.pool;

import io.netty.util.concurrent.Future;

public interface AsyncObjectPool<T> {

    Future<T> acquire();

    void release(T object);

    Future<Void> close();
}
