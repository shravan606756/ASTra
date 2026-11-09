package com.shravan.jcode_intelligence.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shravan.jcode_intelligence.config.EmbeddingQueueConfig;
import com.shravan.jcode_intelligence.model.EmbeddedRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests verifying vector formatting, deterministic ID stability, idempotent upserts,
 * and adaptive batch splitting in {@link PgVectorBulkWriter}.
 */
class PgVectorBulkWriterTest {

    private EmbeddingQueue queue;
    private JdbcTemplate mockJdbcTemplate;
    private EmbeddingQueueConfig config;
    private PgVectorBulkWriter writer;

    @BeforeEach
    void setUp() {
        config = new EmbeddingQueueConfig(100, 20, 5, 100, 1, 2, 10);
        queue = new EmbeddingQueue(config);
        mockJdbcTemplate = mock(JdbcTemplate.class);
        writer = new PgVectorBulkWriter(queue, mockJdbcTemplate, config, new ObjectMapper());
    }

    @Test
    @DisplayName("Deterministic ID generation produces stable RFC-4122 type 3 UUIDs across repeated calls")
    void testDeterministicIdStability() {
        String repoId = "ASTra-Repo";
        String chunkId = "METHOD_CHUNK_42";
        String content = "public void execute() {}";

        String id1 = EmbeddedRecord.generateDeterministicId(repoId, chunkId, content);
        String id2 = EmbeddedRecord.generateDeterministicId(repoId, chunkId, content);

        assertEquals(id1, id2, "Identical coordinates and chunkId must yield identical UUIDs");

        // Verify valid UUID string syntax
        assertDoesNotThrow(() -> UUID.fromString(id1), "Generated ID must be a valid RFC-4122 UUID");

        // Distinct chunkId must yield distinct UUID
        String id3 = EmbeddedRecord.generateDeterministicId(repoId, "METHOD_CHUNK_99", content);
        assertNotEquals(id1, id3, "Different chunkId must yield distinct UUID");
    }

    @Test
    @DisplayName("formatVector serializes float array into PostgreSQL pgvector literal format")
    void testFormatVector() {
        float[] vector = new float[]{0.15f, -0.42f, 1.0f};
        String formatted = PgVectorBulkWriter.formatVector(vector);

        assertEquals("[0.15,-0.42,1.0]", formatted);
        assertEquals("[]", PgVectorBulkWriter.formatVector(null));
        assertEquals("[]", PgVectorBulkWriter.formatVector(new float[]{}));
    }

    @Test
    @DisplayName("Adaptive batch splitting splits batch exceeding threshold into smaller sub-batches")
    void testAdaptiveBatchSplitting() {
        AtomicInteger writeCallCount = new AtomicInteger(0);

        // Mock JdbcTemplate to fail when batch size is greater than 5, but succeed when <= 5
        doAnswer(invocation -> {
            BatchPreparedStatementSetter setter = invocation.getArgument(1);
            int batchSize = setter.getBatchSize();
            writeCallCount.incrementAndGet();
            if (batchSize > 5) {
                throw new RuntimeException("Payload too large: batch size " + batchSize + " exceeds threshold");
            }
            return new int[batchSize];
        }).when(mockJdbcTemplate).batchUpdate(anyString(), any(BatchPreparedStatementSetter.class));

        String jobId = "job-split-test";
        queue.registerJob(jobId, "repo-split");

        // Create a batch of 10 items
        List<EmbeddedRecord> batch = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            String id = EmbeddedRecord.generateDeterministicId("repo-split", "chunk-" + i, "c");
            batch.add(new EmbeddedRecord(id, jobId, "repo-split", "c", Map.of("i", i), new float[]{0.1f}, new CompletableFuture<>()));
        }

        // Execute bulk persistence with adaptive splitting
        writer.persistWithAdaptiveSplitting(batch, 0);

        // Should fail once at size 10, then split into two batches of size 5 and both succeed (total 3 JDBC calls)
        assertEquals(3, writeCallCount.get(), "Must execute 1 failed batch (10) and 2 successful sub-batches (5 and 5)");

