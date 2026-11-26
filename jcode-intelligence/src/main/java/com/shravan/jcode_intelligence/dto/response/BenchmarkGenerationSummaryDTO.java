package com.shravan.jcode_intelligence.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Summary metrics for generation evaluation and citation validation across an entire benchmark run.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BenchmarkGenerationSummaryDTO {

    // ── Operational & Batch Performance Metrics ────
    private int totalQueriesEvaluated;
    private int successfulGenerations;
    private int failedGenerations;
    private int batchCount;
    private Double averageGenerationLatencyMs;
    private Double averageJudgeLatencyMs;
    private Long totalPromptTokens;
    private Long totalCompletionTokens;
    private Long totalTokens;
    private Double averageTokensPerQuery;

    // ── Semantic Quality Metrics ───────────────────
    private Double averageFaithfulness;
    private Double averageRelevancy;
    private Double averageCorrectness;
    private Boolean correctnessApproximate = true;
    private Double averageCitationPrecision;
    private Integer totalUngroundedClaims;

    // ── Structural Citation Metrics ────────────────
    private Double averageCitationCorrectness;
    private Double averageExpectedSourceRecall;
    private int totalCitationsAttempted;
    private int totalCitationsValidSyntax;
    private int totalCitationsStructurallyValid;
    private String revisionStatus;

    public BenchmarkGenerationSummaryDTO() {
    }

    public int getTotalQueriesEvaluated() {
        return totalQueriesEvaluated;
    }

    public void setTotalQueriesEvaluated(int totalQueriesEvaluated) {
        this.totalQueriesEvaluated = totalQueriesEvaluated;
    }

    public int getSuccessfulGenerations() {
        return successfulGenerations;
    }

    public void setSuccessfulGenerations(int successfulGenerations) {
        this.successfulGenerations = successfulGenerations;
    }

    public int getFailedGenerations() {
        return failedGenerations;
    }

    public void setFailedGenerations(int failedGenerations) {
        this.failedGenerations = failedGenerations;
    }

    public int getBatchCount() {
        return batchCount;
    }

    public void setBatchCount(int batchCount) {
        this.batchCount = batchCount;
    }

    public Double getAverageGenerationLatencyMs() {
        return averageGenerationLatencyMs;
    }

    public void setAverageGenerationLatencyMs(Double averageGenerationLatencyMs) {
        this.averageGenerationLatencyMs = averageGenerationLatencyMs;
    }

    public Double getAverageJudgeLatencyMs() {
        return averageJudgeLatencyMs;
    }

    public void setAverageJudgeLatencyMs(Double averageJudgeLatencyMs) {
        this.averageJudgeLatencyMs = averageJudgeLatencyMs;
    }

    public Long getTotalPromptTokens() {
        return totalPromptTokens;
    }

    public void setTotalPromptTokens(Long totalPromptTokens) {
        this.totalPromptTokens = totalPromptTokens;
    }

    public Long getTotalCompletionTokens() {
        return totalCompletionTokens;
    }

    public void setTotalCompletionTokens(Long totalCompletionTokens) {
        this.totalCompletionTokens = totalCompletionTokens;
    }

    public Long getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(Long totalTokens) {
        this.totalTokens = totalTokens;
    }

    public Double getAverageTokensPerQuery() {
        return averageTokensPerQuery;
    }

    public void setAverageTokensPerQuery(Double averageTokensPerQuery) {
        this.averageTokensPerQuery = averageTokensPerQuery;
    }

    public Double getAverageFaithfulness() {
        return averageFaithfulness;
    }

    public void setAverageFaithfulness(Double averageFaithfulness) {
        this.averageFaithfulness = averageFaithfulness;
    }

    public Double getAverageRelevancy() {
        return averageRelevancy;
    }

    public void setAverageRelevancy(Double averageRelevancy) {
        this.averageRelevancy = averageRelevancy;
    }

    public Double getAverageCorrectness() {
        return averageCorrectness;
    }

    public void setAverageCorrectness(Double averageCorrectness) {
        this.averageCorrectness = averageCorrectness;
    }

    public Boolean getCorrectnessApproximate() {
        return correctnessApproximate;
    }

    public void setCorrectnessApproximate(Boolean correctnessApproximate) {
        this.correctnessApproximate = correctnessApproximate;
    }

    public Double getAverageCitationPrecision() {
        return averageCitationPrecision;
    }

    public void setAverageCitationPrecision(Double averageCitationPrecision) {
        this.averageCitationPrecision = averageCitationPrecision;
    }

    public Integer getTotalUngroundedClaims() {
        return totalUngroundedClaims;
    }

    public void setTotalUngroundedClaims(Integer totalUngroundedClaims) {
        this.totalUngroundedClaims = totalUngroundedClaims;
    }

    public Double getAverageCitationCorrectness() {
        return averageCitationCorrectness;
    }

    public void setAverageCitationCorrectness(Double averageCitationCorrectness) {
        this.averageCitationCorrectness = averageCitationCorrectness;
    }

    public Double getAverageExpectedSourceRecall() {
        return averageExpectedSourceRecall;
    }

    public void setAverageExpectedSourceRecall(Double averageExpectedSourceRecall) {
        this.averageExpectedSourceRecall = averageExpectedSourceRecall;
    }

    public int getTotalCitationsAttempted() {
        return totalCitationsAttempted;
    }

    public void setTotalCitationsAttempted(int totalCitationsAttempted) {
        this.totalCitationsAttempted = totalCitationsAttempted;
    }

    public int getTotalCitationsValidSyntax() {
        return totalCitationsValidSyntax;
    }

    public void setTotalCitationsValidSyntax(int totalCitationsValidSyntax) {
        this.totalCitationsValidSyntax = totalCitationsValidSyntax;
    }

    public int getTotalCitationsStructurallyValid() {
        return totalCitationsStructurallyValid;
    }

    public void setTotalCitationsStructurallyValid(int totalCitationsStructurallyValid) {
        this.totalCitationsStructurallyValid = totalCitationsStructurallyValid;
    }

    public String getRevisionStatus() {
        return revisionStatus;
    }

    public void setRevisionStatus(String revisionStatus) {
        this.revisionStatus = revisionStatus;
    }
}
