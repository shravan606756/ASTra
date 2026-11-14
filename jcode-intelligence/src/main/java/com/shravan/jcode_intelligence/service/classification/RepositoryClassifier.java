package com.shravan.jcode_intelligence.service.classification;

import com.shravan.jcode_intelligence.config.MemoryClassificationConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Memory-aware repository classifier.
 *
 * <p>Compares estimated repository memory need against JVM heap budget to determine whether
 * a repository fits in memory (NORMAL) or requires streaming (LARGE).
 */
@Component
public class RepositoryClassifier {

    private final LocAnalyzer locAnalyzer;
    private final MaxHeapProvider maxHeapProvider;
    private final MemoryClassificationConfig config;

    @Autowired
    public RepositoryClassifier(LocAnalyzer locAnalyzer,
                                MaxHeapProvider maxHeapProvider,
                                MemoryClassificationConfig config) {
        this.locAnalyzer = Objects.requireNonNull(locAnalyzer, "locAnalyzer must not be null");
        this.maxHeapProvider = Objects.requireNonNull(maxHeapProvider, "maxHeapProvider must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
    }

    /**
     * Discovers and analyzes repository sources, then classifies based on memory budget or threshold override.
     *
     * @param root repository root directory or source path
     * @return classification result containing decision and memory metrics
     * @throws IOException if file discovery or reading fails
     */
    public RepositoryClassificationResult classify(Path root) throws IOException {
        LocAnalysis analysis = locAnalyzer.analyze(root);
        return classify(analysis);
    }

    /**
     * Classifies a pre-analyzed {@link LocAnalysis} against the current JVM heap budget or manual threshold.
     *
     * @param analysis line-of-code analysis
     * @return classification result containing decision and memory metrics
     */
    public RepositoryClassificationResult classify(LocAnalysis analysis) {
        Objects.requireNonNull(analysis, "analysis must not be null");

        long totalLoc = analysis.totalLoc();
        long maxHeapBytes = maxHeapProvider.maxHeapBytes();
        double budgetFraction = config.getBudgetFraction();
        long bytesPerLoc = config.getBytesPerLoc();
        Long normalThreshold = config.getNormalThreshold();

        // 1. Calculate budgetBytes in an overflow-safe manner, handling Long.MAX_VALUE
        long budgetBytes;
        if (maxHeapBytes == Long.MAX_VALUE && budgetFraction >= 1.0) {
            budgetBytes = Long.MAX_VALUE;
        } else {
            double rawBudget = maxHeapBytes * budgetFraction;
            if (rawBudget >= (double) Long.MAX_VALUE) {
                budgetBytes = Long.MAX_VALUE;
            } else if (rawBudget < 0.0) {
                budgetBytes = 0L;
            } else {
                budgetBytes = (long) rawBudget;
            }
        }

        // 2. Calculate estimatedNeedBytes with saturating arithmetic
        long estimatedNeedBytes;
        try {
            estimatedNeedBytes = Math.multiplyExact(totalLoc, bytesPerLoc);
        } catch (ArithmeticException e) {
            estimatedNeedBytes = Long.MAX_VALUE;
        }

        // 3. Determine classification based on override or memory budget
        boolean overrideUsed = (normalThreshold != null);
        RepositoryClass classification;
        if (overrideUsed) {
            classification = (totalLoc <= normalThreshold) ? RepositoryClass.NORMAL : RepositoryClass.LARGE;
        } else {
            classification = (estimatedNeedBytes <= budgetBytes) ? RepositoryClass.NORMAL : RepositoryClass.LARGE;
        }

        return new RepositoryClassificationResult(
                classification,
                totalLoc,
                analysis.eligibleFiles().size(),
                analysis.eligibleFiles(),
                maxHeapBytes,
                budgetBytes,
                estimatedNeedBytes,
                overrideUsed
        );
    }
}
