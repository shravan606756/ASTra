package com.shravan.jcode_intelligence.service;

import com.shravan.jcode_intelligence.config.EmbeddingQueueConfig;
import com.shravan.jcode_intelligence.model.EmbeddedRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

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

    /** EWMA smoothing factor for inter-arrival time tracking. */
    public static final double EWMA_ALPHA = 0.2;
    /** Multiplier applied to EWMA for quiet-window timeout computation. */
    public static final double QUIET_WINDOW_MULTIPLIER = 1.5;

    /** Reasons for triggering a dynamic batch flush. */
    public enum FlushReason {
        TARGET_REACHED,
        TERMINAL_FLUSH,
        QUIET_WINDOW_ELAPSED,
        MAX_WAIT_EXCEEDED,
        EMPTY_IDLE
    }

    private final EmbeddingQueueConfig config;
    private final int capacity;
    private final ArrayDeque<EmbeddedRecord> buffer;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition recordsAvailable = lock.newCondition();
    private final Condition recordsDrained = lock.newCondition();

    private long lastSubmissionNanos = 0;
    private double ewmaInterArrivalNanos = 0;
    private long firstRecordArrivalNanos = 0;
    private volatile FlushReason lastFlushReason = FlushReason.EMPTY_IDLE;

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
        this.config = config != null ? config : new EmbeddingQueueConfig();
        this.capacity = Math.max(10, this.config.getCapacity());
        this.buffer = new ArrayDeque<>(this.capacity);
    }

    /**
     * Closes admission of new records into the queue.
     * Rejects subsequent submissions immediately to support graceful shutdown.
     */
    public void closeAdmission() {
        admissionClosed.set(true);
        log.info("Closed admission to embedding queue; subsequent submissions will be rejected");
        lock.lock();
        try {
            recordsAvailable.signalAll();
            recordsDrained.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Opens admission of new records into the queue.
     * Invoked when persistence writers start or restart.
     */
    public void openAdmission() {
        admissionClosed.set(false);
        log.info("Opened admission to embedding queue; new submissions will be accepted");
        lock.lock();
        try {
            recordsAvailable.signalAll();
            recordsDrained.signalAll();
        } finally {
            lock.unlock();
        }
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
        registerJob(jobId, repositoryId, null);
    }

    /**
     * Registers a new indexing job in the queue tracker with concurrency and workload hints.
     *
     * @param jobId              unique job identifier; an existing job is left unchanged
     * @param repositoryId       associated repository identifier
     * @param effectiveWorkers   effective number of parallel worker threads producing embeddings
     * @param producerBatchSize  chunk batch size produced per worker embedding call
     * @param totalDocuments     total number of documents to be processed by this job
     */
    public void registerJob(String jobId, String repositoryId, int effectiveWorkers, int producerBatchSize, int totalDocuments) {
        registerJob(jobId, repositoryId, new JobHint(effectiveWorkers, producerBatchSize, totalDocuments));
    }

    private void registerJob(String jobId, String repositoryId, JobHint hint) {
        if (admissionClosed.get()) {
            throw new IllegalStateException("Cannot register job; embedding queue admission is closed");
        }
        activeJobs.computeIfAbsent(jobId, id -> new JobTracker(id, repositoryId, hint));
        log.info("Registered indexing job: {} for repository: {} with hint: {}", jobId, repositoryId, hint);
        lock.lock();
        try {
            recordsAvailable.signalAll();
            recordsDrained.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Computes the aggregated target batch size across active submitting jobs under the queue lock.
     *
     * @param fallbackAdaptiveTargetSize fallback target batch size when hints are unavailable
     * @param maxBatchSize               maximum persistence batch size boundary
     * @return clamped target batch size in range {@code [1, min(maxBatchSize, capacity)]}
     */
    public int computeDynamicTarget(int fallbackAdaptiveTargetSize, int maxBatchSize) {
        lock.lock();
        try {
            int sum = 0;
            boolean anyHintedJob = false;
            for (JobTracker tracker : activeJobs.values()) {
                if (!tracker.submissionsCompleted.get() && !tracker.isFailed() && tracker.getHint() != null) {
                    anyHintedJob = true;
                    JobHint h = tracker.getHint();
                    long wave = (long) h.effectiveWorkers() * h.producerBatchSize();
                    long jobTarget = Math.min(wave, (long) h.totalDocuments());
                    sum += (int) jobTarget;
                }
            }
            int rawTarget = anyHintedJob ? sum : fallbackAdaptiveTargetSize;
            int upper = Math.min(maxBatchSize, capacity);
            return Math.max(1, Math.min(rawTarget, upper));
        } finally {
            lock.unlock();
        }
    }

    /**
     * Computes the dynamic target using configured adaptive target size.
     *
     * @param maxBatchSize maximum persistence batch size
     * @return clamped target batch size
     */
    public int computeDynamicTarget(int maxBatchSize) {
        return computeDynamicTarget(config.getAdaptiveTargetSize(), maxBatchSize);
    }

    /**
     * Returns the most recent flush reason determined during dynamic batch draining.
     *
     * @return flush reason
     */
    public FlushReason getLastFlushReason() {
        return lastFlushReason;
    }

    /**
     * Returns the current EWMA of inter-arrival time in nanoseconds.
     *
     * @return EWMA inter-arrival time in nanoseconds
     */
    public double getEwmaInterArrivalNanos() {
        lock.lock();
        try {
            return ewmaInterArrivalNanos;
        } finally {
            lock.unlock();
        }
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
        lock.lock();
        try {
            long waitNanos = TimeUnit.MILLISECONDS.toNanos(100);
            while (buffer.size() >= capacity) {
                if (tracker.isFailed() || failedJobs.contains(record.jobId()) || admissionClosed.get()) {
                    tracker.submittedCount.decrementAndGet();
                    String msg = (admissionClosed.get())
                            ? "Embedding queue admission closed while awaiting queue capacity"
                            : "Job " + record.jobId() + " failed while awaiting queue capacity";
                    record.acknowledgement().completeExceptionally(new IllegalStateException(msg));
                    recordsAvailable.signalAll();
                    recordsDrained.signalAll();
                    lock.unlock();
                    checkJobTerminalState(tracker);
                    throw new IllegalStateException(msg);
                }
                waitNanos = recordsDrained.awaitNanos(waitNanos);
                if (waitNanos <= 0) {
                    waitNanos = TimeUnit.MILLISECONDS.toNanos(100);
                }
            }

            // Recheck admission and job failure immediately before insertion
            if (tracker.isFailed() || failedJobs.contains(record.jobId()) || admissionClosed.get()) {
                tracker.submittedCount.decrementAndGet();
                String msg = (admissionClosed.get())
                        ? "Embedding queue admission closed while awaiting queue capacity"
                        : "Job " + record.jobId() + " failed while awaiting queue capacity";
                record.acknowledgement().completeExceptionally(new IllegalStateException(msg));
                recordsAvailable.signalAll();
                recordsDrained.signalAll();
                lock.unlock();
                checkJobTerminalState(tracker);
                throw new IllegalStateException(msg);
            }

            if (buffer.isEmpty()) {
                firstRecordArrivalNanos = System.nanoTime();
            }
            buffer.addLast(record);

            // Record EWMA event
            long now = System.nanoTime();
            if (lastSubmissionNanos != 0) {
                long interval = now - lastSubmissionNanos;
                ewmaInterArrivalNanos = EWMA_ALPHA * interval + (1.0 - EWMA_ALPHA) * ewmaInterArrivalNanos;
            }
            lastSubmissionNanos = now;

            recordsAvailable.signalAll();
        } catch (InterruptedException e) {
            tracker.submittedCount.decrementAndGet();
            record.acknowledgement().completeExceptionally(e);
            recordsAvailable.signalAll();
            recordsDrained.signalAll();
            lock.unlock();
            checkJobTerminalState(tracker);
            throw e;
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
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

        int count = records.size();

        // Reject oversized submitAll calls immediately, roll back count, and acknowledge each record exceptionally
        if (count > capacity) {
            JobTracker existingTracker = activeJobs.computeIfAbsent(jobId, id -> new JobTracker(id, repoId));
            existingTracker.submittedCount.addAndGet(count);
            existingTracker.submittedCount.addAndGet(-count);
            String msg = "Batch size " + count + " exceeds queue capacity " + capacity;
            IllegalStateException ex = new IllegalStateException(msg);
            for (EmbeddedRecord r : records) {
                r.acknowledgement().completeExceptionally(ex);
            }
            checkJobTerminalState(existingTracker);
            throw ex;
        }

        JobTracker tracker = activeJobs.computeIfAbsent(jobId, id -> new JobTracker(id, repoId));

        if (tracker.isFailed() || failedJobs.contains(jobId)) {
            for (EmbeddedRecord r : records) {
                r.acknowledgement().completeExceptionally(
                        new IllegalStateException("Cannot submit record; job has failed: " + jobId));
            }
            throw new IllegalStateException("Job " + jobId + " has already failed; aborting submission");
        }

        tracker.submittedCount.addAndGet(count);

        lock.lock();
        try {
            long waitNanos = TimeUnit.MILLISECONDS.toNanos(100);
            while (buffer.size() + count > capacity) {
                if (tracker.isFailed() || failedJobs.contains(jobId) || admissionClosed.get()) {
                    tracker.submittedCount.addAndGet(-count);
                    String msg = admissionClosed.get()
                            ? "Embedding queue admission closed while awaiting queue capacity"
                            : "Job " + jobId + " failed while awaiting queue capacity";
                    IllegalStateException ex = new IllegalStateException(msg);
                    for (EmbeddedRecord r : records) {
                        r.acknowledgement().completeExceptionally(ex);
                    }
                    recordsAvailable.signalAll();
                    recordsDrained.signalAll();
                    lock.unlock();
                    checkJobTerminalState(tracker);
                    throw ex;
                }
                waitNanos = recordsDrained.awaitNanos(waitNanos);
                if (waitNanos <= 0) {
                    waitNanos = TimeUnit.MILLISECONDS.toNanos(100);
                }
            }

            // Recheck admission and job failure immediately before insertion
            if (tracker.isFailed() || failedJobs.contains(jobId) || admissionClosed.get()) {
                tracker.submittedCount.addAndGet(-count);
                String msg = admissionClosed.get()
                        ? "Embedding queue admission closed while awaiting queue capacity"
                        : "Job " + jobId + " failed while awaiting queue capacity";
                IllegalStateException ex = new IllegalStateException(msg);
                for (EmbeddedRecord r : records) {
                    r.acknowledgement().completeExceptionally(ex);
                }
                recordsAvailable.signalAll();
                recordsDrained.signalAll();
                lock.unlock();
                checkJobTerminalState(tracker);
                throw ex;
            }

            if (buffer.isEmpty()) {
                firstRecordArrivalNanos = System.nanoTime();
            }
            buffer.addAll(records);

            // Record EWMA event
            long now = System.nanoTime();
            if (lastSubmissionNanos != 0) {
                long interval = now - lastSubmissionNanos;
                ewmaInterArrivalNanos = EWMA_ALPHA * interval + (1.0 - EWMA_ALPHA) * ewmaInterArrivalNanos;
            }
            lastSubmissionNanos = now;

            recordsAvailable.signalAll();
        } catch (InterruptedException e) {
            tracker.submittedCount.addAndGet(-count);
            for (EmbeddedRecord r : records) {
                r.acknowledgement().completeExceptionally(e);
            }
            recordsAvailable.signalAll();
            recordsDrained.signalAll();
            lock.unlock();
            checkJobTerminalState(tracker);
            throw e;
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
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
        lock.lock();
        try {
            recordsAvailable.signalAll();
            recordsDrained.signalAll();
        } finally {
            lock.unlock();
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
        lock.lock();
        try {
            recordsAvailable.signalAll();
            recordsDrained.signalAll();
        } finally {
            lock.unlock();
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
     * <p>Preserves legacy drain semantics with signal-driven {@code awaitNanos} waiting rather than busy polling.
     *
     * @param maxBatchSize maximum number of active records to return; must be positive
     * @param timeoutMs    maximum wait for records to accumulate, in milliseconds
     * @return active records, possibly empty even if cancelled entries were drained
     * @throws InterruptedException if interrupted while waiting
     */
    public List<EmbeddedRecord> drainBatch(int maxBatchSize, long timeoutMs) throws InterruptedException {
        List<EmbeddedRecord> batch = new ArrayList<>(maxBatchSize);
        long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs);

        lock.lock();
        try {
            // Phase 1: Wait for the first record
            long remainingNanos = timeoutNanos;
            while (buffer.isEmpty()) {
                if (areAllSubmissionsCompleted() || admissionClosed.get() || remainingNanos <= 0) {
                    return batch; // early return or timeout with empty buffer
                }
                remainingNanos = recordsAvailable.awaitNanos(remainingNanos);
            }

            // Drain available records from buffer
            drainAvailableInto(batch, maxBatchSize);

            // Phase 2: Accumulate more records if batch is not full and submissions still in progress
            if (batch.size() < maxBatchSize && !areAllSubmissionsCompleted()) {
                long accumulationDeadline = System.nanoTime() + timeoutNanos;
                while (batch.size() < maxBatchSize && !areAllSubmissionsCompleted()) {
                    long remaining = accumulationDeadline - System.nanoTime();
                    if (remaining <= 0) {
                        break;
                    }
                    recordsAvailable.awaitNanos(remaining);
                    drainAvailableInto(batch, maxBatchSize);
                }
            }

            if (buffer.isEmpty()) {
                firstRecordArrivalNanos = 0;
            }
        } catch (InterruptedException e) {
            // Restore already-removed records to buffer head in reverse order
            for (int i = batch.size() - 1; i >= 0; i--) {
                buffer.addFirst(batch.get(i));
            }
            batch.clear();
            recordsAvailable.signalAll();
            recordsDrained.signalAll();
            lock.unlock();
            throw e;
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }

        // Signal producers that space has been freed
        lock.lock();
        try {
            recordsDrained.signalAll();
        } finally {
            lock.unlock();
        }

        return batch;
    }

    /**
     * Drains records dynamically based on active producer wave sizes, quiet window settlement,
     * and job completion status using default dynamic configuration parameters.
     *
     * @param targetBatchSize target dynamic batch size
     * @param maxBatchSize    maximum batch size constraint
     * @return drained embedded records
     * @throws InterruptedException if interrupted while awaiting records
     */
    public List<EmbeddedRecord> drainDynamicBatch(int targetBatchSize, int maxBatchSize) throws InterruptedException {
        return drainDynamicBatch(targetBatchSize, maxBatchSize,
                config.getQuietWindowMinMs(), config.getQuietWindowMaxMs(), config.getMaxWaitMs());
    }

    /**
     * Drains records dynamically based on active producer wave sizes, quiet window settlement,
     * and job completion status.
     *
     * <p>Evaluates flush predicates under the lock and retains records in the buffer until:
     * <ul>
     *     <li>Target batch size is reached</li>
     *     <li>All submissions are completed or admission closed (terminal flush)</li>
     *     <li>Quiet window {@code clamp(min, max, 1.5 * EMA)} has elapsed without new arrivals</li>
     *     <li>Oldest pending record age exceeds {@code maxWaitMs}</li>
     * </ul>
     *
     * @param targetBatchSize target dynamic batch size
     * @param maxBatchSize    maximum batch size constraint
     * @param quietMinMs      minimum quiet window in milliseconds
     * @param quietMaxMs      maximum quiet window in milliseconds
     * @param maxWaitMs       maximum waiting time in milliseconds before mandatory flush
     * @return drained embedded records
     * @throws InterruptedException if interrupted while awaiting records
     */
    public List<EmbeddedRecord> drainDynamicBatch(int targetBatchSize, int maxBatchSize,
                                                  long quietMinMs, long quietMaxMs, long maxWaitMs) throws InterruptedException {
        List<EmbeddedRecord> batch = new ArrayList<>(maxBatchSize);

        lock.lock();
        try {
            while (true) {
                long now = System.nanoTime();
                boolean allCompleted = areAllSubmissionsCompleted();
                if (allCompleted) {
                    lastSubmissionNanos = 0;
                    ewmaInterArrivalNanos = 0;
                }

                if (!buffer.isEmpty()) {
                    if (buffer.size() >= targetBatchSize) {
                        lastFlushReason = FlushReason.TARGET_REACHED;
                        break;
                    }
                    if (allCompleted || admissionClosed.get()) {
                        lastFlushReason = FlushReason.TERMINAL_FLUSH;
                        break;
                    }

                    long quietWindowNanos;
                    if (ewmaInterArrivalNanos <= 0) {
                        quietWindowNanos = TimeUnit.MILLISECONDS.toNanos(quietMinMs);
                    } else {
                        long minNanos = TimeUnit.MILLISECONDS.toNanos(quietMinMs);
                        long maxNanos = TimeUnit.MILLISECONDS.toNanos(quietMaxMs);
                        long candidate = (long) (QUIET_WINDOW_MULTIPLIER * ewmaInterArrivalNanos);
                        quietWindowNanos = Math.max(minNanos, Math.min(maxNanos, candidate));
                    }

                    if (lastSubmissionNanos > 0 && (now - lastSubmissionNanos >= quietWindowNanos)) {
                        lastFlushReason = FlushReason.QUIET_WINDOW_ELAPSED;
                        break;
                    }

                    long maxWaitNanos = TimeUnit.MILLISECONDS.toNanos(maxWaitMs);
                    if (firstRecordArrivalNanos > 0 && (now - firstRecordArrivalNanos >= maxWaitNanos)) {
                        lastFlushReason = FlushReason.MAX_WAIT_EXCEEDED;
                        break;
                    }

                    // Await nearest deadline
                    long timeUntilQuiet = (lastSubmissionNanos > 0)
                            ? (lastSubmissionNanos + quietWindowNanos - now)
                            : quietWindowNanos;
                    long timeUntilMaxWait = (firstRecordArrivalNanos > 0)
                            ? (firstRecordArrivalNanos + maxWaitNanos - now)
                            : maxWaitNanos;
                    long waitNanos = Math.min(Math.max(1L, timeUntilQuiet), Math.max(1L, timeUntilMaxWait));

                    recordsAvailable.awaitNanos(waitNanos);
                } else {
                    // Buffer is empty
                    if (admissionClosed.get()) {
                        lastFlushReason = FlushReason.TERMINAL_FLUSH;
                        return batch;
                    }
                    long idleBoundNanos = TimeUnit.MILLISECONDS.toNanos(quietMinMs);
                    recordsAvailable.awaitNanos(idleBoundNanos);
                    if (buffer.isEmpty()) {
                        lastFlushReason = admissionClosed.get() ? FlushReason.TERMINAL_FLUSH : FlushReason.EMPTY_IDLE;
                        return batch;
                    }
                }
            }

            drainAvailableInto(batch, maxBatchSize);
            if (buffer.isEmpty()) {
                firstRecordArrivalNanos = 0;
            }
        } catch (InterruptedException e) {
            for (int i = batch.size() - 1; i >= 0; i--) {
                buffer.addFirst(batch.get(i));
            }
            batch.clear();
            recordsAvailable.signalAll();
            recordsDrained.signalAll();
            lock.unlock();
            throw e;
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }

        lock.lock();
        try {
            recordsDrained.signalAll();
        } finally {
            lock.unlock();
        }

        return batch;
    }

    /**
     * Drains available records from the buffer into the batch, skipping cancelled records.
     * Signals {@code recordsDrained} after each removal.
     * Must be called while holding the lock.
     */
    private void drainAvailableInto(List<EmbeddedRecord> batch, int maxBatchSize) {
        while (batch.size() < maxBatchSize) {
            EmbeddedRecord next = buffer.pollFirst();
            if (next == null) {
                break;
            }
            recordsDrained.signal();
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
        lock.lock();
        try {
            recordsAvailable.signalAll();
            recordsDrained.signalAll();
        } finally {
            lock.unlock();
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
            lock.lock();
            try {
                recordsAvailable.signalAll();
                recordsDrained.signalAll();
            } finally {
                lock.unlock();
            }
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
        List<EmbeddedRecord> remaining;
        lock.lock();
        try {
            remaining = new ArrayList<>(buffer);
            buffer.clear();
            firstRecordArrivalNanos = 0;
            recordsDrained.signalAll();
        } finally {
            lock.unlock();
        }
        for (EmbeddedRecord record : remaining) {
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
        lock.lock();
        try {
            return buffer.size();
        } finally {
            lock.unlock();
        }
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
     * Immutable workload and concurrency hint provided during job registration.
     *
     * @param effectiveWorkers   effective number of parallel worker threads producing embeddings
     * @param producerBatchSize  chunk batch size produced per worker embedding call
     * @param totalDocuments     total number of documents to be processed by this job
     */
    public record JobHint(int effectiveWorkers, int producerBatchSize, int totalDocuments) {}

    /**
     * Internal tracking state for an individual indexing job.
     */
    public static class JobTracker {
        private final String jobId;
        private final String repositoryId;
        private final JobHint hint;
        private final AtomicInteger submittedCount = new AtomicInteger(0);
        private final AtomicInteger persistedCount = new AtomicInteger(0);
        private final AtomicInteger failedCount = new AtomicInteger(0);
        private final AtomicBoolean submissionsCompleted = new AtomicBoolean(false);
        private final AtomicBoolean failed = new AtomicBoolean(false);
        private final CompletableFuture<Void> completionFuture = new CompletableFuture<>();
        private final List<String> recordErrors = Collections.synchronizedList(new ArrayList<>());

        public JobTracker(String jobId, String repositoryId) {
            this(jobId, repositoryId, null);
        }

        public JobTracker(String jobId, String repositoryId, JobHint hint) {
            this.jobId = jobId;
            this.repositoryId = repositoryId;
            this.hint = hint;
        }

        public JobHint getHint() {
            return hint;
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
