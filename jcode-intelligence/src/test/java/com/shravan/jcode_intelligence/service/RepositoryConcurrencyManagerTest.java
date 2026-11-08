package com.shravan.jcode_intelligence.service;

import com.shravan.jcode_intelligence.exception.IndexingConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests verifying repository-level concurrency isolation, same-repository conflict rejection,
 * and distinct repository parallelism in {@link RepositoryConcurrencyManager}.
 */
class RepositoryConcurrencyManagerTest {

    private RepositoryConcurrencyManager concurrencyManager;

    @BeforeEach
    void setUp() {
        concurrencyManager = new RepositoryConcurrencyManager();
    }

    @Test
    @DisplayName("Acquiring and releasing an exclusive lock updates repository locked status")
    void testAcquireAndRelease() {
        String repoId = "ASTra-Core";
        assertFalse(concurrencyManager.isLocked(repoId));

        boolean acquired = concurrencyManager.tryAcquire(repoId);
        assertTrue(acquired, "Must acquire lock when repository is idle");
        assertTrue(concurrencyManager.isLocked(repoId), "Repository must report locked");

        concurrencyManager.release(repoId);
        assertFalse(concurrencyManager.isLocked(repoId), "Repository must be unlocked after release");
    }

    @Test
    @DisplayName("Simultaneous indexing attempts for the same repository throw IndexingConflictException")
    void testConcurrentAcquisitionForSameRepo_throwsConflict() throws Exception {
        String repoId = "ASTra-Repo";

        assertTrue(concurrencyManager.tryAcquire(repoId));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> future = executor.submit(() -> {
                assertThrows(IndexingConflictException.class, () ->
                        concurrencyManager.acquireOrThrow(repoId),
                        "Concurrent acquisition for the same repository must throw IndexingConflictException");
            });
            future.get(2, TimeUnit.SECONDS);
        } finally {
            concurrencyManager.release(repoId);
            executor.shutdown();
        }
    }

    @Test
    @DisplayName("Distinct repositories can acquire indexing locks concurrently without blocking")
    void testDifferentRepositoriesCanRunConcurrently() throws Exception {
        String repo1 = "repo-alpha";
        String repo2 = "repo-beta";

        assertTrue(concurrencyManager.tryAcquire(repo1));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> future = executor.submit(() -> concurrencyManager.tryAcquire(repo2));
            boolean acquiredRepo2 = future.get(2, TimeUnit.SECONDS);

            assertTrue(acquiredRepo2, "Distinct repository must be acquired without interference");
            assertTrue(concurrencyManager.isLocked(repo1));
            assertTrue(concurrencyManager.isLocked(repo2));
        } finally {
            concurrencyManager.release(repo1);
            executor.shutdown();
        }
    }

    @Test
    @DisplayName("tryAcquire with timeout returns false when lock is held by another thread")
    void testTryAcquireWithTimeout_timesOut() throws Exception {
        String repoId = "locked-repo";
        assertTrue(concurrencyManager.tryAcquire(repoId));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> future = executor.submit(() ->
                    concurrencyManager.tryAcquire(repoId, 50, TimeUnit.MILLISECONDS));
            assertFalse(future.get(1, TimeUnit.SECONDS), "Must return false when lock cannot be acquired within timeout");
        } finally {
            concurrencyManager.release(repoId);
            executor.shutdown();
        }
    }

    @Test
    @DisplayName("Release only unlocks when called by the thread holding the lock")
    void testReleaseFromNonOwnerThreadIsIgnored() throws Exception {
        String repoId = "owner-repo";
        assertTrue(concurrencyManager.tryAcquire(repoId));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            executor.submit(() -> concurrencyManager.release(repoId)).get();
            assertTrue(concurrencyManager.isLocked(repoId), "Lock must still be held if non-owner calls release");
        } finally {
            concurrencyManager.release(repoId);
            executor.shutdown();
        }
        assertFalse(concurrencyManager.isLocked(repoId));
    }
}

