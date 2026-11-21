package com.shravan.jcode_intelligence.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for the large-repository streaming indexing pipeline.
 *
 * <p>Controls the batch size of chunks accumulated and processed per streaming wave
 * when indexing repositories classified as LARGE, as well as the safety headroom threshold
 * for early memory pressure flushes.
 *
 * <p><strong>Key Properties:</strong>
 * <ul>
 *   <li>{@code astra.indexing.large-repo.batch-size}: Base floor for dynamic wave chunk sizing
 *       (default: 2000). On machines with ample heap, the pass threshold expands dynamically
 *       above this base floor; on constrained machines (or unit tests), this base floor is respected.</li>
 *   <li>{@code astra.indexing.large-repo.safety-headroom-fraction}: Fraction of max heap below which
 *       an early emergency flush of any non-empty chunk buffer is triggered (default: 0.25, i.e. 25%).</li>
 * </ul>
 */
@Configuration
public class LargeRepoIndexingConfig {

    /**
     * Chunk batch size accumulated and processed per streaming wave in the LARGE repository pipeline.
     * Default is 2000 chunks.
     */
    @Value("${astra.indexing.large-repo.batch-size:2000}")
    private int batchSize = 2000;

    /**
     * Safety headroom fraction of max heap below which an early streaming pass flush is triggered.
     * Default is 0.25 (triggers when unallocated heap dips below 25% of max heap).
     */
    @Value("${astra.indexing.large-repo.safety-headroom-fraction:0.25}")
    private double safetyHeadroomFraction = 0.25;

    public LargeRepoIndexingConfig() {
    }

    public LargeRepoIndexingConfig(int batchSize) {
        this(batchSize, 0.25);
    }

    public LargeRepoIndexingConfig(int batchSize, double safetyHeadroomFraction) {
        this.batchSize = batchSize;
        this.safetyHeadroomFraction = safetyHeadroomFraction;
        validate();
    }

    @PostConstruct
    public void validate() {
        if (batchSize <= 0) {
            throw new IllegalArgumentException(
                    "astra.indexing.large-repo.batch-size must be positive, got: " + batchSize);
        }
        if (Double.isNaN(safetyHeadroomFraction) || safetyHeadroomFraction <= 0.0 || safetyHeadroomFraction >= 1.0) {
            throw new IllegalArgumentException(
                    "astra.indexing.large-repo.safety-headroom-fraction must be in (0, 1), got: " + safetyHeadroomFraction);
        }
    }

    /**
     * Returns the configured base chunk batch size for large repository indexing waves.
     *
     * @return base chunk batch size
     */
    public int getBatchSize() {
        return batchSize;
    }

    /**
     * Updates the base chunk batch size with pre-assignment validation.
     *
     * @param batchSize must be a strictly positive integer (> 0)
     * @throws IllegalArgumentException if batchSize is non-positive
     */
    public void setBatchSize(int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException(
                    "astra.indexing.large-repo.batch-size must be positive, got: " + batchSize);
        }
        this.batchSize = batchSize;
    }

    /**
     * Returns the safety headroom fraction of max heap for memory relief flushes.
     *
     * @return safety headroom fraction in range (0.0, 1.0)
     */
    public double getSafetyHeadroomFraction() {
        return safetyHeadroomFraction;
    }

    /**
     * Updates the safety headroom fraction with pre-assignment validation.
     *
     * @param safetyHeadroomFraction must be strictly between 0.0 and 1.0 (exclusive) and not NaN
     * @throws IllegalArgumentException if fraction is out of bounds or NaN
     */
    public void setSafetyHeadroomFraction(double safetyHeadroomFraction) {
        if (Double.isNaN(safetyHeadroomFraction) || safetyHeadroomFraction <= 0.0 || safetyHeadroomFraction >= 1.0) {
            throw new IllegalArgumentException(
                    "astra.indexing.large-repo.safety-headroom-fraction must be in (0, 1), got: " + safetyHeadroomFraction);
        }
        this.safetyHeadroomFraction = safetyHeadroomFraction;
    }
}
