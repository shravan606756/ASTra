package com.shravan.jcode_intelligence.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for the bounded shared embedding queue and bulk PGVector persistence writer.
 *
 * <p>Calibrates queue capacity, bulk write batch sizing, adaptive batch splitting boundaries,
 * flush timeouts, dynamic batching quiet windows, and persistence worker concurrency to prevent database overload and deadlocks.
 */
@Configuration
public class EmbeddingQueueConfig {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingQueueConfig.class);

    /** Maximum capacity of the bounded embedding queue measured in records. */
    @Value("${astra.indexing.embedding-queue.capacity:2000}")
    private int capacity = 2000;

    /** Target batch size for bulk writing embedded records to PGVector. */
    @Value("${astra.indexing.persistence.batch-size:500}")
    private int batchSize = 500;

    /** Minimum batch size allowed during adaptive batch splitting before failing. */
    @Value("${astra.indexing.persistence.min-batch-size:10}")
    private int minBatchSize = 10;

    /** Maximum time in milliseconds to wait before flushing a partial batch under low throughput. */
    @Value("${astra.indexing.persistence.flush-interval-ms:3000}")
    private long flushIntervalMs = 3000;

    /** Number of concurrent persistence writers draining the embedding queue. */
    @Value("${astra.indexing.persistence.writer-count:1}")
    private int writerCount = 1;

    /** Maximum retry attempts for transient persistence errors before reporting failure. */
    @Value("${astra.indexing.persistence.max-retries:3}")
    private int maxRetries = 3;

    /** Base backoff in milliseconds between persistence retry attempts. */
    @Value("${astra.indexing.persistence.retry-backoff-ms:100}")
    private long retryBackoffMs = 100;

    /** Target table name for PGVector persistence. */
    @Value("${spring.ai.vectorstore.pgvector.table-name:vector_store}")
    private String tableName = "vector_store";

    /** Target schema name for PGVector persistence. */
    @Value("${spring.ai.vectorstore.pgvector.schema-name:public}")
    private String schemaName = "public";

    /** Whether dynamic wave batch accumulation is enabled. */
    @Value("${astra.indexing.persistence.dynamic-batching.enabled:true}")
    private boolean dynamicBatchingEnabled = true;

    /** Fallback target batch size when job hints are unavailable. */
    @Value("${astra.indexing.persistence.dynamic-batching.adaptive-target-size:400}")
    private int adaptiveTargetSize = 400;

    /** Minimum quiet window in milliseconds before partial wave flush. */
    @Value("${astra.indexing.persistence.dynamic-batching.quiet-window-min-ms:3500}")
    private long quietWindowMinMs = 3500;

    /** Maximum quiet window in milliseconds before partial wave flush. */
    @Value("${astra.indexing.persistence.dynamic-batching.quiet-window-max-ms:5000}")
    private long quietWindowMaxMs = 5000;

    /** Maximum waiting time in milliseconds for the oldest buffered record before mandatory flush. */
    @Value("${astra.indexing.persistence.dynamic-batching.max-wait-ms:10000}")
    private long maxWaitMs = 10000;

    /** Default constructor. */
    public EmbeddingQueueConfig() {
    }

    /**
     * Validates configuration parameters at application startup.
     *
     * @throws IllegalArgumentException if any sizing or interval configuration is non-positive or invalid
     */
    @PostConstruct
    public void validate() {
        if (capacity <= 0) {
            throw new IllegalArgumentException("astra.indexing.embedding-queue.capacity must be greater than 0");
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException("astra.indexing.persistence.batch-size must be greater than 0");
        }
        if (minBatchSize <= 0) {
            throw new IllegalArgumentException("astra.indexing.persistence.min-batch-size must be greater than 0");
        }
        if (minBatchSize > batchSize) {
            throw new IllegalArgumentException(String.format(
                    "astra.indexing.persistence.min-batch-size (%d) must not exceed batch-size (%d)",
                    minBatchSize, batchSize));
        }
        if (flushIntervalMs <= 0) {
            throw new IllegalArgumentException("astra.indexing.persistence.flush-interval-ms must be greater than 0");
        }
        if (writerCount <= 0) {
            throw new IllegalArgumentException("astra.indexing.persistence.writer-count must be greater than 0");
        }
        if (tableName == null || !tableName.matches("^[a-zA-Z_][a-zA-Z0-9_]*$")) {
            throw new IllegalArgumentException("Invalid table-name: " + tableName);
        }
        if (schemaName == null || !schemaName.matches("^[a-zA-Z_][a-zA-Z0-9_]*$")) {
            throw new IllegalArgumentException("Invalid schema-name: " + schemaName);
        }

        // Dynamic batching validation
        if (adaptiveTargetSize <= 0) {
            throw new IllegalArgumentException("astra.indexing.persistence.dynamic-batching.adaptive-target-size must be greater than 0");
        }
        if (quietWindowMinMs <= 0) {
            throw new IllegalArgumentException("astra.indexing.persistence.dynamic-batching.quiet-window-min-ms must be greater than 0");
        }
        if (quietWindowMinMs > quietWindowMaxMs || quietWindowMaxMs > maxWaitMs) {
            throw new IllegalArgumentException(String.format(
                    "Expected quiet-window-min-ms (%d) <= quiet-window-max-ms (%d) <= max-wait-ms (%d)",
                    quietWindowMinMs, quietWindowMaxMs, maxWaitMs));
        }
        if (adaptiveTargetSize > batchSize || adaptiveTargetSize > capacity) {
            log.warn("astra.indexing.persistence.dynamic-batching.adaptive-target-size ({}) exceeds batch-size ({}) or capacity ({}) and will be clamped during runtime",
                    adaptiveTargetSize, batchSize, capacity);
        }
    }

    /**
     * Parameterized constructor for programmatic testing with dynamic batching disabled.
     *
     * @param capacity        queue capacity
     * @param batchSize       persistence batch size
     * @param minBatchSize    threshold above which a failed batch is split
     * @param flushIntervalMs maximum wait for an initial queue entry, in milliseconds
     * @param writerCount     writer concurrency
     * @param maxRetries      maximum retry attempts
     * @param retryBackoffMs  retry backoff milliseconds
     */
    public EmbeddingQueueConfig(int capacity, int batchSize, int minBatchSize,
                                long flushIntervalMs, int writerCount,
                                int maxRetries, long retryBackoffMs) {
        this.capacity = capacity;
        this.batchSize = batchSize;
        this.minBatchSize = minBatchSize;
        this.flushIntervalMs = flushIntervalMs;
        this.writerCount = writerCount;
        this.maxRetries = maxRetries;
        this.retryBackoffMs = retryBackoffMs;
        this.dynamicBatchingEnabled = false;
    }

    /**
     * Parameterized constructor for programmatic testing with dynamic batching enabled and custom dynamic parameters.
     *
     * @param capacity               queue capacity
     * @param batchSize              persistence batch size
     * @param minBatchSize           threshold above which a failed batch is split
     * @param flushIntervalMs        maximum wait for an initial queue entry, in milliseconds
     * @param writerCount            writer concurrency
     * @param maxRetries             maximum retry attempts
     * @param retryBackoffMs         retry backoff milliseconds
     * @param dynamicBatchingEnabled whether dynamic wave batch accumulation is enabled
     * @param adaptiveTargetSize     fallback target batch size when hints are unavailable
     * @param quietWindowMinMs       minimum quiet window in milliseconds
     * @param quietWindowMaxMs       maximum quiet window in milliseconds
     * @param maxWaitMs              maximum waiting time in milliseconds before mandatory flush
     */
    public EmbeddingQueueConfig(int capacity, int batchSize, int minBatchSize,
                                long flushIntervalMs, int writerCount,
                                int maxRetries, long retryBackoffMs,
                                boolean dynamicBatchingEnabled, int adaptiveTargetSize,
                                long quietWindowMinMs, long quietWindowMaxMs, long maxWaitMs) {
        this(capacity, batchSize, minBatchSize, flushIntervalMs, writerCount, maxRetries, retryBackoffMs);
        this.dynamicBatchingEnabled = dynamicBatchingEnabled;
        this.adaptiveTargetSize = adaptiveTargetSize;
        this.quietWindowMinMs = quietWindowMinMs;
        this.quietWindowMaxMs = quietWindowMaxMs;
        this.maxWaitMs = maxWaitMs;
    }

    /**
     * Returns the maximum bounded capacity of the embedding queue.
     *
     * @return queue capacity
     */
    public int getCapacity() {
        return capacity;
    }

    /**
     * Sets the maximum bounded capacity of the embedding queue.
     *
     * @param capacity queue capacity
     */
    public void setCapacity(int capacity) {
        this.capacity = capacity;
    }

    /**
     * Returns the target bulk persistence batch size.
     *
     * @return batch size
     */
    public int getBatchSize() {
        return batchSize;
    }

    /**
     * Sets the target bulk persistence batch size.
     *
     * @param batchSize batch size
     */
    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    /**
     * Returns the size threshold above which a failed batch is split.
     *
     * @return minimum batch size
     */
    public int getMinBatchSize() {
        return minBatchSize;
    }

    /**
     * Sets the size threshold above which a failed batch is split. A split can produce
     * batches smaller than this threshold.
     *
     * @param minBatchSize minimum batch size
     */
    public void setMinBatchSize(int minBatchSize) {
        this.minBatchSize = minBatchSize;
    }

    /**
     * Returns the maximum wait for an initial queue entry in milliseconds.
     *
     * @return flush interval in milliseconds
     */
    public long getFlushIntervalMs() {
        return flushIntervalMs;
    }

    /**
     * Sets the maximum wait for an initial queue entry in milliseconds.
     *
     * @param flushIntervalMs flush interval in milliseconds
     */
    public void setFlushIntervalMs(long flushIntervalMs) {
        this.flushIntervalMs = flushIntervalMs;
    }

    /**
     * Returns the number of concurrent persistence writers.
     *
     * @return writer thread count
     */
    public int getWriterCount() {
        return writerCount;
    }

    /**
     * Sets the number of concurrent persistence writers.
     *
     * @param writerCount writer thread count
     */
    public void setWriterCount(int writerCount) {
        this.writerCount = writerCount;
    }

    /**
     * Returns the maximum number of retries for a failed batch at or below the split threshold.
     *
     * @return max retries
     */
    public int getMaxRetries() {
        return maxRetries;
    }

    /**
     * Sets the maximum number of retries for a failed batch at or below the split threshold.
     *
     * @param maxRetries max retries
     */
    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    /**
     * Returns the base retry backoff in milliseconds.
     *
     * @return retry backoff in milliseconds
     */
    public long getRetryBackoffMs() {
        return retryBackoffMs;
    }

    /**
     * Sets the base retry backoff in milliseconds.
     *
     * @param retryBackoffMs retry backoff in milliseconds
     */
    public void setRetryBackoffMs(long retryBackoffMs) {
        this.retryBackoffMs = retryBackoffMs;
    }

    /**
     * Returns the target PGVector table name.
     *
     * @return table name
     */
    public String getTableName() {
        return tableName;
    }

    /**
     * Sets the target PGVector table name.
     *
     * @param tableName table name
     */
    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    /**
     * Returns the target PGVector schema name.
     *
     * @return schema name
     */
    public String getSchemaName() {
        return schemaName;
    }

    /**
     * Sets the target PGVector schema name.
     *
     * @param schemaName schema name
     */
    public void setSchemaName(String schemaName) {
        this.schemaName = schemaName;
    }

    /**
     * Returns whether dynamic wave batch accumulation is enabled.
     *
     * @return true if dynamic batching is enabled
     */
    public boolean isDynamicBatchingEnabled() {
        return dynamicBatchingEnabled;
    }

    /**
     * Sets whether dynamic wave batch accumulation is enabled.
     *
     * @param dynamicBatchingEnabled dynamic batching enable flag
     */
    public void setDynamicBatchingEnabled(boolean dynamicBatchingEnabled) {
        this.dynamicBatchingEnabled = dynamicBatchingEnabled;
    }

    /**
     * Returns the fallback target batch size for dynamic wave accumulation.
     *
     * @return adaptive target size
     */
    public int getAdaptiveTargetSize() {
        return adaptiveTargetSize;
    }

    /**
     * Sets the fallback target batch size for dynamic wave accumulation.
     *
     * @param adaptiveTargetSize adaptive target size
     */
    public void setAdaptiveTargetSize(int adaptiveTargetSize) {
        this.adaptiveTargetSize = adaptiveTargetSize;
    }

    /**
     * Returns the minimum quiet window in milliseconds.
     *
     * @return minimum quiet window ms
     */
    public long getQuietWindowMinMs() {
        return quietWindowMinMs;
    }

    /**
     * Sets the minimum quiet window in milliseconds.
     *
     * @param quietWindowMinMs minimum quiet window ms
     */
    public void setQuietWindowMinMs(long quietWindowMinMs) {
        this.quietWindowMinMs = quietWindowMinMs;
    }

    /**
     * Returns the maximum quiet window in milliseconds.
     *
     * @return maximum quiet window ms
     */
    public long getQuietWindowMaxMs() {
        return quietWindowMaxMs;
    }

    /**
     * Sets the maximum quiet window in milliseconds.
     *
     * @param quietWindowMaxMs maximum quiet window ms
     */
    public void setQuietWindowMaxMs(long quietWindowMaxMs) {
        this.quietWindowMaxMs = quietWindowMaxMs;
    }

    /**
     * Returns the maximum wait in milliseconds for the oldest buffered record before mandatory flush.
     *
     * @return max wait ms
     */
    public long getMaxWaitMs() {
        return maxWaitMs;
    }

    /**
     * Sets the maximum wait in milliseconds for the oldest buffered record before mandatory flush.
     *
     * @param maxWaitMs max wait ms
     */
    public void setMaxWaitMs(long maxWaitMs) {
        this.maxWaitMs = maxWaitMs;
    }
}
