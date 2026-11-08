package com.shravan.jcode_intelligence.service;

import com.shravan.jcode_intelligence.config.EmbeddingQueueConfig;
import com.shravan.jcode_intelligence.model.EmbeddedRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests verifying bounded staging capacity, backpressure, heterogeneous mixed-job
 * batch draining, timeout partial flushes, and per-job lifecycle tracking in {@link EmbeddingQueue}.
 */
class EmbeddingQueueTest {

    private EmbeddingQueueConfig config;
    private EmbeddingQueue queue;

    @BeforeEach
    void setUp() {
        config = new EmbeddingQueueConfig(200, 50, 10, 150, 2, 3, 50);
        queue = new EmbeddingQueue(config);
    }

    @Test
    @DisplayName("Heterogeneous batch draining drains records across 5 concurrent jobs without deadlocking")
    void testHeterogeneousDrainingDoesNotDeadlock() throws Exception {
        int jobCount = 5;
        int recordsPerJob = 20; // 5 * 20 = 100 records total (none alone reach batchSize 50)
        ExecutorService producers = Executors.newFixedThreadPool(jobCount);
        CountDownLatch startLatch = new CountDownLatch(1);

        List<Future<?>> producerFutures = new ArrayList<>();
        for (int j = 1; j <= jobCount; j++) {
            String jobId = "job-" + j;
            String repoId = "repo-" + j;
            queue.registerJob(jobId, repoId);

            producerFutures.add(producers.submit(() -> {
                try {
                    startLatch.await();
                    for (int r = 1; r <= recordsPerJob; r++) {
                        String id = EmbeddedRecord.generateDeterministicId(repoId, "chunk-" + r, "content-" + r);
                        EmbeddedRecord record = new EmbeddedRecord(
                                id, jobId, repoId, "content-" + r, Map.of("index", r), new float[]{0.1f}, new CompletableFuture<>());
                        queue.submit(record);
                    }
                    queue.completeJob(jobId);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }

        // Release producers simultaneously
        startLatch.countDown();

        for (Future<?> f : producerFutures) {
            f.get(5, TimeUnit.SECONDS);
        }

        assertEquals(100, queue.size(), "Queue must hold all 100 submitted records");

        // Drain in batches of 50
        List<EmbeddedRecord> batch1 = queue.drainBatch(50, 200);
        assertEquals(50, batch1.size(), "First batch must drain 50 records");

        // Verify heterogeneous mix of job IDs in batch 1
        Set<String> jobsInBatch1 = new HashSet<>();
        for (EmbeddedRecord r : batch1) {
            jobsInBatch1.add(r.jobId());
        }
        assertTrue(jobsInBatch1.size() > 1, "Batch must be heterogeneous and contain records from multiple jobs");

        List<EmbeddedRecord> batch2 = queue.drainBatch(50, 200);
        assertEquals(50, batch2.size(), "Second batch must drain the remaining 50 records");

        assertEquals(0, queue.size(), "Queue must be completely drained");
        producers.shutdown();
    }

    @Test
    @DisplayName("Partial batch drains after timeout without blocking indefinitely")
    void testTimeBoundedFlushingForPartialBatch() throws Exception {
        String jobId = "job-partial";
        queue.registerJob(jobId, "repo-partial");

        // Submit only 5 records (well below batchSize of 50)
        for (int i = 1; i <= 5; i++) {
            String id = EmbeddedRecord.generateDeterministicId("repo-partial", "chunk-" + i, "data");
            queue.submit(new EmbeddedRecord(id, jobId, "repo-partial", "data", Map.of(), new float[]{0.5f}, new CompletableFuture<>()));
        }

        long start = System.currentTimeMillis();
        // Drain batch requesting 50 items with 100ms timeout
        List<EmbeddedRecord> drained = queue.drainBatch(50, 100);
        long elapsed = System.currentTimeMillis() - start;

        assertEquals(5, drained.size(), "Must drain all available partial records");
        assertTrue(elapsed < 1000, "Must return promptly without hanging");
    }

    @Test
    @DisplayName("Capacity backpressure blocks producers when queue is full until space is freed")
    void testCapacityBackpressure() throws Exception {
        EmbeddingQueue smallQueue = new EmbeddingQueue(new EmbeddingQueueConfig(10, 5, 2, 100, 1, 3, 50));
        String jobId = "job-backpressure";
        smallQueue.registerJob(jobId, "repo-bp");

        // Fill queue to capacity (10)
        for (int i = 1; i <= 10; i++) {
            String id = EmbeddedRecord.generateDeterministicId("repo-bp", "c-" + i, "c");
            smallQueue.submit(new EmbeddedRecord(id, jobId, "repo-bp", "c", Map.of(), new float[]{0.1f}, new CompletableFuture<>()));
        }

        assertEquals(10, smallQueue.size());

        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicInteger submitted11th = new AtomicInteger(0);

        try {
            Future<?> future11th = executor.submit(() -> {
                try {
                    String id11 = EmbeddedRecord.generateDeterministicId("repo-bp", "c-11", "c");
                    smallQueue.submit(new EmbeddedRecord(id11, jobId, "repo-bp", "c", Map.of(), new float[]{0.1f}, new CompletableFuture<>()));
                    submitted11th.incrementAndGet();
                } catch (InterruptedException ignored) {}
            });

            // Producer must initially be blocked waiting for capacity
            Thread.sleep(50);
            assertEquals(0, submitted11th.get(), "11th record must be blocked by backpressure");

            // Drain 1 record to unblock
            List<EmbeddedRecord> drained = smallQueue.drainBatch(1, 50);
            assertEquals(1, drained.size());

            // Producer should now proceed deterministically
            future11th.get(2, TimeUnit.SECONDS);
            assertEquals(1, submitted11th.get(), "11th record must be accepted after queue space is freed");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("failJob immediately unblocks awaitJobCompletion and cancels staged records")
    void testFailJobCancelsRecordsAndUnblocksAwait() throws Exception {
        String jobId = "job-fail-test";
        queue.registerJob(jobId, "repo-fail");

        CompletableFuture<Void> ack1 = new CompletableFuture<>();
        String id1 = EmbeddedRecord.generateDeterministicId("repo-fail", "c-1", "x");
        EmbeddedRecord r1 = new EmbeddedRecord(id1, jobId, "repo-fail", "x", Map.of(), new float[]{0.1f}, ack1);
        queue.submit(r1);

        // Fail job due to AST parser error
        queue.failJob(jobId, new IllegalArgumentException("Invalid syntax in file"));

        // Awaiting completion must throw immediately
        Exception ex = assertThrows(Exception.class, () ->
                queue.awaitJobCompletion(jobId, Duration.ofSeconds(2)));
        assertTrue(ex.getMessage().contains("Invalid syntax in file"));

        // Draining should discard cancelled records and fail their acknowledgements
        List<EmbeddedRecord> drained = queue.drainBatch(10, 50);
        assertTrue(drained.isEmpty(), "Cancelled job records must be discarded during drain");
        assertTrue(ack1.isCompletedExceptionally(), "Record acknowledgement must be completed exceptionally");
    }

    @Test
    @DisplayName("Successful job completion lifecycle resolves awaitJobCompletion once all records are persisted")
    void testSuccessfulJobCompletionLifecycle() throws Exception {
        String jobId = "job-success";
        queue.registerJob(jobId, "repo-success");

        List<EmbeddedRecord> records = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            String id = EmbeddedRecord.generateDeterministicId("repo-success", "chunk-" + i, "txt");
            EmbeddedRecord r = new EmbeddedRecord(id, jobId, "repo-success", "txt", Map.of(), new float[]{0.1f}, new CompletableFuture<>());
            records.add(r);
            queue.submit(r);
        }

        queue.completeJob(jobId);

        // Simulate persistence writer processing
        for (EmbeddedRecord r : records) {
            queue.recordPersisted(r);
            assertTrue(r.acknowledgement().isDone(), "Acknowledgement future must be completed");
        }

        // Await should return cleanly without exception
        assertDoesNotThrow(() -> queue.awaitJobCompletion(jobId, Duration.ofSeconds(1)));
    }

    @Test
    @DisplayName("submitAll atomically enqueues batch and drainBatch consolidates available records")
    void testSubmitAllAndConsolidatedDraining() throws Exception {
        String jobId = "job-consolidate";
        queue.registerJob(jobId, "repo-consolidate");

        List<EmbeddedRecord> batch = new ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            String id = EmbeddedRecord.generateDeterministicId("repo-consolidate", "chunk-" + i, "data");
            batch.add(new EmbeddedRecord(id, jobId, "repo-consolidate", "data", Map.of(), new float[]{0.1f}, new CompletableFuture<>()));
        }

        queue.submitAll(batch);
        queue.completeJob(jobId);

        assertEquals(30, queue.size(), "Queue must hold all 30 records submitted via submitAll");

        List<EmbeddedRecord> drained = queue.drainBatch(50, 100);
        assertEquals(30, drained.size(), "drainBatch must drain all 30 completed records in a single bulk slice");
    }

    @Test
    @DisplayName("submitAll under constrained capacity rejects closed admission without partial batch visibility")
    void testSubmitAllConstrainedCapacityAndClosedAdmission() throws Exception {
        // Minimum capacity enforced by EmbeddingQueue is 10
        EmbeddingQueue smallQueue = new EmbeddingQueue(new EmbeddingQueueConfig(10, 5, 2, 100, 1, 3, 50));
        String jobId = "job-atomic-test";
        smallQueue.registerJob(jobId, "repo-atomic");

        // Submit 8 records to small queue (capacity is 10, remaining is 2)
        List<EmbeddedRecord> initialBatch = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            String id = EmbeddedRecord.generateDeterministicId("repo-atomic", "c-" + i, "init");
            initialBatch.add(new EmbeddedRecord(id, jobId, "repo-atomic", "init", Map.of(), new float[]{0.1f}, new CompletableFuture<>()));
        }
        smallQueue.submitAll(initialBatch);
        assertEquals(8, smallQueue.size());

        // Try submitting 4 more records (4 > remaining capacity 2)
        List<EmbeddedRecord> secondBatch = new ArrayList<>();
        for (int i = 9; i <= 12; i++) {
            String id = EmbeddedRecord.generateDeterministicId("repo-atomic", "c-" + i, "sec");
            secondBatch.add(new EmbeddedRecord(id, jobId, "repo-atomic", "sec", Map.of(), new float[]{0.1f}, new CompletableFuture<>()));
        }

        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicBoolean threwException = new AtomicBoolean(false);

        Future<?> future = executor.submit(() -> {
            try {
                smallQueue.submitAll(secondBatch);
            } catch (Exception e) {
                threwException.set(true);
            }
        });

        // While producer is waiting for capacity, verify no partial batch is visible
        Thread.sleep(50);
        assertEquals(8, smallQueue.size(), "No partial batch of secondBatch must be visible in queue");

        smallQueue.closeAdmission();
        future.get(2, TimeUnit.SECONDS);

        assertTrue(threwException.get(), "Blocked submitAll must throw exception when admission is closed");
        assertEquals(8, smallQueue.size(), "Queue size must remain 8; no partial records from secondBatch admitted");

        for (EmbeddedRecord r : secondBatch) {
            assertTrue(r.acknowledgement().isDone(), "All records of rejected batch must have acknowledgement completed");
            assertTrue(r.acknowledgement().isCompletedExceptionally(), "Acknowledgement must be completed exceptionally");
        }

        executor.shutdownNow();
    }

    @Test
    @DisplayName("Scaling: 25 producer batches consolidate into 1 single queue poll (25:1 batch-to-poll ratio)")
    void testScalingBatchesToQueuePollRatio() throws Exception {
        int producerBatchCount = 25;
        int recordsPerBatch = 10; // 250 records total
        int maxPersistenceBatchSize = 250;

        EmbeddingQueue scalingQueue = new EmbeddingQueue(
                new EmbeddingQueueConfig(1000, maxPersistenceBatchSize, 10, 500, 1, 3, 50));
        String jobId = "job-scale-ratio";
        scalingQueue.registerJob(jobId, "repo-scale");

        // Submit 25 batches in parallel across 4 worker threads
        ExecutorService workers = Executors.newFixedThreadPool(4);
        List<Future<?>> futures = new ArrayList<>();

        for (int b = 1; b <= producerBatchCount; b++) {
            final int batchId = b;
            futures.add(workers.submit(() -> {
                List<EmbeddedRecord> batch = new ArrayList<>(recordsPerBatch);
                for (int r = 1; r <= recordsPerBatch; r++) {
                    String chunkId = "b" + batchId + "-c" + r;
                    String id = EmbeddedRecord.generateDeterministicId("repo-scale", chunkId, "txt");
                    batch.add(new EmbeddedRecord(
                            id, jobId, "repo-scale", "txt", Map.of("b", batchId, "r", r),
                            new float[]{0.1f}, new CompletableFuture<>()));
                }
                try {
                    scalingQueue.submitAll(batch);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            }));
        }

        for (Future<?> f : futures) {
            f.get(5, TimeUnit.SECONDS);
        }

        scalingQueue.completeJob(jobId);
        workers.shutdown();

        assertEquals(250, scalingQueue.size(), "Queue must hold all 250 records from 25 batches");

        // Drain with maxPersistenceBatchSize = 250
        List<EmbeddedRecord> consolidatedBatch = scalingQueue.drainBatch(maxPersistenceBatchSize, 100);

        // Assert that all 25 producer batches were consolidated into 1 single poll
        assertEquals(250, consolidatedBatch.size(),
                "All 25 producer batches must consolidate into exactly 1 queue poll batch (25:1 ratio)");
        assertEquals(0, scalingQueue.size(), "Queue must be completely drained in 1 poll");

        // Acknowledge all and verify clean completion
        for (EmbeddedRecord r : consolidatedBatch) {
            scalingQueue.recordPersisted(r);
        }
        assertDoesNotThrow(() -> scalingQueue.awaitJobCompletion(jobId, Duration.ofSeconds(1)));
    }

    @Test
    @DisplayName("Scaling: 1,000 records across 40 batches under capacity backpressure maintain 8:1 consolidation ratio")
    void testLargeScaleBatchConsolidationWithBackpressure() throws Exception {
        int totalRecords = 1000;
        int producerBatchCount = 40;
        int recordsPerBatch = totalRecords / producerBatchCount; // 25 records per batch
        int queueCapacity = 200; // Constrained capacity (20% of total load) to force backpressure
        int persistenceBatchSize = 200;

        EmbeddingQueue backpressureQueue = new EmbeddingQueue(
                new EmbeddingQueueConfig(queueCapacity, persistenceBatchSize, 10, 200, 1, 3, 50));
        String jobId = "job-backpressure-scale";
        backpressureQueue.registerJob(jobId, "repo-bp-scale");

        ExecutorService producers = Executors.newFixedThreadPool(4);
        AtomicInteger totalDrained = new AtomicInteger(0);
        AtomicInteger nonNullPollCount = new AtomicInteger(0);

        // Start background consumer thread simulating PgVectorBulkWriter
        ExecutorService consumer = Executors.newSingleThreadExecutor();
        Future<?> consumerFuture = consumer.submit(() -> {
            try {
                while (totalDrained.get() < totalRecords) {
                    List<EmbeddedRecord> batch = backpressureQueue.drainBatch(persistenceBatchSize, 50);
                    if (!batch.isEmpty()) {
                        nonNullPollCount.incrementAndGet();
                        totalDrained.addAndGet(batch.size());
                        for (EmbeddedRecord r : batch) {
                            backpressureQueue.recordPersisted(r);
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // Submit 40 batches from 4 concurrent producer threads
        List<Future<?>> producerFutures = new ArrayList<>();
        for (int b = 1; b <= producerBatchCount; b++) {
            final int batchIdx = b;
            producerFutures.add(producers.submit(() -> {
                List<EmbeddedRecord> batch = new ArrayList<>(recordsPerBatch);
                for (int r = 1; r <= recordsPerBatch; r++) {
                    String chunkId = "chunk-" + batchIdx + "-" + r;
                    String id = EmbeddedRecord.generateDeterministicId("repo-bp-scale", chunkId, "data");
                    batch.add(new EmbeddedRecord(
                            id, jobId, "repo-bp-scale", "data", Map.of(), new float[]{0.1f}, new CompletableFuture<>()));
                }
                try {
                    backpressureQueue.submitAll(batch);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            }));
        }

        for (Future<?> f : producerFutures) {
            f.get(10, TimeUnit.SECONDS);
        }
        backpressureQueue.completeJob(jobId);

        consumerFuture.get(10, TimeUnit.SECONDS);

        // Verify total records processed
        assertEquals(totalRecords, totalDrained.get(), "All 1,000 records must be successfully persisted");

        // Verify batches to poll ratio: 40 producer batches drained in exactly 5 polls of size 200 (8:1 ratio)
        assertEquals(5, nonNullPollCount.get(),
                "40 producer batches of 25 records must be drained in exactly 5 polls of size 200 (8:1 ratio)");

        assertDoesNotThrow(() -> backpressureQueue.awaitJobCompletion(jobId, Duration.ofSeconds(1)));

        producers.shutdownNow();
        consumer.shutdownNow();
    }

    @Test
    @DisplayName("Scaling: 10 concurrent repositories producing 50 batches consolidate with 10:1 ratio")
    void testMultiJobScalingWithHeterogeneousDrainingRatio() throws Exception {
        int jobCount = 10;
        int batchesPerJob = 5;
        int recordsPerBatch = 10; // 10 * 5 * 10 = 500 records total across 50 batches
        int persistenceBatchSize = 100;

        EmbeddingQueue multiJobQueue = new EmbeddingQueue(
                new EmbeddingQueueConfig(1000, persistenceBatchSize, 10, 100, 1, 3, 50));

        ExecutorService jobPool = Executors.newFixedThreadPool(jobCount);
        List<Future<?>> jobFutures = new ArrayList<>();

        for (int j = 1; j <= jobCount; j++) {
            final int jobIndex = j;
            final String jobId = "job-multi-" + jobIndex;
            final String repoId = "repo-multi-" + jobIndex;
            multiJobQueue.registerJob(jobId, repoId);

            jobFutures.add(jobPool.submit(() -> {
                for (int b = 1; b <= batchesPerJob; b++) {
                    List<EmbeddedRecord> batch = new ArrayList<>(recordsPerBatch);
                    for (int r = 1; r <= recordsPerBatch; r++) {
                        String id = EmbeddedRecord.generateDeterministicId(repoId, "c-" + b + "-" + r, "code");
                        batch.add(new EmbeddedRecord(
                                id, jobId, repoId, "code", Map.of(), new float[]{0.1f}, new CompletableFuture<>()));
                    }
                    try {
                        multiJobQueue.submitAll(batch);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                }
                multiJobQueue.completeJob(jobId);
            }));
        }

        for (Future<?> f : jobFutures) {
            f.get(5, TimeUnit.SECONDS);
        }

        assertEquals(500, multiJobQueue.size(), "Queue must hold all 500 submitted records");

        // Drain the queue in batches of 100
        int pollCount = 0;
        int recordsDrained = 0;
        while (multiJobQueue.size() > 0) {
            List<EmbeddedRecord> drained = multiJobQueue.drainBatch(persistenceBatchSize, 50);
            if (!drained.isEmpty()) {
                pollCount++;
                recordsDrained += drained.size();
                for (EmbeddedRecord r : drained) {
                    multiJobQueue.recordPersisted(r);
                }
            }
        }

        // 50 producer batches should drain in exactly 5 polls of 100 (10:1 ratio)
        assertEquals(500, recordsDrained, "Must drain all 500 records across 10 jobs");
        assertEquals(5, pollCount, "50 producer batches must be consolidated into exactly 5 polls of size 100 (10:1 ratio)");

        // Verify all 10 jobs complete successfully
        for (int j = 1; j <= jobCount; j++) {
            String jobId = "job-multi-" + j;
            assertDoesNotThrow(() -> multiJobQueue.awaitJobCompletion(jobId, Duration.ofSeconds(1)));
        }

        jobPool.shutdownNow();
    }

    /**
     * Scaling suite verifying batch-to-queue-poll consolidation ratios across progressive scale points:
     * 10 batches, 25 batches, 50 batches, and 100 batches.
     *
     * Validates that as batch volume scales from 10 to 100 batches:
     * 1. Batches consolidate into bulk polls without fragmentation.
     * 2. Zero micro-batches or orphaned records occur.
     * 3. Batch-to-poll ratios achieve the desired 5:1 to 10:1 consolidation targets.
     * 4. Job lifecycle and acknowledgements complete cleanly.
     */
    @Test
    @DisplayName("Scaling: Progressive load from 10 to 100 batches maintains 5:1 to 10:1 consolidation ratio")
    void testProgressiveScalingBatchesToPollRatio() throws Exception {
        int[] batchCounts = {10, 25, 50, 100};
        int[] recordsPerBatchArr = {20, 20, 20, 25};
        int[] persistenceBatchSizes = {100, 100, 200, 250};
        int[] expectedPollCounts = {2, 5, 5, 10};

        System.out.println("================================================================================");
        System.out.println("            PROGRESSIVE SCALING: BATCH-TO-POLL RATIO BENCHMARK                  ");
        System.out.println("================================================================================");

        for (int step = 0; step < batchCounts.length; step++) {
            int numBatches = batchCounts[step];
            int recordsPerBatch = recordsPerBatchArr[step];
            int targetBatchSize = persistenceBatchSizes[step];
            int expectedPolls = expectedPollCounts[step];
            int totalRecords = numBatches * recordsPerBatch;

            EmbeddingQueue scaleQueue = new EmbeddingQueue(
                    new EmbeddingQueueConfig(totalRecords * 2, targetBatchSize, 10, 100, 1, 3, 50));
            String jobId = "job-prog-scale-" + numBatches;
            String repoId = "repo-prog-scale-" + numBatches;
            scaleQueue.registerJob(jobId, repoId);

            long startTime = System.currentTimeMillis();

            // Submit batches concurrently using 4 workers
            ExecutorService workers = Executors.newFixedThreadPool(4);
            List<Future<?>> futures = new ArrayList<>();
            for (int b = 1; b <= numBatches; b++) {
                final int batchIdx = b;
                futures.add(workers.submit(() -> {
                    List<EmbeddedRecord> batch = new ArrayList<>(recordsPerBatch);
                    for (int r = 1; r <= recordsPerBatch; r++) {
                        String id = EmbeddedRecord.generateDeterministicId(repoId, "b" + batchIdx + "-c" + r, "p");
                        batch.add(new EmbeddedRecord(
                                id, jobId, repoId, "p", Map.of(), new float[]{0.1f}, new CompletableFuture<>()));
                    }
                    try {
                        scaleQueue.submitAll(batch);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                }));
            }

            for (Future<?> f : futures) {
                f.get(5, TimeUnit.SECONDS);
            }
            scaleQueue.completeJob(jobId);

            // Drain the queue in bulk
            int pollCount = 0;
            int totalDrained = 0;
            List<Integer> drainedBatchSizes = new ArrayList<>();

            while (scaleQueue.size() > 0) {
                List<EmbeddedRecord> drained = scaleQueue.drainBatch(targetBatchSize, 50);
                if (!drained.isEmpty()) {
                    pollCount++;
                    totalDrained += drained.size();
                    drainedBatchSizes.add(drained.size());
                    for (EmbeddedRecord r : drained) {
                        r.acknowledgement().complete(null);
                        scaleQueue.recordPersisted(r);
                    }
                }
            }

            long duration = System.currentTimeMillis() - startTime;
            double ratio = (double) numBatches / pollCount;

            System.out.println(String.format("Scale Point %d: %3d Batches (%4d records) -> Drained in %2d Polls | Ratio: %5.2f:1 | Time: %3d ms",
                    step + 1, numBatches, totalRecords, pollCount, ratio, duration));
            System.out.println(String.format("   Poll Sizes: %s", drainedBatchSizes));

            assertEquals(totalRecords, totalDrained, "All records must be drained for scale point " + numBatches);
            assertEquals(expectedPolls, pollCount,
                    numBatches + " batches must consolidate into exactly " + expectedPolls + " polls of size " + targetBatchSize);
            assertEquals(0, scaleQueue.size());
            assertDoesNotThrow(() -> scaleQueue.awaitJobCompletion(jobId, Duration.ofSeconds(1)));

            workers.shutdownNow();
        }
        System.out.println("================================================================================");
    }
}

