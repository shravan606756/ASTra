package com.shravan.jcode_intelligence.service;

import com.shravan.jcode_intelligence.exception.IndexingConflictException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Coordinates repository-level execution locks to serialize indexing jobs on the same
 * repository and eliminate race conditions between pre-indexing deletions and vector inserts.
 */
@Component
public class RepositoryConcurrencyManager {

    private static final Logger log = LoggerFactory.getLogger(RepositoryConcurrencyManager.class);

    private final ConcurrentHashMap<String, ReentrantLock> repositoryLocks = new ConcurrentHashMap<>();

    /**
     * Default constructor.
     */
    public RepositoryConcurrencyManager() {
    }

    /**
     * Attempts to acquire an exclusive lock for the specified repository immediately.
     *
     * @param repositoryId repository identifier
     * @return true if the lock was acquired, false if another job is already indexing this repository
     */
    public boolean tryAcquire(String repositoryId) {
        String key = normalizeRepoId(repositoryId);
        ReentrantLock lock = repositoryLocks.computeIfAbsent(key, k -> new ReentrantLock());
        if (lock.isHeldByCurrentThread()) {
            log.warn("Failed to acquire exclusive lock for repository: {} (already held by current thread)", key);
            return false;
        }
        boolean acquired = lock.tryLock();
        if (acquired) {
            log.info("Acquired exclusive indexing lock for repository: {}", key);
        } else {
            log.warn("Failed to acquire exclusive lock for repository: {} (already in progress)", key);
        }
        return acquired;
    }

    /**
     * Acquires an exclusive lock for the specified repository immediately, or throws
     * an {@link IndexingConflictException} if already locked.
     *
     * @param repositoryId repository identifier
     * @throws IndexingConflictException if another indexing job is actively indexing this repository
     */
    public void acquireOrThrow(String repositoryId) {
        if (!tryAcquire(repositoryId)) {
            throw new IndexingConflictException(
                    String.format("Concurrent indexing rejected: repository '%s' is actively being indexed", repositoryId));
        }
    }

    /**
     * Attempts to acquire an exclusive lock for the specified repository, waiting up to the specified timeout.
     *
     * @param repositoryId repository identifier
     * @param timeout      timeout duration
     * @param unit         time unit
     * @return true if lock was acquired within the timeout, false otherwise
     * @throws InterruptedException if interrupted while waiting
     */
    public boolean tryAcquire(String repositoryId, long timeout, TimeUnit unit) throws InterruptedException {
        String key = normalizeRepoId(repositoryId);
        ReentrantLock lock = repositoryLocks.computeIfAbsent(key, k -> new ReentrantLock());
        if (lock.isHeldByCurrentThread()) {
            log.warn("Failed to acquire exclusive lock for repository: {} (already held by current thread)", key);
            return false;
        }
        boolean acquired = lock.tryLock(timeout, unit);
        if (acquired) {
            log.info("Acquired exclusive indexing lock for repository: {} after waiting", key);
        } else {
            log.warn("Timed out waiting for exclusive lock for repository: {}", key);
        }
        return acquired;
    }

    /**
     * Releases the exclusive indexing lock for the specified repository.
     *
     * @param repositoryId repository identifier
     */
    public void release(String repositoryId) {
        String key = normalizeRepoId(repositoryId);
        ReentrantLock lock = repositoryLocks.get(key);
        if (lock != null && lock.isHeldByCurrentThread()) {
            lock.unlock();
            log.info("Released exclusive indexing lock for repository: {}", key);
            if (!lock.hasQueuedThreads()) {
                repositoryLocks.remove(key, lock);
            }
        }
    }

    /**
     * Checks if a repository currently has an active indexing lock.
     *
     * @param repositoryId repository identifier
     * @return true if locked, false otherwise
     */
    public boolean isLocked(String repositoryId) {
        String key = normalizeRepoId(repositoryId);
        ReentrantLock lock = repositoryLocks.get(key);
        return lock != null && lock.isLocked();
    }

    private String normalizeRepoId(String repositoryId) {
        return (repositoryId != null && !repositoryId.isBlank()) ? repositoryId.trim() : "default";
    }
}

