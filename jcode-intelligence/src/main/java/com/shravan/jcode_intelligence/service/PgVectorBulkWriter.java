package com.shravan.jcode_intelligence.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shravan.jcode_intelligence.config.EmbeddingQueueConfig;
import com.shravan.jcode_intelligence.model.EmbeddedRecord;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Background persistence writer that drains embedded records from {@link EmbeddingQueue}
 * and executes bounded, idempotent bulk upserts into PostgreSQL's {@code vector_store}.
 *
 * <p>Employs deterministic RFC-4122 record IDs and {@code ON CONFLICT (id)} updates to guarantee
 * write idempotency during network retries, and uses adaptive batch splitting down to
 * {@code minBatchSize} when oversized payloads fail.
 */
@Component
public class PgVectorBulkWriter implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(PgVectorBulkWriter.class);

    private static final String BULK_UPSERT_SQL = """
            INSERT INTO vector_store (id, content, metadata, embedding)
            VALUES (?::uuid, ?, ?::json, ?::vector)
            ON CONFLICT (id) DO UPDATE SET
                content = EXCLUDED.content,
                metadata = EXCLUDED.metadata,
                embedding = EXCLUDED.embedding
            """;

    private final EmbeddingQueue queue;
    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingQueueConfig config;
    private final ObjectMapper objectMapper;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService writerExecutor;

    /**
     * Constructs a {@code PgVectorBulkWriter}.
     *
     * @param queue        shared staging embedding queue
     * @param jdbcTemplate Spring JDBC template
     * @param config       embedding queue configuration
     * @param objectMapper Jackson object mapper for metadata serialization
     */
    public PgVectorBulkWriter(EmbeddingQueue queue,
                              JdbcTemplate jdbcTemplate,
                              EmbeddingQueueConfig config,
                              ObjectMapper objectMapper) {
        this.queue = queue;
        this.jdbcTemplate = jdbcTemplate;
        this.config = config != null ? config : new EmbeddingQueueConfig();
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    /** Starts the configured daemon workers if the writer is not already running. */
    @Override
    @PostConstruct
    public void start() {
        if (running.compareAndSet(false, true)) {
            int workers = Math.max(1, config.getWriterCount());
            writerExecutor = Executors.newFixedThreadPool(workers, r -> {
                Thread t = new Thread(r, "astra-vector-writer");
                t.setDaemon(true);
                return t;
            });

            log.info("Starting {} PGVector bulk persistence writer worker(s) (batch size: {}, flush timeout: {} ms)",
                    workers, config.getBatchSize(), config.getFlushIntervalMs());

            for (int i = 0; i < workers; i++) {
                writerExecutor.submit(this::writerLoop);
            }
        }
    }

    /**
     * Requests the workers to stop, allowing up to three seconds for them to drain the queue before
     * requesting interruption. An interrupted caller retains its interrupt status.
     */
    @Override
    @PreDestroy
    public void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("Stopping PGVector bulk persistence writers, draining remaining queue items...");
            if (writerExecutor != null) {
                writerExecutor.shutdown();
                try {
                    if (!writerExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                        writerExecutor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    writerExecutor.shutdownNow();
                }
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    private void writerLoop() {
        while (running.get() || queue.size() > 0) {
            try {
                List<EmbeddedRecord> batch = queue.drainBatch(config.getBatchSize(), config.getFlushIntervalMs());
                if (batch != null && !batch.isEmpty()) {
                    persistWithAdaptiveSplitting(batch, 0);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Unexpected error in PGVector persistence writer loop", e);
            }
        }
    }

    /**
     * Persists a batch and acknowledges its records. On failure, batches larger than the
     * configured split threshold are halved; batches at or below it are retried with linearly
     * increasing delays. Exhausted retries or an interrupted delay report the write
     * failure to the queue. A null or empty batch is ignored.
     *
     * @param batch   list of records to persist
     * @param attempt current retry count; normally zero for a new batch
     */
    public void persistWithAdaptiveSplitting(List<EmbeddedRecord> batch, int attempt) {
        if (batch == null || batch.isEmpty()) {
            return;
        }

        try {
            long persistStart = System.currentTimeMillis();
            executeBulkUpsert(batch);
            long persistDuration = System.currentTimeMillis() - persistStart;
            log.info("[PGVECTOR BULK PERSISTENCE] Persisted bulk batch of {} vector record(s) to PostgreSQL vector_store in {} ms",
                    batch.size(), persistDuration);
            for (EmbeddedRecord record : batch) {
                queue.recordPersisted(record);
            }
        } catch (Exception ex) {
            log.warn("Bulk insert of {} records failed: {}", batch.size(), ex.getMessage());

            // 1. If batch is larger than minimum batch size, split in half
            if (batch.size() > config.getMinBatchSize()) {
                int mid = batch.size() / 2;
                log.info("Splitting failing batch of {} records into sub-batches of {} and {}",
                        batch.size(), mid, batch.size() - mid);

                persistWithAdaptiveSplitting(batch.subList(0, mid), 0);
                persistWithAdaptiveSplitting(batch.subList(mid, batch.size()), 0);
                return;
            }

            // 2. If at or below minBatchSize, retry with exponential backoff up to maxRetries
            if (attempt < config.getMaxRetries()) {
                long backoff = config.getRetryBackoffMs() * (attempt + 1);
                log.info("Retrying failed batch of {} records (attempt {}/{}) after {} ms backoff",
                        batch.size(), attempt + 1, config.getMaxRetries(), backoff);
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    failBatch(batch, ex);
                    return;
                }
                persistWithAdaptiveSplitting(batch, attempt + 1);
            } else {
                // 3. Exhausted retries and splitting; fail records
                log.error("Exhausted all retries and adaptive splitting for batch of {} records; reporting failure", batch.size(), ex);
                failBatch(batch, ex);
            }
        }
    }

    private void executeBulkUpsert(List<EmbeddedRecord> batch) {
        jdbcTemplate.batchUpdate(BULK_UPSERT_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                EmbeddedRecord record = batch.get(i);
                ps.setString(1, record.id());
                ps.setString(2, record.content());
                try {
                    ps.setString(3, objectMapper.writeValueAsString(record.metadata()));
                } catch (JsonProcessingException e) {
                    throw new SQLException("Failed to serialize metadata to JSON for record: " + record.id(), e);
                }
                ps.setString(4, formatVector(record.embedding()));
            }

            @Override
            public int getBatchSize() {
                return batch.size();
            }
        });
    }

    private void failBatch(List<EmbeddedRecord> batch, Throwable cause) {
        for (EmbeddedRecord record : batch) {
            queue.recordFailed(record, cause);
        }
    }

    /**
     * Formats a floating-point array into PostgreSQL's {@code vector} literal syntax: {@code [0.1,0.2,...]}.
     *
     * @param vector array of float values
     * @return vector string representation, or {@code []} for a null or empty array
     */
    public static String formatVector(float[] vector) {
        if (vector == null || vector.length == 0) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder(vector.length * 8 + 2);
        sb.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        sb.append(']');
        return sb.toString();
    }
}

