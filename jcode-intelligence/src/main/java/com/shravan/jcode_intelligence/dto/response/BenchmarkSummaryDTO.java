package com.shravan.jcode_intelligence.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Summary metrics for an entire benchmark evaluation run.
 * Cleanly separates retrieval quality metrics from operational performance metrics.
 * Quality metrics are omitted when ground-truth annotations are unavailable.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BenchmarkSummaryDTO {

    private boolean hasGroundTruth = true;

    // ── Retrieval Quality Metrics (Only populated when ground truth exists) ──
    private Double hitRateAt3;
    private Double hitRateAt5;
    private Double hitRateAt25;

    private Double recallAt3;
    private Double recallAt5;
    private Double recallAt25;

    private Double precisionAt3;
    private Double precisionAt5;
    private Double precisionAt25;

    private Double mrr; // Mean Reciprocal Rank

    private Double ndcgAt3;
    private Double ndcgAt5;
    private Double ndcgAt25;

    private Double contextPrecision;
    private Double contextRecall;

    private int totalQueries;
    private int successfulQueries;
    private int failedQueries;

    // ── Operational Performance Metrics (Always populated) ────
    private double avgTotalLatencyMs;
    private double avgEmbeddingLatencyMs;
    private double avgVectorSearchLatencyMs;
    private double avgRerankingLatencyMs;
    private double avgRetrievedChunks;

    private long totalDurationMs;
    private int failureCount;
    private int timeoutCount;

    public BenchmarkSummaryDTO() {
    }

    public boolean isHasGroundTruth() {
        return hasGroundTruth;
    }

    public void setHasGroundTruth(boolean hasGroundTruth) {
        this.hasGroundTruth = hasGroundTruth;
    }

    // Getters and setters for Retrieval Quality Metrics
    public Double getHitRateAt3() { return hitRateAt3; }
    public void setHitRateAt3(Double hitRateAt3) { this.hitRateAt3 = hitRateAt3; }

    public Double getHitRateAt5() { return hitRateAt5; }
    public void setHitRateAt5(Double hitRateAt5) { this.hitRateAt5 = hitRateAt5; }

    public Double getHitRateAt25() { return hitRateAt25; }
    public void setHitRateAt25(Double hitRateAt25) { this.hitRateAt25 = hitRateAt25; }

    public Double getRecallAt3() { return recallAt3; }
    public void setRecallAt3(Double recallAt3) { this.recallAt3 = recallAt3; }

    public Double getRecallAt5() { return recallAt5; }
    public void setRecallAt5(Double recallAt5) { this.recallAt5 = recallAt5; }

    public Double getRecallAt25() { return recallAt25; }
    public void setRecallAt25(Double recallAt25) { this.recallAt25 = recallAt25; }

    public Double getPrecisionAt3() { return precisionAt3; }
    public void setPrecisionAt3(Double precisionAt3) { this.precisionAt3 = precisionAt3; }

    public Double getPrecisionAt5() { return precisionAt5; }
    public void setPrecisionAt5(Double precisionAt5) { this.precisionAt5 = precisionAt5; }

    public Double getPrecisionAt25() { return precisionAt25; }
    public void setPrecisionAt25(Double precisionAt25) { this.precisionAt25 = precisionAt25; }

    public Double getMrr() { return mrr; }
    public void setMrr(Double mrr) { this.mrr = mrr; }

    public Double getNdcgAt3() { return ndcgAt3; }
    public void setNdcgAt3(Double ndcgAt3) { this.ndcgAt3 = ndcgAt3; }

    public Double getNdcgAt5() { return ndcgAt5; }
    public void setNdcgAt5(Double ndcgAt5) { this.ndcgAt5 = ndcgAt5; }

    public Double getNdcgAt25() { return ndcgAt25; }
    public void setNdcgAt25(Double ndcgAt25) { this.ndcgAt25 = ndcgAt25; }

    public Double getContextPrecision() { return contextPrecision; }
    public void setContextPrecision(Double contextPrecision) { this.contextPrecision = contextPrecision; }

    public Double getContextRecall() { return contextRecall; }
    public void setContextRecall(Double contextRecall) { this.contextRecall = contextRecall; }

    public int getTotalQueries() { return totalQueries; }
    public void setTotalQueries(int totalQueries) { this.totalQueries = totalQueries; }

    public int getSuccessfulQueries() { return successfulQueries; }
    public void setSuccessfulQueries(int successfulQueries) { this.successfulQueries = successfulQueries; }

    public int getFailedQueries() { return failedQueries; }
    public void setFailedQueries(int failedQueries) { this.failedQueries = failedQueries; }

    // Getters and setters for Operational Performance Metrics
    public double getAvgTotalLatencyMs() { return avgTotalLatencyMs; }
    public void setAvgTotalLatencyMs(double avgTotalLatencyMs) { this.avgTotalLatencyMs = avgTotalLatencyMs; }

    public double getAvgEmbeddingLatencyMs() { return avgEmbeddingLatencyMs; }
    public void setAvgEmbeddingLatencyMs(double avgEmbeddingLatencyMs) { this.avgEmbeddingLatencyMs = avgEmbeddingLatencyMs; }

    public double getAvgVectorSearchLatencyMs() { return avgVectorSearchLatencyMs; }
    public void setAvgVectorSearchLatencyMs(double avgVectorSearchLatencyMs) { this.avgVectorSearchLatencyMs = avgVectorSearchLatencyMs; }

    public double getAvgRerankingLatencyMs() { return avgRerankingLatencyMs; }
    public void setAvgRerankingLatencyMs(double avgRerankingLatencyMs) { this.avgRerankingLatencyMs = avgRerankingLatencyMs; }

    public double getAvgRetrievedChunks() { return avgRetrievedChunks; }
    public void setAvgRetrievedChunks(double avgRetrievedChunks) { this.avgRetrievedChunks = avgRetrievedChunks; }

    public long getTotalDurationMs() { return totalDurationMs; }
    public void setTotalDurationMs(long totalDurationMs) { this.totalDurationMs = totalDurationMs; }

    public int getFailureCount() { return failureCount; }
    public void setFailureCount(int failureCount) { this.failureCount = failureCount; }

    public int getTimeoutCount() { return timeoutCount; }
    public void setTimeoutCount(int timeoutCount) { this.timeoutCount = timeoutCount; }
}
