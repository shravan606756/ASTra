package com.shravan.jcode_intelligence.service;

import com.shravan.jcode_intelligence.config.EmbeddingQueueConfig;
import com.shravan.jcode_intelligence.model.EmbeddedRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
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
            executor.submit(() -> {
                try {
                    String id11 = EmbeddedRecord.generateDeterministicId("repo-bp", "c-11", "c");
                    smallQueue.submit(new EmbeddedRecord(id11, jobId, "repo-bp", "c", Map.of(), new float[]{0.1f}, new CompletableFuture<>()));
                    submitted11th.incrementAndGet();
                } catch (InterruptedException ignored) {}
            });

            // Producer must be blocked
            Thread.sleep(100);
            assertEquals(0, submitted11th.get(), "11th record must be blocked by backpressure");

            // Drain 1 record to unblock
            List<EmbeddedRecord> drained = smallQueue.drainBatch(1, 50);
            assertEquals(1, drained.size());

            // Producer should now proceed
            Thread.sleep(100);
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
}

