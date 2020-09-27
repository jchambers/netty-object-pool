package com.eatthepath.pool;

import io.netty.util.concurrent.DefaultEventExecutor;
import io.netty.util.concurrent.OrderedEventExecutor;
import io.netty.util.concurrent.SucceededFuture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultKeyedAsyncObjectPoolTest {

    private KeyedPooledObjectLifecycleManager<String, Object> objectLifecycleManager;

    private DefaultKeyedAsyncObjectPool<String, Object> pool;

    private static OrderedEventExecutor EVENT_EXECUTOR;

    @BeforeAll
    public static void setUpBeforeClass() {
        EVENT_EXECUTOR = new DefaultEventExecutor();
    }

    @SuppressWarnings("unchecked")
    @BeforeEach
    public void setUp() {
        objectLifecycleManager = mock(KeyedPooledObjectLifecycleManager.class);
        when(objectLifecycleManager.destroy(anyString(), any())).thenReturn(new SucceededFuture<>(EVENT_EXECUTOR, null));

        pool = new DefaultKeyedAsyncObjectPool<>(objectLifecycleManager, EVENT_EXECUTOR, 1);
    }

    @AfterEach
    public void tearDown() throws InterruptedException {
        pool.close().await();
    }

    @AfterAll
    public static void tearDownAfterClass() throws Exception {
        EVENT_EXECUTOR.shutdownGracefully().await();
    }

    
}