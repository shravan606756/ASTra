package com.shravan.jcode_intelligence.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shravan.jcode_intelligence.config.EmbeddingQueueConfig;
import com.shravan.jcode_intelligence.model.EmbeddedRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.SQLException;
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

    @Test
    @DisplayName("Progressive k-way partitioning splits 400 -> 200 -> 67, 67, 66 on size failure")
    void testProgressiveKWayPartitioningSequence() {
        List<Integer> attemptedSizes = new ArrayList<>();

        // Fail for batch sizes > 150 with size-related error, succeed when <= 150
        doAnswer(invocation -> {
            BatchPreparedStatementSetter setter = invocation.getArgument(1);
            int batchSize = setter.getBatchSize();
            attemptedSizes.add(batchSize);
            if (batchSize > 150) {
                throw new RuntimeException("Payload too large: batch size " + batchSize + " exceeds statement limit");
            }
            return new int[batchSize];
        }).when(mockJdbcTemplate).batchUpdate(anyString(), any(BatchPreparedStatementSetter.class));

        EmbeddingQueueConfig kConfig = new EmbeddingQueueConfig(1000, 500, 10, 100, 1, 2, 10);
        EmbeddingQueue kQueue = new EmbeddingQueue(kConfig);
        PgVectorBulkWriter kWriter = new PgVectorBulkWriter(kQueue, mockJdbcTemplate, kConfig, new ObjectMapper());

        String jobId = "job-kway-seq";
        kQueue.registerJob(jobId, "repo-kway");

        List<EmbeddedRecord> batch = new ArrayList<>(400);
        for (int i = 1; i <= 400; i++) {
            String id = EmbeddedRecord.generateDeterministicId("repo-kway", "c-" + i, "c");
            batch.add(new EmbeddedRecord(id, jobId, "repo-kway", "c", Map.of("i", i), new float[]{0.1f}, new CompletableFuture<>()));
        }

        kWriter.persistWithAdaptiveSplitting(batch, 0);

        // Sequence of attempted batch sizes:
        // 400 -> 200 -> 67, 67, 66 -> 200 -> 67, 67, 66
        List<Integer> expectedSizes = List.of(400, 200, 67, 67, 66, 200, 67, 67, 66);
        assertEquals(expectedSizes, attemptedSizes, "Attempted batch sizes must match progressive k-way sequence");

        for (EmbeddedRecord r : batch) {
            assertTrue(r.acknowledgement().isDone(), "All 400 records must be successfully acknowledged");
            assertFalse(r.acknowledgement().isCompletedExceptionally(), "Acknowledgement must not be exceptional");
        }
    }

    @Test
    @DisplayName("Progressive k is clamped by floor(size / minBatchSize)")
    void testKClampingByMinBatchSize() {
        List<Integer> attemptedSizes = new ArrayList<>();

        // Fail for sizes > 20, with minBatchSize = 10
        doAnswer(invocation -> {
            BatchPreparedStatementSetter setter = invocation.getArgument(1);
            int batchSize = setter.getBatchSize();
            attemptedSizes.add(batchSize);
            if (batchSize > 20) {
                throw new RuntimeException("size limit exceeded: " + batchSize);
            }
            return new int[batchSize];
        }).when(mockJdbcTemplate).batchUpdate(anyString(), any(BatchPreparedStatementSetter.class));

        EmbeddingQueueConfig clampConfig = new EmbeddingQueueConfig(100, 50, 10, 100, 1, 2, 10);
        EmbeddingQueue clampQueue = new EmbeddingQueue(clampConfig);
        PgVectorBulkWriter clampWriter = new PgVectorBulkWriter(clampQueue, mockJdbcTemplate, clampConfig, new ObjectMapper());

        String jobId = "job-clamp";
        clampQueue.registerJob(jobId, "repo-clamp");

        // Batch of size 25: with k=5, floor(25 / 10) = 2, so effectiveK is clamped to 2 (splits 13, 12)
        List<EmbeddedRecord> batch = new ArrayList<>(25);
        for (int i = 1; i <= 25; i++) {
            String id = EmbeddedRecord.generateDeterministicId("repo-clamp", "c-" + i, "c");
            batch.add(new EmbeddedRecord(id, jobId, "repo-clamp", "c", Map.of(), new float[]{0.1f}, new CompletableFuture<>()));
        }

        clampWriter.persistWithAdaptiveSplitting(batch, 0, 5);

        assertEquals(List.of(25, 13, 12), attemptedSizes);
        for (EmbeddedRecord r : batch) {
            assertTrue(r.acknowledgement().isDone());
            assertFalse(r.acknowledgement().isCompletedExceptionally());
        }
    }

    @Test
    @DisplayName("Transient connection error with SQLSTATE 08006 triggers whole-batch retry without splitting")
    void testTransientConnectionErrorSqlState08006RetriesWithoutSplit() throws SQLException {
        AtomicInteger attempts = new AtomicInteger(0);

        doAnswer(invocation -> {
            int att = attempts.incrementAndGet();
            if (att < 3) {
                throw new RuntimeException(new SQLException("Connection terminated", "08006"));
            }
            BatchPreparedStatementSetter setter = invocation.getArgument(1);
            return new int[setter.getBatchSize()];
        }).when(mockJdbcTemplate).batchUpdate(anyString(), any(BatchPreparedStatementSetter.class));

        String jobId = "job-sqlstate-08";
        queue.registerJob(jobId, "repo-08");

        List<EmbeddedRecord> batch = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            String id = EmbeddedRecord.generateDeterministicId("repo-08", "c-" + i, "c");
            batch.add(new EmbeddedRecord(id, jobId, "repo-08", "c", Map.of(), new float[]{0.1f}, new CompletableFuture<>()));
        }

        writer.persistWithAdaptiveSplitting(batch, 0);

        assertEquals(3, attempts.get(), "Must retry whole batch 3 times without splitting");
        for (EmbeddedRecord r : batch) {
            assertTrue(r.acknowledgement().isDone());
            assertFalse(r.acknowledgement().isCompletedExceptionally());
        }
    }

    @Test
    @DisplayName("Size error with SQLSTATE 54001 triggers progressive k-way splitting")
    void testSizeErrorSqlState54001TriggersSplit() throws SQLException {
        AtomicInteger writeCallCount = new AtomicInteger(0);

        doAnswer(invocation -> {
            BatchPreparedStatementSetter setter = invocation.getArgument(1);
            int batchSize = setter.getBatchSize();
            writeCallCount.incrementAndGet();
            if (batchSize > 5) {
                throw new RuntimeException(new SQLException("statement too complex", "54001"));
            }
            return new int[batchSize];
        }).when(mockJdbcTemplate).batchUpdate(anyString(), any(BatchPreparedStatementSetter.class));

        String jobId = "job-sqlstate-54";
        queue.registerJob(jobId, "repo-54");

        List<EmbeddedRecord> batch = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            String id = EmbeddedRecord.generateDeterministicId("repo-54", "c-" + i, "c");
            batch.add(new EmbeddedRecord(id, jobId, "repo-54", "c", Map.of(), new float[]{0.1f}, new CompletableFuture<>()));
        }

        writer.persistWithAdaptiveSplitting(batch, 0);

        assertEquals(3, writeCallCount.get(), "Must split on SQLSTATE 54001 into 2 sub-batches (10 -> 5 and 5)");
        for (EmbeddedRecord r : batch) {
            assertTrue(r.acknowledgement().isDone());
            assertFalse(r.acknowledgement().isCompletedExceptionally());
        }
    }

    @Test
    @DisplayName("Live scaling test: dynamic mode executes fewer JDBC calls than legacy mode under identical workload")
    void testDynamicModeExecutesFewerJdbcCallsThanLegacy() throws Exception {
        int totalBatches = 20;
        int recordsPerBatch = 20; // 400 records total
        int maxPersistenceBatchSize = 500;

        // Run 1: Legacy Mode (dynamic batching disabled, short flush interval 50ms to simulate micro-flushes)
        AtomicInteger legacySqlCalls = new AtomicInteger(0);
        JdbcTemplate legacyJdbc = mock(JdbcTemplate.class);
        doAnswer(invocation -> {
            BatchPreparedStatementSetter setter = invocation.getArgument(1);
            legacySqlCalls.incrementAndGet();
            return new int[setter.getBatchSize()];
        }).when(legacyJdbc).batchUpdate(anyString(), any(BatchPreparedStatementSetter.class));

        EmbeddingQueueConfig legacyConfig = new EmbeddingQueueConfig(
                1000, maxPersistenceBatchSize, 10, 30, 1, 3, 50, false, 400, 1000, 3500, 10000);
        EmbeddingQueue legacyQueue = new EmbeddingQueue(legacyConfig);
        PgVectorBulkWriter legacyWriter = new PgVectorBulkWriter(legacyQueue, legacyJdbc, legacyConfig, new ObjectMapper());
        legacyWriter.start();

        String legacyJobId = "job-compare-legacy";
        legacyQueue.registerJob(legacyJobId, "repo-compare");

        for (int b = 1; b <= totalBatches; b++) {
            List<EmbeddedRecord> batch = new ArrayList<>(recordsPerBatch);
            for (int r = 1; r <= recordsPerBatch; r++) {
                String id = EmbeddedRecord.generateDeterministicId("repo-compare", "leg-b" + b + "-r" + r, "txt");
                batch.add(new EmbeddedRecord(id, legacyJobId, "repo-compare", "txt", Map.of(), new float[]{0.1f}, new CompletableFuture<>()));
            }
            legacyQueue.submitAll(batch);
            Thread.sleep(40); // small delay to let legacy 30ms flush timer expire between submissions
        }
        legacyQueue.completeJob(legacyJobId);
        legacyQueue.awaitJobCompletion(legacyJobId, Duration.ofSeconds(5));
        legacyWriter.stop();

        // Run 2: Dynamic Mode (enabled with target 400 and quiet window)
        AtomicInteger dynamicSqlCalls = new AtomicInteger(0);
        JdbcTemplate dynamicJdbc = mock(JdbcTemplate.class);
        List<EmbeddedRecord> dynamicSubmitted = new ArrayList<>();
        doAnswer(invocation -> {
            BatchPreparedStatementSetter setter = invocation.getArgument(1);
            dynamicSqlCalls.incrementAndGet();
            return new int[setter.getBatchSize()];
        }).when(dynamicJdbc).batchUpdate(anyString(), any(BatchPreparedStatementSetter.class));

        EmbeddingQueueConfig dynamicConfig = new EmbeddingQueueConfig(
                1000, maxPersistenceBatchSize, 10, 3000, 1, 3, 50, true, 400, 1000, 3500, 10000);
        EmbeddingQueue dynamicQueue = new EmbeddingQueue(dynamicConfig);
        PgVectorBulkWriter dynamicWriter = new PgVectorBulkWriter(dynamicQueue, dynamicJdbc, dynamicConfig, new ObjectMapper());
        dynamicWriter.start();

        String dynamicJobId = "job-compare-dynamic";
        dynamicQueue.registerJob(dynamicJobId, "repo-compare", 4, 100, 400);

        for (int b = 1; b <= totalBatches; b++) {
            List<EmbeddedRecord> batch = new ArrayList<>(recordsPerBatch);
            for (int r = 1; r <= recordsPerBatch; r++) {
                String id = EmbeddedRecord.generateDeterministicId("repo-compare", "dyn-b" + b + "-r" + r, "txt");
                batch.add(new EmbeddedRecord(id, dynamicJobId, "repo-compare", "txt", Map.of(), new float[]{0.1f}, new CompletableFuture<>()));
            }
            dynamicSubmitted.addAll(batch);
            dynamicQueue.submitAll(batch);
            Thread.sleep(20);
        }
        dynamicQueue.completeJob(dynamicJobId);
        dynamicQueue.awaitJobCompletion(dynamicJobId, Duration.ofSeconds(5));
        dynamicWriter.stop();

        // Assertions
        assertTrue(dynamicSqlCalls.get() < legacySqlCalls.get(),
                String.format("Dynamic mode calls (%d) must be fewer than legacy mode calls (%d)",
                        dynamicSqlCalls.get(), legacySqlCalls.get()));

        for (EmbeddedRecord r : dynamicSubmitted) {
            assertTrue(r.acknowledgement().isDone(), "All dynamic mode records must be acknowledged");
            assertFalse(r.acknowledgement().isCompletedExceptionally());
        }
    }
}

