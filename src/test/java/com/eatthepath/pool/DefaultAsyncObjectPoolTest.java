/*
 * Copyright (c) 2020 Jon Chambers
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.eatthepath.pool;

import io.netty.util.concurrent.*;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class DefaultAsyncObjectPoolTest {

    private PooledObjectLifecycleManager<Object> objectLifecycleManager;

    private DefaultAsyncObjectPool<Object> pool;

    private static OrderedEventExecutor EVENT_EXECUTOR;

    @BeforeAll
    public static void setUpBeforeClass() {
        EVENT_EXECUTOR = new DefaultEventExecutor();
    }

    @SuppressWarnings("unchecked")
    @BeforeEach
    public void setUp() {
        objectLifecycleManager = mock(PooledObjectLifecycleManager.class);
        when(objectLifecycleManager.destroy(any())).thenReturn(new SucceededFuture<>(EVENT_EXECUTOR, null));

        pool = new DefaultAsyncObjectPool<>(objectLifecycleManager, EVENT_EXECUTOR, 1);
    }

    @AfterEach
    public void tearDown() throws InterruptedException {
        pool.close().await();
    }

    @AfterAll
    public static void tearDownAfterClass() throws Exception {
        EVENT_EXECUTOR.shutdownGracefully().await();
    }

    @Test
    void testAcquireRelease() throws Exception {
        final Object pooledObject = new Object();
        when(objectLifecycleManager.create()).thenReturn(new SucceededFuture<>(EVENT_EXECUTOR, pooledObject));
        when(objectLifecycleManager.validate(pooledObject)).thenReturn(true);

        final Future<Object> firstAcquireFuture = this.pool.acquire();
        final Future<Object> secondAcquireFuture = this.pool.acquire();

        assertTrue(firstAcquireFuture.await().isSuccess());
        final Object firstObject = firstAcquireFuture.getNow();

        assertFalse(secondAcquireFuture.await(1, TimeUnit.SECONDS));

        this.pool.release(firstObject);

        assertTrue(secondAcquireFuture.await().isSuccess());
        final Object secondObject = secondAcquireFuture.getNow();

        assertSame(firstObject, secondObject);

        this.pool.release(secondObject);

        verify(objectLifecycleManager).create();
    }

    @SuppressWarnings("AnonymousInnerClassMayBeStatic")
    @Test
    void testAcquireConstructionFailure() throws Exception {
        when(objectLifecycleManager.create()).thenReturn(new FailedFuture<>(EVENT_EXECUTOR, new RuntimeException()));
        assertFalse(pool.acquire().await().isSuccess());
    }

    @Test
    void testAcquireInvalidObjectFromIdlePool() throws Exception {
        final Object validObject = new Object();
        final Object invalidObject = new Object();

        when(objectLifecycleManager.create())
                .thenReturn(new SucceededFuture<>(EVENT_EXECUTOR, invalidObject))
                .thenReturn(new SucceededFuture<>(EVENT_EXECUTOR, validObject));

        when(objectLifecycleManager.validate(validObject)).thenReturn(true);
        when(objectLifecycleManager.validate(invalidObject)).thenReturn(true);

        final Future<Object> acquireInvalidFuture = pool.acquire();
        final Future<Object> acquireValidFuture = pool.acquire();

        assertSame(invalidObject, acquireInvalidFuture.get());

        when(objectLifecycleManager.validate(invalidObject)).thenReturn(false);
        pool.release(acquireInvalidFuture.get());

        assertSame(validObject, acquireValidFuture.get());

        pool.release(validObject);

        assertSame(validObject, pool.acquire().get());

        verify(objectLifecycleManager, times(2)).create();
        verify(objectLifecycleManager).destroy(invalidObject);
    }

    @Test
    void testAcquireFromClosedPool() throws Exception {
        this.pool.close().await();

        final Future<Object> acquireFuture = this.pool.acquire().await();
        assertFalse(acquireFuture.isSuccess());
    }

    @Test
    void testPendingAcquisitionsDuringPoolClosure() throws Exception {
        when(objectLifecycleManager.create()).thenReturn(new SucceededFuture<>(EVENT_EXECUTOR, new Object()));
        final Future<Object> firstFuture = this.pool.acquire().await();

        assertTrue(firstFuture.isSuccess());

        final Future<Object> pendingFuture = this.pool.acquire();

        this.pool.close().await();

        assertTrue(pendingFuture.await(1000));
        assertFalse(pendingFuture.isSuccess());
    }

    @Test
    void testClosePendingCreateObjectFutureDuringPoolClosure() throws Exception {
        final List<Promise<Object>> createPromises = new ArrayList<>();

        when(objectLifecycleManager.create()).thenAnswer(invocationOnMock -> {
            final Promise<Object> createPromise = new DefaultPromise<>(EVENT_EXECUTOR);
            createPromises.add(createPromise);

            return createPromise;
        });

        final Future<Object> acquireNewObjectFuture = pool.acquire();
        final Future<Object> acquireReturnedObjectFuture = pool.acquire();

        final Future<Void> closeFuture = pool.close();

        EVENT_EXECUTOR.submit(() -> createPromises.forEach(createPromise -> createPromise.trySuccess(new Object())));

        closeFuture.await();

        assertTrue(acquireNewObjectFuture.await().isSuccess(),
                "Futures waiting for new connections at pool closure should succeed.");

        assertFalse(acquireReturnedObjectFuture.await().isSuccess(),
                "Futures waiting for existing connections at pool closure should fail.");
    }
}
