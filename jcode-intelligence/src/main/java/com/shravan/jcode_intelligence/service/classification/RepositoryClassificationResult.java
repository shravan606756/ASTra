package com.shravan.jcode_intelligence.service.classification;

import java.nio.file.Path;
import java.util.List;

/**
 * Result of memory-aware repository classification.
 *
 * @param classification     the determined repository classification (NORMAL or LARGE)
 * @param totalLoc           total non-blank lines of code
 * @param eligibleFileCount  number of eligible files discovered
 * @param eligibleFiles      immutable list of eligible file paths
 * @param maxHeapBytes       maximum heap memory of the JVM in bytes
 * @param budgetBytes        memory budget allocated for AST and chunk retention in bytes
 * @param estimatedNeedBytes estimated memory requirement for this repository in bytes
 * @param overrideUsed       true if classification was determined by manual LOC threshold override
 */
public record RepositoryClassificationResult(
        RepositoryClass classification,
        long totalLoc,
        int eligibleFileCount,
        List<Path> eligibleFiles,
        long maxHeapBytes,
        long budgetBytes,
        long estimatedNeedBytes,
        boolean overrideUsed
) {
    public RepositoryClassificationResult {
        eligibleFiles = (eligibleFiles == null) ? List.of() : List.copyOf(eligibleFiles);
    }
}
