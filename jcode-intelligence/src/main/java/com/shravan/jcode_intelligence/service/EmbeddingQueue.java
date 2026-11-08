package com.shravan.jcode_intelligence.service;

import com.shravan.jcode_intelligence.config.EmbeddingQueueConfig;
import com.shravan.jcode_intelligence.model.EmbeddedRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe, bounded staging queue decoupling chunk embedding generation from database persistence.
 *
 * <p>Enforces backpressure on producers when queue capacity is reached, tracks per-job
 * completion and failure isolation, and drains heterogeneous (mixed-job) batches to eliminate
 * partial-batch deadlocks.
 */
@Component
public class EmbeddingQueue {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingQueue.class);

    private final BlockingQueue<EmbeddedRecord> queue;
    private final int capacity;
    private final ConcurrentHashMap<String, JobTracker> activeJobs = new ConcurrentHashMap<>();
    private final Set<String> failedJobs = ConcurrentHashMap.newKeySet();

    /**
     * Constructs an {@code EmbeddingQueue} with properties from {@link EmbeddingQueueConfig}.
     *
     * @param config queue configuration; null uses a capacity of 2000, and configured
     *               capacities below 10 are raised to 10
     */
    public EmbeddingQueue(EmbeddingQueueConfig config) {
        this.capacity = config != null ? Math.max(10, config.getCapacity()) : 2000;
        this.queue = new LinkedBlockingQueue<>(this.capacity);
    }

    /**
     * Registers a new indexing job in the queue tracker before submitting records.
     *
     * @param jobId        unique job identifier; an existing job is left unchanged
     * @param repositoryId associated repository identifier
     */
    public void registerJob(String jobId, String repositoryId) {
        activeJobs.computeIfAbsent(jobId, id -> new JobTracker(id, repositoryId));
        log.info("Registered indexing job: {} for repository: {}", jobId, repositoryId);
    }

    /**
     * Submits an embedded record to the queue, blocking if capacity is full to apply backpressure.
     *
     * <p>A missing job is registered automatically. A null record is ignored.
     *
     * @param record self-contained embedded record
     * @throws InterruptedException if interrupted while waiting for queue space
     * @throws IllegalStateException if the owning job has already been marked failed or cancelled
     */
    public void submit(EmbeddedRecord record) throws InterruptedException {
        if (record == null) {
            return;
        }

        JobTracker tracker = activeJobs.get(record.jobId());
        if (tracker == null) {
            registerJob(record.jobId(), record.repositoryId());
            tracker = activeJobs.get(record.jobId());
        }

        if (tracker.isFailed()) {
            record.acknowledgement().completeExceptionally(
                    new IllegalStateException("Cannot submit record; job has failed: " + record.jobId()));
            throw new IllegalStateException("Job " + record.jobId() + " has already failed; aborting submission");
        }

        tracker.submittedCount.incrementAndGet();
        queue.put(record);
    }

    /**
     * Signals that the producer has finished submitting all records for the specified job.
     *
     * @param jobId indexing job identifier
     */
    public void completeJob(String jobId) {
        JobTracker tracker = activeJobs.get(jobId);
        if (tracker != null) {
            tracker.submissionsCompleted.set(true);
            log.info("Job {} signaled submission complete (total submitted: {})", jobId, tracker.submittedCount.get());
            checkJobTerminalState(tracker);
        }
    }

    /**
     * Marks an indexing job as failed and releases callers waiting for its completion.
     * Queued entries are discarded and acknowledged exceptionally when drained; entries
     * already drained are not cancelled by this method.
     *
     * @param jobId indexing job identifier
     * @param cause root cause of the failure
     */
    public void failJob(String jobId, Throwable cause) {
        if (jobId != null) {
            failedJobs.add(jobId);
        }
        JobTracker tracker = activeJobs.get(jobId);
        if (tracker != null) {
            tracker.markFailed(cause);
            log.error("Job {} marked failed: {}", jobId, cause != null ? cause.getMessage() : "Unknown error");
        }
    }

    /**
     * Awaits completion of all records submitted by a job after submissions are complete.
     * Returns immediately if the job is not tracked. A timeout marks the job as failed;
     * a completed job is removed from tracking before this method returns or throws.
     *
     * @param jobId   indexing job identifier
     * @param timeout maximum duration to wait
     * @throws Exception if the job fails, times out, or the wait is interrupted; a job's
     *                   exception cause is rethrown, while a non-exception cause is wrapped
     */
    public void awaitJobCompletion(String jobId, Duration timeout) throws Exception {
        JobTracker tracker = activeJobs.get(jobId);
        if (tracker == null) {
            return;
        }
        try {
            tracker.completionFuture.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            failJob(jobId, new TimeoutException(String.format("Job %s timed out after %s", jobId, timeout)));
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof Exception ex) {
                throw ex;
            }
            throw new RuntimeException(cause);
        } finally {
            if (tracker.completionFuture.isDone()) {
                activeJobs.remove(jobId);
            }
        }
    }

    /**
     * Drains available records across active jobs, waiting up to {@code timeoutMs} for the
     * first entry if the queue is empty. Discarded entries are acknowledged exceptionally.
     * After the first entry, the method does not wait to fill the batch.
     *
     * @param maxBatchSize maximum number of active records to return; must be positive
     * @param timeoutMs    maximum wait for the first entry, in milliseconds
     * @return active records, possibly empty even if cancelled entries were drained
     * @throws InterruptedException if interrupted while waiting
     */
    public List<EmbeddedRecord> drainBatch(int maxBatchSize, long timeoutMs) throws InterruptedException {
        List<EmbeddedRecord> batch = new ArrayList<>(maxBatchSize);

        EmbeddedRecord first = queue.poll(timeoutMs, TimeUnit.MILLISECONDS);
        if (first == null) {
            return batch;
        }

        if (isRecordActive(first)) {
            batch.add(first);
        } else {
            acknowledgeCancelled(first);
        }

        // Drain up to maxBatchSize - 1 remaining elements greedily
        while (batch.size() < maxBatchSize) {
            EmbeddedRecord next = queue.poll();
            if (next == null) {
                break;
            }
            if (isRecordActive(next)) {
                batch.add(next);
            } else {
                acknowledgeCancelled(next);
            }
        }

        return batch;
    }

    /**
     * Records successful persistence of an embedded record, resolving its acknowledgement future.
     *
     * @param record successfully persisted record
     */
    public void recordPersisted(EmbeddedRecord record) {
        if (record == null) {
            return;
        }
        record.acknowledgement().complete(null);

        JobTracker tracker = activeJobs.get(record.jobId());
        if (tracker != null) {
            tracker.persistedCount.incrementAndGet();
            checkJobTerminalState(tracker);
        }
    }

    /**
     * Records failure to persist an embedded record, failing its acknowledgement future.
     *
     * @param record failed record
     * @param cause  underlying persistence exception
     */
    public void recordFailed(EmbeddedRecord record, Throwable cause) {
        if (record == null) {
            return;
        }
        record.acknowledgement().completeExceptionally(cause);

        JobTracker tracker = activeJobs.get(record.jobId());
        if (tracker != null) {
            tracker.failedCount.incrementAndGet();
            tracker.recordErrors.add(cause != null ? cause.getMessage() : "Unknown write error");
            tracker.markFailed(cause);
        }
    }

    /**
     * Returns the current number of staged records waiting in the queue.
     *
     * @return queue size
     */
    public int size() {
        return queue.size();
    }

    /**
     * Returns the configured queue capacity.
     *
     * @return queue capacity
     */
    public int getCapacity() {
        return capacity;
    }

    /**
     * Returns the current number of active tracked indexing jobs.
     *
     * @return active job count
     */
    public int getActiveJobCount() {
        return activeJobs.size();
    }

    private boolean isRecordActive(EmbeddedRecord record) {
        if (record == null || (record.jobId() != null && failedJobs.contains(record.jobId()))) {
            return false;
        }
        JobTracker tracker = activeJobs.get(record.jobId());
        return tracker != null && !tracker.isFailed();
    }

    private void acknowledgeCancelled(EmbeddedRecord record) {
        record.acknowledgement().completeExceptionally(
                new IllegalStateException("Record discarded: job " + record.jobId() + " was cancelled or failed"));
    }

    private void checkJobTerminalState(JobTracker tracker) {
        if (tracker.isFailed()) {
            return;
        }
        if (tracker.submissionsCompleted.get()) {
            int total = tracker.submittedCount.get();
            int persisted = tracker.persistedCount.get();
            int failed = tracker.failedCount.get();

            if (persisted + failed >= total) {
                if (failed == 0) {
                    tracker.completionFuture.complete(null);
                    log.info("Job {} completed successfully (all {} records persisted)", tracker.jobId, persisted);
                } else {
                    tracker.markFailed(new RuntimeException(
                            String.format("Job %s finished with %d failed record(s) out of %d total", tracker.jobId, failed, total)));
                }
            }
        }
    }

    /**
     * Internal tracking state for an individual indexing job.
     */
    public static class JobTracker {
        private final String jobId;
        private final String repositoryId;
        private final AtomicInteger submittedCount = new AtomicInteger(0);
        private final AtomicInteger persistedCount = new AtomicInteger(0);
        private final AtomicInteger failedCount = new AtomicInteger(0);
        private final AtomicBoolean submissionsCompleted = new AtomicBoolean(false);
        private final AtomicBoolean failed = new AtomicBoolean(false);
        private final CompletableFuture<Void> completionFuture = new CompletableFuture<>();
        private final List<String> recordErrors = Collections.synchronizedList(new ArrayList<>());

        public JobTracker(String jobId, String repositoryId) {
            this.jobId = jobId;
            this.repositoryId = repositoryId;
        }

        public boolean isFailed() {
            return failed.get();
        }

        public void markFailed(Throwable cause) {
            if (failed.compareAndSet(false, true)) {
                completionFuture.completeExceptionally(cause != null ? cause : new RuntimeException("Job failed"));
            }
        }

        public String getJobId() {
            return jobId;
        }

        public String getRepositoryId() {
            return repositoryId;
        }

        public int getSubmittedCount() {
            return submittedCount.get();
        }

        public int getPersistedCount() {
            return persistedCount.get();
        }

        public int getFailedCount() {
            return failedCount.get();
        }

        public CompletableFuture<Void> getCompletionFuture() {
            return completionFuture;
        }
    }
}

