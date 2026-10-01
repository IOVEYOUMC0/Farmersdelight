package com.huidu.farmersdelight.visual;

import org.junit.jupiter.api.Test;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class BoundedPacketMailboxTest {
    @Test void overloadNeverRunsWorkOnTheProducer() {
        ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        List<Integer> sent = new ArrayList<>();
        var mailbox = new BoundedPacketMailbox<Integer>(2, 2, tasks::add, sent::addAll);
        assertTrue(mailbox.offer(1));
        assertTrue(mailbox.offer(2));
        assertFalse(mailbox.offer(3));
        assertEquals(1, tasks.size());
        assertTrue(sent.isEmpty());
        tasks.removeFirst().run();
        assertEquals(List.of(1, 2), sent);
    }

    @Test void drainingYieldsBetweenBoundedBatches() {
        ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        List<List<Integer>> sent = new ArrayList<>();
        var mailbox = new BoundedPacketMailbox<Integer>(5, 2, tasks::add, sent::add);
        for (int i = 0; i < 5; i++) assertTrue(mailbox.offer(i));
        tasks.removeFirst().run();
        assertEquals(List.of(List.of(0, 1)), sent);
        assertEquals(1, tasks.size());
        while (!tasks.isEmpty()) tasks.removeFirst().run();
        assertEquals(List.of(List.of(0, 1), List.of(2, 3), List.of(4)), sent);
        assertTrue(mailbox.offer(5));
        assertEquals(1, tasks.size());
    }

    @Test void anOfferDuringDeliveryIsNotLostOrScheduledTwice() {
        ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        List<Integer> sent = new ArrayList<>();
        AtomicReference<BoundedPacketMailbox<Integer>> ref = new AtomicReference<>();
        var mailbox = new BoundedPacketMailbox<Integer>(4, 2, tasks::add, batch -> {
            sent.addAll(batch);
            if (batch.contains(1)) assertTrue(ref.get().offer(2));
        });
        ref.set(mailbox);
        mailbox.offer(1);
        tasks.removeFirst().run();
        assertEquals(1, tasks.size());
        tasks.removeFirst().run();
        assertEquals(List.of(1, 2), sent);
        assertTrue(tasks.isEmpty());
    }

    @Test void closingDiscardsScheduledAndFutureWork() {
        ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        var mailbox = new BoundedPacketMailbox<Integer>(2, 2, tasks::add, batch -> fail("Closed mailbox sent packets"));
        mailbox.offer(1);
        mailbox.close();
        assertFalse(mailbox.offer(2));
        tasks.removeFirst().run();
        assertTrue(tasks.isEmpty());
    }

    @Test void aStoppedConnectionDoesNotRetainQueuedWork() {
        var mailbox = new BoundedPacketMailbox<Integer>(2, 2,
                task -> { throw new RejectedExecutionException(); }, batch -> fail("Rejected work ran"));
        assertFalse(mailbox.offer(1));
        assertFalse(mailbox.offer(2));
    }

    @Test void aFailedBatchDoesNotWedgeLaterDelivery() {
        ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        List<Integer> sent = new ArrayList<>();
        var mailbox = new BoundedPacketMailbox<Integer>(3, 1, tasks::add, batch -> {
            if (batch.contains(1)) throw new IllegalStateException("transport failure");
            sent.addAll(batch);
        });
        mailbox.offer(1);
        mailbox.offer(2);
        assertThrows(IllegalStateException.class, () -> tasks.removeFirst().run());
        assertEquals(1, tasks.size());
        tasks.removeFirst().run();
        assertEquals(List.of(2), sent);
    }
}
