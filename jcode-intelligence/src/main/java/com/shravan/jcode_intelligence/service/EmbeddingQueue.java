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
    private final ConcurrentHashMap<String, Throwable> failedJobCauses = new ConcurrentHashMap<>();
    private final AtomicBoolean admissionClosed = new AtomicBoolean(false);

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
     * Closes admission of new records into the queue.
     * Rejects subsequent submissions immediately to support graceful shutdown.
     */
    public void closeAdmission() {
        admissionClosed.set(true);
        log.info("Closed admission to embedding queue; subsequent submissions will be rejected");
    }

    /**
     * Opens admission of new records into the queue.
     * Invoked when persistence writers start or restart.
     */
    public void openAdmission() {
        admissionClosed.set(false);
        log.info("Opened admission to embedding queue; new submissions will be accepted");
    }

    /**
     * Checks if queue admission is closed.
     *
     * @return true if admission is closed, false otherwise
     */
    public boolean isAdmissionClosed() {
        return admissionClosed.get();
    }

    /**
     * Registers a new indexing job in the queue tracker before submitting records.
     *
     * @param jobId        unique job identifier; an existing job is left unchanged
     * @param repositoryId associated repository identifier
     */
    public void registerJob(String jobId, String repositoryId) {
        if (admissionClosed.get()) {
            throw new IllegalStateException("Cannot register job; embedding queue admission is closed");
        }
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
     * @throws IllegalStateException if admission is closed or the owning job has failed
     */
    public void submit(EmbeddedRecord record) throws InterruptedException {
        if (record == null) {
            return;
        }

        if (admissionClosed.get()) {
            record.acknowledgement().completeExceptionally(
                    new IllegalStateException("Embedding queue admission is closed"));
            throw new IllegalStateException("Cannot submit record; embedding queue admission is closed");
        }

        JobTracker tracker = activeJobs.get(record.jobId());
        if (tracker == null) {
            registerJob(record.jobId(), record.repositoryId());
            tracker = activeJobs.get(record.jobId());
        }

        if (tracker.isFailed() || failedJobs.contains(record.jobId())) {
            record.acknowledgement().completeExceptionally(
                    new IllegalStateException("Cannot submit record; job has failed: " + record.jobId()));
            throw new IllegalStateException("Job " + record.jobId() + " has already failed; aborting submission");
        }

        tracker.submittedCount.incrementAndGet();
        try {
            while (!queue.offer(record, 100, TimeUnit.MILLISECONDS)) {
                if (tracker.isFailed() || failedJobs.contains(record.jobId()) || admissionClosed.get()) {
                    tracker.submittedCount.decrementAndGet();
                    String msg = (admissionClosed.get())
                            ? "Embedding queue admission closed while awaiting queue capacity"
                            : "Job " + record.jobId() + " failed while awaiting queue capacity";
                    record.acknowledgement().completeExceptionally(new IllegalStateException(msg));
                    checkJobTerminalState(tracker);
                    throw new IllegalStateException(msg);
                }
            }
        } catch (InterruptedException e) {
            tracker.submittedCount.decrementAndGet();
            record.acknowledgement().completeExceptionally(e);
            checkJobTerminalState(tracker);
            throw e;
        }
    }

    /**
     * Submits a collection of embedded records to the queue atomically, applying backpressure
     * if the queue does not have enough remaining capacity. All records must belong to the same job.
     * The entire batch becomes visible to writers only when full capacity is admitted.
     *
     * @param records list of embedded records to submit
     * @throws InterruptedException if interrupted while waiting for queue space
     * @throws IllegalArgumentException if records in the batch belong to different jobs
     * @throws IllegalStateException if admission is closed or the owning job has failed
     */
    public void submitAll(List<EmbeddedRecord> records) throws InterruptedException {
        if (records == null || records.isEmpty()) {
            return;
        }

        if (admissionClosed.get()) {
            for (EmbeddedRecord r : records) {
                r.acknowledgement().completeExceptionally(
                        new IllegalStateException("Embedding queue admission is closed"));
            }
            throw new IllegalStateException("Cannot submit records; embedding queue admission is closed");
        }

        String jobId = records.get(0).jobId();
        String repoId = records.get(0).repositoryId();

        for (EmbeddedRecord r : records) {
            if (!jobId.equals(r.jobId())) {
                throw new IllegalArgumentException(
                        "submitAll requires all records to belong to the same job: expected " + jobId + ", found " + r.jobId());
            }
        }

        JobTracker tracker = activeJobs.computeIfAbsent(jobId, id -> new JobTracker(id, repoId));

        if (tracker.isFailed() || failedJobs.contains(jobId)) {
            for (EmbeddedRecord r : records) {
                r.acknowledgement().completeExceptionally(
                        new IllegalStateException("Cannot submit record; job has failed: " + jobId));
            }
            throw new IllegalStateException("Job " + jobId + " has already failed; aborting submission");
        }

        int count = records.size();
        tracker.submittedCount.addAndGet(count);

        try {
            synchronized (queue) {
                while (queue.remainingCapacity() < count) {
                    if (tracker.isFailed() || failedJobs.contains(jobId) || admissionClosed.get()) {
                        tracker.submittedCount.addAndGet(-count);
                        String msg = admissionClosed.get()
                                ? "Embedding queue admission closed while awaiting queue capacity"
                                : "Job " + jobId + " failed while awaiting queue capacity";
                        IllegalStateException ex = new IllegalStateException(msg);
                        for (EmbeddedRecord r : records) {
                            r.acknowledgement().completeExceptionally(ex);
                        }
                        checkJobTerminalState(tracker);
                        throw ex;
                    }
                    queue.wait(50);
                }
                queue.addAll(records);
            }
        } catch (InterruptedException e) {
            tracker.submittedCount.addAndGet(-count);
            for (EmbeddedRecord r : records) {
                r.acknowledgement().completeExceptionally(e);
            }
            checkJobTerminalState(tracker);
            throw e;
        }
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
            if (cause != null) {
                failedJobCauses.put(jobId, cause);
            }
        }
        JobTracker tracker = activeJobs.get(jobId);
        if (tracker != null) {
            tracker.submissionsCompleted.set(true);
            tracker.markFailed(cause);
            log.error("Job {} marked failed: {}", jobId, cause != null ? cause.getMessage() : "Unknown error");
            checkJobTerminalState(tracker);
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
            Throwable failure = failedJobCauses.remove(jobId);
            if (failure != null) {
                failedJobs.remove(jobId);
                if (failure instanceof Exception ex) {
                    throw ex;
                }
                throw new RuntimeException(failure);
            }
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
                failedJobs.remove(jobId);
                failedJobCauses.remove(jobId);
            }
        }
    }

    /**
     * Checks whether all currently active jobs have completed record submissions.
     *
     * @return true if there are active jobs and all have completed submissions, or if no active jobs exist
     */
    public boolean areAllSubmissionsCompleted() {
        if (activeJobs.isEmpty()) {
            return true;
        }
        for (JobTracker tracker : activeJobs.values()) {
            if (!tracker.submissionsCompleted.get() && !tracker.isFailed()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Drains available records across active jobs, waiting up to {@code timeoutMs} for records
     * to accumulate before returning a partial batch.
     *
     * <p>If active jobs are still submitting records in parallel and the drained batch has not
     * reached {@code maxBatchSize}, this method continues accumulating records up to {@code timeoutMs}
     * or until submissions are signaled complete, maximizing batch sizes and minimizing database calls.
     *
     * @param maxBatchSize maximum number of active records to return; must be positive
     * @param timeoutMs    maximum wait for records to accumulate, in milliseconds
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

        drainAvailableInto(batch, maxBatchSize);

        // If batch is still smaller than maxBatchSize and active jobs are still generating embeddings,
        // wait up to timeoutMs for parallel batches to arrive rather than immediately sending micro-batches
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (batch.size() < maxBatchSize && !areAllSubmissionsCompleted()) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                break;
            }
            EmbeddedRecord next = queue.poll(Math.min(remaining, 50), TimeUnit.MILLISECONDS);
            if (next != null) {
                if (isRecordActive(next)) {
                    batch.add(next);
                } else {
                    acknowledgeCancelled(next);
                }
                drainAvailableInto(batch, maxBatchSize);
            }
        }

        drainAvailableInto(batch, maxBatchSize);
        synchronized (queue) {
            queue.notifyAll();
        }
        return batch;
    }

    private void drainAvailableInto(List<EmbeddedRecord> batch, int maxBatchSize) {
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
    }

    /**
     * Cleans up tracking state and stored failure causes for a completed or aborted job.
     *
     * @param jobId indexing job identifier
     */
    public void cleanupJob(String jobId) {
        if (jobId != null) {
            activeJobs.remove(jobId);
            failedJobs.remove(jobId);
            failedJobCauses.remove(jobId);
        }
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

        if (record.jobId() != null) {
            failedJobs.add(record.jobId());
            if (cause != null) {
                failedJobCauses.put(record.jobId(), cause);
            }
        }

        JobTracker tracker = activeJobs.get(record.jobId());
        if (tracker != null) {
            tracker.failedCount.incrementAndGet();
            tracker.recordErrors.add(cause != null ? cause.getMessage() : "Unknown write error");
            tracker.markFailed(cause);
            checkJobTerminalState(tracker);
        }
    }

    /**
     * Drains all remaining records from the queue and marks them as failed.
     * Invoked during writer shutdown to prevent hanging producers.
     *
     * @param cause root cause of the writer shutdown or failure
     */
    public void drainAndFailRemaining(Throwable cause) {
        closeAdmission();
        EmbeddedRecord record;
        while ((record = queue.poll()) != null) {
            recordFailed(record, cause);
        }
        for (JobTracker tracker : activeJobs.values()) {
            tracker.markFailed(cause);
            checkJobTerminalState(tracker);
        }
        // Do not clear failedJobs or failedJobCauses here so waiting callers in awaitJobCompletion
        // can observe failure before retirement
        activeJobs.clear();
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
        JobTracker tracker = activeJobs.get(record.jobId());
        if (tracker != null) {
            tracker.failedCount.incrementAndGet();
            checkJobTerminalState(tracker);
        }
    }

    private void checkJobTerminalState(JobTracker tracker) {
        if (tracker.submissionsCompleted.get()) {
            int total = tracker.submittedCount.get();
            int persisted = tracker.persistedCount.get();
            int failed = tracker.failedCount.get();

            if (persisted + failed >= total) {
                if (!tracker.isFailed()) {
                    if (failed == 0) {
                        tracker.completionFuture.complete(null);
                        log.info("Job {} completed successfully (all {} records persisted)", tracker.jobId, persisted);
                    } else {
                        tracker.markFailed(new RuntimeException(
                                String.format("Job %s finished with %d failed record(s) out of %d total", tracker.jobId, failed, total)));
                    }
                }
                if (tracker.isFailed() && tracker.completionFuture.isDone()) {
                    activeJobs.remove(tracker.getJobId());
                    failedJobs.remove(tracker.getJobId());
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

