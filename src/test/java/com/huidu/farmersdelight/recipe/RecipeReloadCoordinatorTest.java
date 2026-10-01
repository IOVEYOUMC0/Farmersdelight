package com.huidu.farmersdelight.recipe;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class RecipeReloadCoordinatorTest {
    private static final class Backend implements RecipeReloadCoordinator.Backend {
        final ArrayDeque<Runnable> owners = new ArrayDeque<>();
        final ArrayDeque<Runnable> workers = new ArrayDeque<>();
        final List<Integer> published = new ArrayList<>();
        final List<Boolean> mergePolicies = new ArrayList<>();
        boolean onOwner;
        boolean onWorker;
        boolean enabled = true;
        boolean accept = true;
        boolean failPreparation;
        boolean failPublication;
        boolean merge;
        long generation;
        int disk;
        int reads;
        int badValidations;
        int scopedVersion;

        public void onOwner(Runnable task) { owners.add(task); }
        public boolean onWorker(Runnable task) { if (!accept) return false; workers.add(task); return true; }
        public boolean enabled() { assertTrue(onOwner); return enabled; }
        public long generation() { assertTrue(onOwner); return generation; }
        public boolean mergeMissing() { assertTrue(onOwner); return merge; }
        public RecipeReloadCoordinator.Batch prepare(boolean merge) throws IOException {
            assertTrue(onWorker);
            reads++;
            mergePolicies.add(merge);
            if (failPreparation) throw new IOException("Bad YAML");
            int captured = disk;
            return new RecipeReloadCoordinator.Batch() {
                public void validateCurrent() throws IOException {
                    assertTrue(onOwner);
                    if (badValidations > 0) { badValidations--; throw new IOException("Changed during read"); }
                    if (captured != disk) throw new IOException("Changed during read");
                }
                public void publishWithin(Runnable publication) {
                    assertTrue(onOwner);
                    scopedVersion = captured;
                    publication.run();
                }
            };
        }
        public void publish() {
            assertTrue(onOwner);
            if (failPublication) throw new IllegalStateException("Item decoding failed");
            published.add(scopedVersion);
        }

        void owner() { onOwner = true; try { owners.removeFirst().run(); } finally { onOwner = false; } }
        void worker() { onWorker = true; try { workers.removeFirst().run(); } finally { onWorker = false; } }
        void drain() {
            for (int i = 0; i < 100 && (!owners.isEmpty() || !workers.isEmpty()); i++) {
                if (!owners.isEmpty()) owner(); else worker();
            }
            assertTrue(owners.isEmpty());
            assertTrue(workers.isEmpty());
        }
    }

    @Test void simultaneousCommitsAreCoalescedAndAcknowledgedAfterOwnerPublication() {
        Backend backend = new Backend();
        var coordinator = new RecipeReloadCoordinator(backend);
        List<CompletableFuture<Void>> requests = new ArrayList<>();
        for (int i = 0; i < 100; i++) { backend.disk++; requests.add(coordinator.request()); }
        assertEquals(1, backend.owners.size());
        backend.owner();
        backend.worker();
        for (var request : requests) assertFalse(request.isDone());
        backend.owner();
        assertEquals(List.of(100), backend.published);
        assertEquals(1, backend.reads);
        requests.forEach(CompletableFuture::join);
    }

    @Test void anEditCommittedAfterPreparationCannotBeOverwrittenByTheOldBatch() {
        Backend backend = new Backend();
        var coordinator = new RecipeReloadCoordinator(backend);
        backend.disk = 1;
        var first = coordinator.request();
        backend.owner();
        backend.worker();
        backend.disk = 2;
        var second = coordinator.request();
        backend.owner();
        assertTrue(backend.published.isEmpty());
        assertFalse(first.isDone());
        backend.drain();
        assertEquals(List.of(2), backend.published);
        first.join();
        second.join();
    }

    @Test void aConfigurationReloadRecapturesTheMergePolicyAndPreparedFiles() {
        Backend backend = new Backend();
        var coordinator = new RecipeReloadCoordinator(backend);
        var result = coordinator.request();
        backend.owner();
        backend.worker();
        backend.generation++;
        backend.merge = true;
        backend.drain();
        assertEquals(List.of(false, true), backend.mergePolicies);
        assertEquals(1, backend.published.size());
        result.join();
    }

    @Test void externalFileChangesAreRetriedButPermanentChurnIsBounded() {
        Backend backend = new Backend();
        var coordinator = new RecipeReloadCoordinator(backend);
        backend.badValidations = 2;
        var success = coordinator.request();
        backend.drain();
        success.join();
        assertEquals(3, backend.reads);
        backend.badValidations = 100;
        var failure = coordinator.request();
        backend.drain();
        assertTrue(failure.isCompletedExceptionally());
        assertEquals(6, backend.reads);
        assertEquals(1, backend.published.size());
    }

    @Test void queueRejectionFailsTheAcknowledgementAndTheNextRequestCanRecover() {
        Backend backend = new Backend();
        var coordinator = new RecipeReloadCoordinator(backend);
        backend.accept = false;
        var rejected = coordinator.request();
        backend.drain();
        assertTrue(rejected.isCompletedExceptionally());
        assertEquals(0, backend.reads);
        backend.accept = true;
        var retry = coordinator.request();
        backend.drain();
        retry.join();
        assertEquals(1, backend.published.size());
    }

    @Test void malformedDocumentsAndPublicationFailuresLeaveNoStuckRequests() {
        Backend backend = new Backend();
        var coordinator = new RecipeReloadCoordinator(backend);
        backend.failPreparation = true;
        var readFailure = coordinator.request();
        backend.drain();
        assertTrue(readFailure.isCompletedExceptionally());
        backend.failPreparation = false;
        backend.failPublication = true;
        var itemFailure = coordinator.request();
        backend.drain();
        assertTrue(itemFailure.isCompletedExceptionally());
        backend.failPublication = false;
        var success = coordinator.request();
        backend.drain();
        success.join();
        assertEquals(1, backend.published.size());
    }

    @Test void closingAfterAReadFailsPendingRequestsAndSuppressesLatePublication() {
        Backend backend = new Backend();
        var coordinator = new RecipeReloadCoordinator(backend);
        var pending = coordinator.request();
        backend.owner();
        backend.worker();
        coordinator.close();
        backend.drain();
        assertTrue(pending.isCompletedExceptionally());
        assertTrue(coordinator.request().isCompletedExceptionally());
        assertTrue(backend.published.isEmpty());
    }

    @Test void disabledPluginDoesNotReadOrPublish() {
        Backend backend = new Backend();
        backend.enabled = false;
        var result = new RecipeReloadCoordinator(backend).request();
        backend.drain();
        assertTrue(result.isCompletedExceptionally());
        assertEquals(0, backend.reads);
    }

    @Test void acknowledgementOverflowDoesNotLoseTheMostRecentCommittedFiles() {
        Backend backend = new Backend();
        var coordinator = new RecipeReloadCoordinator(backend);
        List<CompletableFuture<Void>> results = new ArrayList<>();
        for (int i = 0; i < 300; i++) { backend.disk++; results.add(coordinator.request()); }
        assertEquals(44, results.stream().filter(CompletableFuture::isCompletedExceptionally).count());
        backend.drain();
        assertEquals(List.of(300), backend.published);
        for (int i = 0; i < 256; i++) results.get(i).join();
    }
}
