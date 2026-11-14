package com.shravan.jcode_intelligence.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for memory-aware repository classification.
 *
 * <p>Determines the memory budget fraction and workload bytes-per-LOC factor
 * used to classify repositories into NORMAL or LARGE indexing pipelines, along
 * with an optional manual LOC threshold override.
 */
@Configuration
public class MemoryClassificationConfig {

    /** Fraction of max heap allocated as memory budget for ASTs and chunks. */
    @Value("${astra.indexing.memory.budget-fraction:0.4}")
    private double budgetFraction = 0.4;

    /** Estimated workload memory required per line of code (in bytes). */
    @Value("${astra.indexing.memory.bytes-per-loc:600}")
    private long bytesPerLoc = 600L;

    /** Optional manual LOC threshold override. If set, repositories with LOC <= threshold are classified NORMAL. */
    @Value("${astra.indexing.loc.normal-threshold:#{null}}")
    private Long normalThreshold = null;

    public MemoryClassificationConfig() {
    }

    public MemoryClassificationConfig(double budgetFraction, long bytesPerLoc, Long normalThreshold) {
        this.budgetFraction = budgetFraction;
        this.bytesPerLoc = bytesPerLoc;
        this.normalThreshold = normalThreshold;
        validate();
    }

    @PostConstruct
    public void validate() {
        if (budgetFraction <= 0.0 || budgetFraction > 1.0) {
            throw new IllegalArgumentException(
                    "astra.indexing.memory.budget-fraction must be in (0, 1], got: " + budgetFraction);
        }
        if (bytesPerLoc <= 0) {
            throw new IllegalArgumentException(
                    "astra.indexing.memory.bytes-per-loc must be positive, got: " + bytesPerLoc);
        }
        if (normalThreshold != null && normalThreshold < 0) {
            throw new IllegalArgumentException(
                    "astra.indexing.loc.normal-threshold must be non-negative when set, got: " + normalThreshold);
        }
    }

    public double getBudgetFraction() {
        return budgetFraction;
    }

    public void setBudgetFraction(double budgetFraction) {
        this.budgetFraction = budgetFraction;
    }

    public long getBytesPerLoc() {
        return bytesPerLoc;
    }

    public void setBytesPerLoc(long bytesPerLoc) {
        this.bytesPerLoc = bytesPerLoc;
    }

    public Long getNormalThreshold() {
        return normalThreshold;
    }

    public void setNormalThreshold(Long normalThreshold) {
        this.normalThreshold = normalThreshold;
    }
}
