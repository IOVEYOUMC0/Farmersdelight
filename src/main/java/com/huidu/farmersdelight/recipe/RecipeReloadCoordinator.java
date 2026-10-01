package com.huidu.farmersdelight.recipe;

import com.huidu.farmersdelight.FarmersDelightPlugin;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;

/** Coalesces committed edits and acknowledges them after the latest document batch is published. */
public final class RecipeReloadCoordinator {
    interface Batch {
        void validateCurrent() throws IOException;
        void publishWithin(Runnable publication);
    }

    interface Backend {
        void onOwner(Runnable task);
        boolean onWorker(Runnable task);
        boolean enabled();
        long generation();
        boolean mergeMissing();
        Batch prepare(boolean merge) throws Exception;
        void publish();
    }

    private record Request(long revision, CompletableFuture<Void> result) { }
    private static final int MAX_PENDING_REQUESTS = 256;
    private final Backend backend;
    private final List<Request> requests = new ArrayList<>();
    private long revision;
    private boolean running;
    private boolean stopped;

    public RecipeReloadCoordinator(FarmersDelightPlugin plugin) {
        this(new Backend() {
            public void onOwner(Runnable task) { plugin.scheduler().run(task); }
            public boolean onWorker(Runnable task) { return plugin.scheduler().tryRunAsync(task); }
            public boolean enabled() { return plugin.isEnabled(); }
            public long generation() { return plugin.configurationGeneration(); }
            public boolean mergeMissing() { return plugin.getConfigBoolean(false, "recipes.merge-missing-bundled"); }
            public Batch prepare(boolean merge) throws Exception { return PreparedRecipeFiles.read(plugin, merge); }
            public void publish() { plugin.publishEditedRecipes(); }
        });
    }

    RecipeReloadCoordinator(Backend backend) { this.backend = backend; }

    public CompletableFuture<Void> request() {
        CompletableFuture<Void> result = new CompletableFuture<>();
        boolean start;
        synchronized (this) {
            if (stopped) return CompletableFuture.failedFuture(new IllegalStateException("Recipe reload stopped"));
            ++revision;
            // Even an overflowed acknowledgement invalidates a batch read before this disk commit.
            if (requests.size() >= MAX_PENDING_REQUESTS) {
                return CompletableFuture.failedFuture(new RejectedExecutionException("Too many pending recipe edits"));
            }
            requests.add(new Request(revision, result));
            start = !running;
            running = true;
        }
        if (start) prepare(0);
        return result;
    }

    private void prepare(int retry) {
        try {
            backend.onOwner(() -> {
                long captured;
                synchronized (this) {
                    if (stopped) return;
                    captured = revision;
                }
                if (!backend.enabled()) { finish(captured, new IllegalStateException("Plugin stopped")); return; }
                long generation = backend.generation();
                boolean merge = backend.mergeMissing();
                if (!backend.onWorker(() -> {
                    synchronized (this) { if (stopped) return; }
                    try {
                        Batch prepared = backend.prepare(merge);
                        backend.onOwner(() -> publish(captured, generation, prepared, retry));
                    } catch (Exception | LinkageError error) {
                        finish(captured, error);
                    }
                })) finish(captured, new RejectedExecutionException("Recipe preparation queue is full or stopped"));
            });
        } catch (RuntimeException stoppedScheduler) {
            long captured;
            synchronized (this) { captured = revision; }
            finish(captured, stoppedScheduler);
        }
    }

    private void publish(long captured, long generation, Batch prepared, int retry) {
        boolean outdated;
        synchronized (this) {
            if (stopped) return;
            outdated = captured != revision;
        }
        if (outdated || generation != backend.generation()) { prepare(0); return; }
        if (!backend.enabled()) { finish(captured, new IllegalStateException("Plugin stopped")); return; }
        try {
            prepared.validateCurrent();
        } catch (IOException changed) {
            if (retry < 2) { prepare(retry + 1); return; }
            finish(captured, changed);
            return;
        } catch (RuntimeException | LinkageError error) {
            finish(captured, error);
            return;
        }
        try {
            prepared.publishWithin(backend::publish);
            finish(captured, null);
        } catch (RuntimeException | LinkageError error) {
            finish(captured, error);
        }
    }

    private void finish(long captured, Throwable error) {
        List<Request> finished = new ArrayList<>();
        boolean again;
        synchronized (this) {
            if (stopped) return;
            requests.removeIf(request -> {
                if (request.revision() > captured) return false;
                finished.add(request);
                return true;
            });
            again = !requests.isEmpty();
            running = again;
        }
        for (Request request : finished) {
            if (error == null) request.result().complete(null);
            else request.result().completeExceptionally(error);
        }
        if (again) prepare(0);
    }

    public void close() {
        List<Request> remaining;
        synchronized (this) {
            stopped = true;
            remaining = List.copyOf(requests);
            requests.clear();
            running = false;
        }
        for (Request request : remaining) request.result().completeExceptionally(new IllegalStateException("Recipe reload stopped"));
    }
}
