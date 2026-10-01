package com.huidu.farmersdelight.util.scheduler;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class BoundedExecutorTest {
    @Test void saturationRejectsWithoutExecutingOnCaller() throws Exception {
        var pool = BoundedExecutor.create(1, 1, Thread::new);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean rejectedRan = new AtomicBoolean();
        try {
            pool.execute(() -> {
                entered.countDown();
                try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            pool.execute(() -> {});
            assertThrows(RejectedExecutionException.class, () -> pool.execute(() -> rejectedRan.set(true)));
            assertFalse(rejectedRan.get());
            assertEquals(1, pool.getQueue().size());
        } finally {
            release.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
