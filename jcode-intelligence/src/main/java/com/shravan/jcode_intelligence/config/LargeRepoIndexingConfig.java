package com.shravan.jcode_intelligence.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for the large-repository streaming indexing pipeline.
 *
 * <p>Controls the batch size of chunks accumulated and processed per streaming wave
 * when indexing repositories classified as LARGE.
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

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException(
                    "astra.indexing.large-repo.batch-size must be positive, got: " + batchSize);
        }
        this.batchSize = batchSize;
    }

    public double getSafetyHeadroomFraction() {
        return safetyHeadroomFraction;
    }

    public void setSafetyHeadroomFraction(double safetyHeadroomFraction) {
        if (Double.isNaN(safetyHeadroomFraction) || safetyHeadroomFraction <= 0.0 || safetyHeadroomFraction >= 1.0) {
            throw new IllegalArgumentException(
                    "astra.indexing.large-repo.safety-headroom-fraction must be in (0, 1), got: " + safetyHeadroomFraction);
        }
        this.safetyHeadroomFraction = safetyHeadroomFraction;
    }
}