        for (EmbeddedRecord r : batch) {
            assertTrue(r.acknowledgement().isDone(), "All records in split sub-batches must be acknowledged");
            assertFalse(r.acknowledgement().isCompletedExceptionally(), "Records must complete successfully");
        }
    }

    @Test
    @DisplayName("Exhausted retries below minBatchSize report failure to the embedding queue")
    void testExhaustedRetriesReportsFailure() {
        // JdbcTemplate always fails
        doThrow(new RuntimeException("Database down"))
                .when(mockJdbcTemplate).batchUpdate(anyString(), any(BatchPreparedStatementSetter.class));

        String jobId = "job-fail-batch";
        queue.registerJob(jobId, "repo-fail");

        // Batch of size 3 (already below minBatchSize of 5)
        List<EmbeddedRecord> batch = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            String id = EmbeddedRecord.generateDeterministicId("repo-fail", "chunk-" + i, "c");
            batch.add(new EmbeddedRecord(id, jobId, "repo-fail", "c", Map.of(), new float[]{0.1f}, new CompletableFuture<>()));
        }

        writer.persistWithAdaptiveSplitting(batch, 0);

        for (EmbeddedRecord r : batch) {
            assertTrue(r.acknowledgement().isDone());
            assertTrue(r.acknowledgement().isCompletedExceptionally(), "Records must fail after retries are exhausted");
        }
    }

    @Test
    @DisplayName("Non-size failure retries directly without splitting the batch")
    void testNonSizeFailureDoesNotSplitBatch() {
        AtomicInteger writeCallCount = new AtomicInteger(0);

        // Always throw a non-size exception (e.g. syntax error)
        doAnswer(invocation -> {
            writeCallCount.incrementAndGet();
            throw new RuntimeException("syntax error at or near 'SELECT'");
        }).when(mockJdbcTemplate).batchUpdate(anyString(), any(BatchPreparedStatementSetter.class));

        String jobId = "job-non-size-fail";
        queue.registerJob(jobId, "repo-non-size");

        // Batch of 10 items (size >= 2 * minBatchSize 5)
        List<EmbeddedRecord> batch = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            String id = EmbeddedRecord.generateDeterministicId("repo-non-size", "chunk-" + i, "c");
            batch.add(new EmbeddedRecord(id, jobId, "repo-non-size", "c", Map.of("i", i), new float[]{0.1f}, new CompletableFuture<>()));
        }

        // maxRetries is 2, so attempt 0, attempt 1, attempt 2 (3 calls) + 10 single-record fallbacks = 13 calls
        writer.persistWithAdaptiveSplitting(batch, 0);

        assertEquals(13, writeCallCount.get(), "Must retry without splitting into sub-batches of 5 and 5");

        for (EmbeddedRecord r : batch) {
            assertTrue(r.acknowledgement().isDone());
            assertTrue(r.acknowledgement().isCompletedExceptionally());
        }
    }

    @Test
    @DisplayName("Scaling: 20 chunk batches (500 records) persist in exactly 1 PGVector bulk JDBC call (20:1 ratio)")
    void testScalingPgVectorPersistenceBatchesToSqlCallRatio() throws Exception {
        AtomicInteger sqlExecutionCount = new AtomicInteger(0);
        AtomicInteger totalRecordsPersistedInSql = new AtomicInteger(0);

        doAnswer(invocation -> {
            BatchPreparedStatementSetter setter = invocation.getArgument(1);
            int batchSize = setter.getBatchSize();
            sqlExecutionCount.incrementAndGet();
            totalRecordsPersistedInSql.addAndGet(batchSize);
            return new int[batchSize];
        }).when(mockJdbcTemplate).batchUpdate(anyString(), any(BatchPreparedStatementSetter.class));

        int producerBatchCount = 20;
        int recordsPerBatch = 25; // 500 records total
        int persistenceBatchSize = 500;

        EmbeddingQueueConfig scaleConfig = new EmbeddingQueueConfig(1000, persistenceBatchSize, 10, 500, 1, 3, 50);
        EmbeddingQueue scaleQueue = new EmbeddingQueue(scaleConfig);
        PgVectorBulkWriter scaleWriter = new PgVectorBulkWriter(scaleQueue, mockJdbcTemplate, scaleConfig, new ObjectMapper());

        String jobId = "job-e2e-scale-ratio";
        scaleQueue.registerJob(jobId, "repo-e2e-scale");

        // Submit 20 separate chunk batches to simulate parallel indexing workers
        for (int b = 1; b <= producerBatchCount; b++) {
            List<EmbeddedRecord> batch = new ArrayList<>(recordsPerBatch);
            for (int r = 1; r <= recordsPerBatch; r++) {
                String id = EmbeddedRecord.generateDeterministicId("repo-e2e-scale", "b" + b + "-c" + r, "body");
                batch.add(new EmbeddedRecord(
                        id, jobId, "repo-e2e-scale", "body", Map.of(), new float[]{0.1f}, new CompletableFuture<>()));
            }
            scaleQueue.submitAll(batch);
        }
        scaleQueue.completeJob(jobId);

        // Run persistence writer on the queue
        List<EmbeddedRecord> drained = scaleQueue.drainBatch(persistenceBatchSize, 100);
        scaleWriter.persistWithAdaptiveSplitting(drained, 0);

        // Assert: 20 producer batches were persisted in exactly 1 single JDBC execution of 500 records!
        assertEquals(500, totalRecordsPersistedInSql.get(), "All 500 records must be persisted to PGVector");
        assertEquals(1, sqlExecutionCount.get(),
                "20 chunk batches must consolidate into exactly 1 PGVector database call (20:1 ratio)");
        assertEquals(0, scaleQueue.size(), "Queue must be completely drained");
        assertDoesNotThrow(() -> scaleQueue.awaitJobCompletion(jobId, Duration.ofSeconds(1)));
    }

    /**
     * Scaling benchmark verifying pipeline robustness and consolidation ratio under 100 producer batches.
     * Simulates parallel indexing workers producing 100 batches of 25 records (2,500 total records)
     * against a bounded queue with an active background {@link PgVectorBulkWriter} daemon thread.
     *
     * Validates:
     * 1. High-load concurrency without deadlock, starvation, or dropped records.
     * 2. Zero micro-batches (all database writes are bulk batches >= minBatchSize).
     * 3. Batches-to-database write consolidation ratio (100 producer batches -> 10 database writes = 10:1 ratio).
     * 4. 100% end-to-end acknowledgement completion across all 2,500 records.
     * 5. Queue cleanly drains to zero records and job state completes.
     */
    @Test
    @DisplayName("Scaling: Live pipeline with 100 producer batches (2,500 records) consolidates to 10 DB writes (10:1 ratio)")
    void testLivePipelineScalingTo100Batches() throws Exception {
        AtomicInteger sqlExecutionCount = new AtomicInteger(0);
        AtomicInteger totalRecordsPersistedInSql = new AtomicInteger(0);
        List<Integer> perCallBatchSizes = new CopyOnWriteArrayList<>();
        List<Long> perCallDurationsMs = new CopyOnWriteArrayList<>();

        doAnswer(invocation -> {
            long start = System.currentTimeMillis();
            BatchPreparedStatementSetter setter = invocation.getArgument(1);
            int batchSize = setter.getBatchSize();
            // Simulate realistic PostgreSQL network and disk I/O latency (10ms per batch write)
            Thread.sleep(10);
            long duration = System.currentTimeMillis() - start;

            sqlExecutionCount.incrementAndGet();
            totalRecordsPersistedInSql.addAndGet(batchSize);
            perCallBatchSizes.add(batchSize);
            perCallDurationsMs.add(duration);
            return new int[batchSize];
        }).when(mockJdbcTemplate).batchUpdate(anyString(), any(BatchPreparedStatementSetter.class));

        int totalBatches = 100;
        int recordsPerBatch = 25; // 100 * 25 = 2,500 records total
        int persistenceBatchSize = 250;
        int queueCapacity = 500; // Constrained capacity < 2,500 to exercise backpressure

        EmbeddingQueueConfig scaleConfig = new EmbeddingQueueConfig(
                queueCapacity, persistenceBatchSize, 10, 200, 1, 3, 50);
        EmbeddingQueue scaleQueue = new EmbeddingQueue(scaleConfig);
        PgVectorBulkWriter scaleWriter = new PgVectorBulkWriter(scaleQueue, mockJdbcTemplate, scaleConfig, new ObjectMapper());

        String jobId = "job-100-batch-scale";
        String repoId = "repo-100-batch-scale";
        scaleQueue.registerJob(jobId, repoId);

        // Start the background writer daemon
        scaleWriter.start();

        long pipelineStartTime = System.currentTimeMillis();
        ExecutorService producerPool = Executors.newFixedThreadPool(4);
        List<Future<?>> producerFutures = new ArrayList<>();
        List<EmbeddedRecord> allSubmittedRecords = new CopyOnWriteArrayList<>();

        // Submit 100 batches across 4 concurrent worker threads
        for (int b = 1; b <= totalBatches; b++) {
            final int batchIndex = b;
            producerFutures.add(producerPool.submit(() -> {
                List<EmbeddedRecord> batch = new ArrayList<>(recordsPerBatch);
                for (int r = 1; r <= recordsPerBatch; r++) {
                    String chunkId = "chunk-b" + batchIndex + "-r" + r;
                    String id = EmbeddedRecord.generateDeterministicId(repoId, chunkId, "body-" + batchIndex + "-" + r);
                    EmbeddedRecord record = new EmbeddedRecord(
                            id, jobId, repoId, "body-" + batchIndex + "-" + r,
                            Map.of("batch", batchIndex, "chunk", r),
                            new float[]{0.05f * (r % 10)},
                            new CompletableFuture<>());
                    batch.add(record);
                }
                allSubmittedRecords.addAll(batch);
                try {
                    scaleQueue.submitAll(batch);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            }));
        }

        // Wait for all 100 producer batches to finish submitting
        for (Future<?> f : producerFutures) {
            f.get(15, TimeUnit.SECONDS);
        }

        // Signal that all submissions are complete
        scaleQueue.completeJob(jobId);

        // Await full pipeline persistence and completion
        assertDoesNotThrow(() -> scaleQueue.awaitJobCompletion(jobId, Duration.ofSeconds(15)));

        long pipelineDurationMs = System.currentTimeMillis() - pipelineStartTime;

        // Stop background writer
        scaleWriter.stop();
        producerPool.shutdownNow();

        // Output actual pipeline observation results
        System.out.println("================================================================================");
        System.out.println("            SCALING BENCHMARK: 100 BATCHES REAL PIPELINE EXECUTION              ");
        System.out.println("================================================================================");
        System.out.println(String.format("Total Producer Batches Submitted : %d batches", totalBatches));
        System.out.println(String.format("Total Embedded Records Processed: %d records", totalRecordsPersistedInSql.get()));
        System.out.println(String.format("Target Bulk Persistence Size    : %d records/DB write", persistenceBatchSize));
        System.out.println(String.format("Queue Capacity (Backpressure)   : %d records", queueCapacity));
        System.out.println(String.format("Actual PGVector DB Calls Made   : %d calls", sqlExecutionCount.get()));
        double batchToDbRatio = (double) totalBatches / sqlExecutionCount.get();
        System.out.println(String.format("Batches-to-DB-Write Ratio       : %.2f : 1 (%.1f%% reduction in DB calls)",
                batchToDbRatio, (1.0 - (double) sqlExecutionCount.get() / totalBatches) * 100.0));
        System.out.println(String.format("Total Pipeline Duration         : %d ms", pipelineDurationMs));
        double throughput = (totalRecordsPersistedInSql.get() * 1000.0) / pipelineDurationMs;
        System.out.println(String.format("Pipeline End-to-End Throughput  : %.1f records/sec", throughput));
        System.out.println("Per-Database-Call Batch Breakdown:");
        for (int i = 0; i < perCallBatchSizes.size(); i++) {
            System.out.println(String.format("  [DB Call %2d] Persisted %3d records (simulated I/O: %d ms)",
                    i + 1, perCallBatchSizes.get(i), perCallDurationsMs.get(i)));
        }
        System.out.println("================================================================================");

        // Robustness & Correctness Assertions
        assertEquals(2500, totalRecordsPersistedInSql.get(), "All 2,500 records must be persisted into PGVector");
        assertTrue(sqlExecutionCount.get() <= 12,
                "100 producer batches of 25 must consolidate into ~10 DB calls (found " + sqlExecutionCount.get() + ")");
        assertTrue(batchToDbRatio >= 8.0,
                "Batches-to-DB ratio must achieve at least 8:1 (actual: " + batchToDbRatio + ")");
        assertEquals(0, scaleQueue.size(), "Queue must be empty after full job completion");

        // Verify zero micro-batches occurred
        for (int batchSize : perCallBatchSizes) {
            assertTrue(batchSize >= scaleConfig.getMinBatchSize(),
                    "Zero micro-batches allowed: batch size " + batchSize + " must be >= minBatchSize 10");
        }

        // Verify all 2,500 individual record futures completed successfully
        for (EmbeddedRecord r : allSubmittedRecords) {
            assertTrue(r.acknowledgement().isDone(), "Acknowledgement must be complete");
            assertFalse(r.acknowledgement().isCompletedExceptionally(), "Acknowledgement must not be exceptional");
        }
    }

    /**
     * Verifies that when embedding tasks finish and completeJob is signaled, a subsequent persistence
     * failure in PgVectorBulkWriter fails awaitJobCompletion and marks all record futures exceptionally.
     */
    @Test
    @DisplayName("Persistence failure occurring after embedding tasks finish fails awaitJobCompletion")
    void testPersistenceFailureAfterEmbeddingTasksFinish() throws Exception {
        String jobId = "job-post-embedding-failure";
        String repoId = "repo-post-embedding";
        queue.registerJob(jobId, repoId);

        // Simulate embedding tasks completing and submitting 5 records
        List<EmbeddedRecord> batch = new ArrayList<>(5);
        for (int i = 1; i <= 5; i++) {
            String chunkId = "chunk-post-" + i;
            String id = EmbeddedRecord.generateDeterministicId(repoId, chunkId, "data-" + i);
            batch.add(new EmbeddedRecord(
                    id, jobId, repoId, "data-" + i, Map.of(), new float[]{0.1f}, new CompletableFuture<>()));
        }
        queue.submitAll(batch);

        // Embedding tasks are 100% finished, so producer signals completeJob
        queue.completeJob(jobId);

        // Configure mockJdbcTemplate to fail all batch updates with database error
        doThrow(new RuntimeException("PostgreSQL connection lost during write"))
                .when(mockJdbcTemplate).batchUpdate(anyString(), any(BatchPreparedStatementSetter.class));

        // Writer attempts to drain and persist the batch
        List<EmbeddedRecord> drained = queue.drainBatch(20, 50);
        assertEquals(5, drained.size(), "Drained batch must contain all 5 staged records");
        writer.persistWithAdaptiveSplitting(drained, 0);

        // Awaiting job completion must throw Exception reflecting the persistence failure
        Exception ex = assertThrows(Exception.class, () ->
                queue.awaitJobCompletion(jobId, Duration.ofSeconds(2)));
        assertTrue(ex.getMessage().contains("PostgreSQL connection lost")
                || (ex.getCause() != null && ex.getCause().getMessage().contains("PostgreSQL connection lost")));

        // Verify all 5 record futures were completed exceptionally
        for (EmbeddedRecord r : batch) {
            assertTrue(r.acknowledgement().isDone(), "Record acknowledgement must be done");
            assertTrue(r.acknowledgement().isCompletedExceptionally(), "Record acknowledgement must be exceptional");
        }
    }
}

