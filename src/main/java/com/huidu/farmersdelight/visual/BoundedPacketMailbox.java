package com.huidu.farmersdelight.visual;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/** One scheduled drain per connection, with a finite queue for disposable visual updates. */
final class BoundedPacketMailbox<T> {
    private final ArrayDeque<T> pending = new ArrayDeque<>();
    private final int capacity;
    private final int batchSize;
    private final Executor executor;
    private final Consumer<List<T>> consumer;
    private boolean scheduled;
    private boolean closed;

    BoundedPacketMailbox(int capacity, int batchSize, Executor executor, Consumer<List<T>> consumer) {
        if (capacity < 1 || batchSize < 1) throw new IllegalArgumentException("Invalid mailbox limits");
        this.capacity = capacity;
        this.batchSize = batchSize;
        this.executor = executor;
        this.consumer = consumer;
    }

    boolean offer(T value) {
        boolean start;
        synchronized (this) {
            if (closed || pending.size() >= capacity) return false;
            pending.addLast(value);
            start = !scheduled;
            scheduled = true;
        }
        return !start || schedule();
    }

    synchronized void close() {
        closed = true;
        pending.clear();
    }

    private boolean schedule() {
        try {
            executor.execute(this::drain);
            return true;
        } catch (RejectedExecutionException stopped) {
            close();
            return false;
        }
    }

    private void drain() {
        List<T> batch = new ArrayList<>(batchSize);
        synchronized (this) {
            if (!closed) {
                while (batch.size() < batchSize && !pending.isEmpty()) batch.add(pending.removeFirst());
            }
        }
        try {
            if (!batch.isEmpty()) consumer.accept(List.copyOf(batch));
        } finally {
            boolean again;
            synchronized (this) {
                again = !closed && !pending.isEmpty();
                if (!again) scheduled = false;
            }
            if (again) schedule();
        }
    }
}
