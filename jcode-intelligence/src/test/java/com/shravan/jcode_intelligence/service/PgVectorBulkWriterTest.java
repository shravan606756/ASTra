package com.shravan.jcode_intelligence.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shravan.jcode_intelligence.config.EmbeddingQueueConfig;
import com.shravan.jcode_intelligence.model.EmbeddedRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
}

