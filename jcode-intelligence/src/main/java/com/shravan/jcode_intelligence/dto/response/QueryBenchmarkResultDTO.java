package com.shravan.jcode_intelligence.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.shravan.jcode_intelligence.model.benchmark.RelevantChunkDefinition;
import java.util.ArrayList;
import java.util.List;

/**
 * Detailed evaluation and operational result for a single benchmark query.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class QueryBenchmarkResultDTO {

    private String queryId;
    private String query;
    private String category;
    private String expectedIntent;
    private String classifiedIntent;
    private boolean intentMatch;
    private boolean hasGroundTruth = true;
    private String status; // "SUCCESS" or "FAILED"
    private String errorMessage;
    private List<RetrievedChunkDTO> retrievedChunks = new ArrayList<>();
    private QueryQualityMetricsDTO qualityMetrics;
    private QueryOperationalMetricsDTO operationalMetrics;
    private Integer candidateCount;
    private Long generationLatencyMs;
    private Long judgeLatencyMs;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    private QueryGenerationResultDTO generationResult;
    private CitationValidationDTO citationValidation;
    private List<RelevantChunkDefinition> unmatchedGroundTruths;

    public QueryBenchmarkResultDTO() {
    }

    public QueryBenchmarkResultDTO(String queryId, String query, String category, String expectedIntent,
            String classifiedIntent, boolean intentMatch, String status,
            String errorMessage, List<RetrievedChunkDTO> retrievedChunks,
            QueryQualityMetricsDTO qualityMetrics,
            QueryOperationalMetricsDTO operationalMetrics) {
        this.queryId = queryId;
        this.query = query;
        this.category = category;
        this.expectedIntent = expectedIntent;
        this.classifiedIntent = classifiedIntent;
        this.intentMatch = intentMatch;
        this.hasGroundTruth = (qualityMetrics != null);
        this.status = status;
        this.errorMessage = errorMessage;
        this.retrievedChunks = (retrievedChunks != null) ? retrievedChunks : new ArrayList<>();
        this.qualityMetrics = qualityMetrics;
        this.operationalMetrics = operationalMetrics;
    }

    public String getQueryId() {
        return queryId;
    }

    public void setQueryId(String queryId) {
        this.queryId = queryId;
    }

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getExpectedIntent() {
        return expectedIntent;
    }

    public void setExpectedIntent(String expectedIntent) {
        this.expectedIntent = expectedIntent;
    }

    public String getClassifiedIntent() {
        return classifiedIntent;
    }

    public void setClassifiedIntent(String classifiedIntent) {
        this.classifiedIntent = classifiedIntent;
    }

    public boolean isIntentMatch() {
        return intentMatch;
    }

    public void setIntentMatch(boolean intentMatch) {
        this.intentMatch = intentMatch;
    }

    public boolean isHasGroundTruth() {
        return hasGroundTruth;
    }

    public void setHasGroundTruth(boolean hasGroundTruth) {
        this.hasGroundTruth = hasGroundTruth;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public List<RetrievedChunkDTO> getRetrievedChunks() {
        return retrievedChunks;
    }

    public void setRetrievedChunks(List<RetrievedChunkDTO> retrievedChunks) {
        this.retrievedChunks = retrievedChunks;
    }

    public QueryQualityMetricsDTO getQualityMetrics() {
        return qualityMetrics;
    }

    public void setQualityMetrics(QueryQualityMetricsDTO qualityMetrics) {
        this.qualityMetrics = qualityMetrics;
    }

    public QueryOperationalMetricsDTO getOperationalMetrics() {
        return operationalMetrics;
    }

    public void setOperationalMetrics(QueryOperationalMetricsDTO operationalMetrics) {
        this.operationalMetrics = operationalMetrics;
    }

    public Integer getCandidateCount() {
        return candidateCount;
    }

    public void setCandidateCount(Integer candidateCount) {
        this.candidateCount = candidateCount;
    }

    public Long getGenerationLatencyMs() {
        return generationLatencyMs;
    }

    public void setGenerationLatencyMs(Long generationLatencyMs) {
        this.generationLatencyMs = generationLatencyMs;
    }

    public Long getJudgeLatencyMs() {
        return judgeLatencyMs;
    }

    public void setJudgeLatencyMs(Long judgeLatencyMs) {
        this.judgeLatencyMs = judgeLatencyMs;
    }

    public Integer getPromptTokens() {
        return promptTokens;
    }

    public void setPromptTokens(Integer promptTokens) {
        this.promptTokens = promptTokens;
    }

    public Integer getCompletionTokens() {
        return completionTokens;
    }

    public void setCompletionTokens(Integer completionTokens) {
        this.completionTokens = completionTokens;
    }

    public Integer getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(Integer totalTokens) {
        this.totalTokens = totalTokens;
    }

    public QueryGenerationResultDTO getGenerationResult() {
        return generationResult;
    }

    public void setGenerationResult(QueryGenerationResultDTO generationResult) {
        this.generationResult = generationResult;
    }

    public CitationValidationDTO getCitationValidation() {
        return citationValidation;
    }

    public void setCitationValidation(CitationValidationDTO citationValidation) {
        this.citationValidation = citationValidation;
    }

    public List<RelevantChunkDefinition> getUnmatchedGroundTruths() {
        return unmatchedGroundTruths;
    }

    public void setUnmatchedGroundTruths(List<RelevantChunkDefinition> unmatchedGroundTruths) {
        this.unmatchedGroundTruths = unmatchedGroundTruths;
    }

    // ── Nested DTOs ──────────────────────────────────────────

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class RetrievedChunkDTO {
        private String chunkId;
        private String filePath;
        private String className;
        private String elementName;
        private String type;
        private int rank;
        private Boolean relevant;
        private Integer relevanceGrade;
        private String matchReason;

        public RetrievedChunkDTO() {
        }

        public RetrievedChunkDTO(String chunkId, String filePath, String className, String elementName,
                String type, int rank, Boolean relevant, Integer relevanceGrade) {
            this(chunkId, filePath, className, elementName, type, rank, relevant, relevanceGrade, null);
        }

        public RetrievedChunkDTO(String chunkId, String filePath, String className, String elementName,
                String type, int rank, Boolean relevant, Integer relevanceGrade, String matchReason) {
            this.chunkId = chunkId;
            this.filePath = filePath;
            this.className = className;
            this.elementName = elementName;
            this.type = type;
            this.rank = rank;
            this.relevant = relevant;
            this.relevanceGrade = relevanceGrade;
            this.matchReason = matchReason;
        }

        public String getChunkId() {
            return chunkId;
        }

        public void setChunkId(String chunkId) {
            this.chunkId = chunkId;
        }

        public String getFilePath() {
            return filePath;
        }

        public void setFilePath(String filePath) {
            this.filePath = filePath;
        }

        public String getClassName() {
            return className;
        }

        public void setClassName(String className) {
            this.className = className;
        }

        public String getElementName() {
            return elementName;
        }

        public void setElementName(String elementName) {
            this.elementName = elementName;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public int getRank() {
            return rank;
        }

        public void setRank(int rank) {
            this.rank = rank;
        }

        public boolean isRelevant() {
            return relevant != null && relevant;
        }

        public Boolean getRelevant() {
            return relevant;
        }

        public void setRelevant(Boolean relevant) {
            this.relevant = relevant;
        }

        public Integer getRelevanceGrade() {
            return relevanceGrade;
        }

        public void setRelevanceGrade(Integer relevanceGrade) {
            this.relevanceGrade = relevanceGrade;
        }

        public String getMatchReason() {
            return matchReason;
        }

        public void setMatchReason(String matchReason) {
            this.matchReason = matchReason;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class QueryQualityMetricsDTO {
        private double hitRateAt3;
        private double hitRateAt5;
        private double hitRateAt25;
        private double recallAt3;
        private double recallAt5;
        private double recallAt25;
        private double precisionAt3;
        private double precisionAt5;
        private double precisionAt25;
        private double reciprocalRank;
        private double ndcgAt3;
        private double ndcgAt5;
        private double ndcgAt25;
        private double contextPrecision;
        private double contextRecall;

        public QueryQualityMetricsDTO() {
        }

        public double getHitRateAt3() {
            return hitRateAt3;
        }

        public void setHitRateAt3(double hitRateAt3) {
            this.hitRateAt3 = hitRateAt3;
        }

        public double getHitRateAt5() {
            return hitRateAt5;
        }

        public void setHitRateAt5(double hitRateAt5) {
            this.hitRateAt5 = hitRateAt5;
        }

        public double getHitRateAt25() {
            return hitRateAt25;
        }

        public void setHitRateAt25(double hitRateAt25) {
            this.hitRateAt25 = hitRateAt25;
        }

        public double getRecallAt3() {
            return recallAt3;
        }

        public void setRecallAt3(double recallAt3) {
            this.recallAt3 = recallAt3;
        }

        public double getRecallAt5() {
            return recallAt5;
        }

        public void setRecallAt5(double recallAt5) {
            this.recallAt5 = recallAt5;
        }

        public double getRecallAt25() {
            return recallAt25;
        }

        public void setRecallAt25(double recallAt25) {
            this.recallAt25 = recallAt25;
        }

        public double getPrecisionAt3() {
            return precisionAt3;
        }

        public void setPrecisionAt3(double precisionAt3) {
            this.precisionAt3 = precisionAt3;
        }

        public double getPrecisionAt5() {
            return precisionAt5;
        }

        public void setPrecisionAt5(double precisionAt5) {
            this.precisionAt5 = precisionAt5;
        }

        public double getPrecisionAt25() {
            return precisionAt25;
        }

        public void setPrecisionAt25(double precisionAt25) {
            this.precisionAt25 = precisionAt25;
        }

        public double getReciprocalRank() {
            return reciprocalRank;
        }

        public void setReciprocalRank(double reciprocalRank) {
            this.reciprocalRank = reciprocalRank;
        }

        public double getNdcgAt3() {
            return ndcgAt3;
        }

        public void setNdcgAt3(double ndcgAt3) {
            this.ndcgAt3 = ndcgAt3;
        }

        public double getNdcgAt5() {
            return ndcgAt5;
        }

        public void setNdcgAt5(double ndcgAt5) {
            this.ndcgAt5 = ndcgAt5;
        }

        public double getNdcgAt25() {
            return ndcgAt25;
        }

        public void setNdcgAt25(double ndcgAt25) {
            this.ndcgAt25 = ndcgAt25;
        }

        public double getContextPrecision() {
            return contextPrecision;
        }

        public void setContextPrecision(double contextPrecision) {
            this.contextPrecision = contextPrecision;
        }

        public double getContextRecall() {
            return contextRecall;
        }

        public void setContextRecall(double contextRecall) {
            this.contextRecall = contextRecall;
        }
    }

    public static class QueryOperationalMetricsDTO {
        private long totalLatencyMs;
        private long embeddingLatencyMs;
        private long vectorSearchLatencyMs;
        private long rerankingLatencyMs;
        private int retrievedChunkCount;
        private Integer candidateCount;

        public QueryOperationalMetricsDTO() {
        }

        public QueryOperationalMetricsDTO(long totalLatencyMs, long embeddingLatencyMs,
                long vectorSearchLatencyMs, long rerankingLatencyMs,
                int retrievedChunkCount) {
            this(totalLatencyMs, embeddingLatencyMs, vectorSearchLatencyMs, rerankingLatencyMs, retrievedChunkCount, null);
        }

        public QueryOperationalMetricsDTO(long totalLatencyMs, long embeddingLatencyMs,
                long vectorSearchLatencyMs, long rerankingLatencyMs,
                int retrievedChunkCount, Integer candidateCount) {
            this.totalLatencyMs = totalLatencyMs;
            this.embeddingLatencyMs = embeddingLatencyMs;
            this.vectorSearchLatencyMs = vectorSearchLatencyMs;
            this.rerankingLatencyMs = rerankingLatencyMs;
            this.retrievedChunkCount = retrievedChunkCount;
            this.candidateCount = candidateCount;
        }

        public long getTotalLatencyMs() {
            return totalLatencyMs;
        }

        public void setTotalLatencyMs(long totalLatencyMs) {
            this.totalLatencyMs = totalLatencyMs;
        }

        public long getEmbeddingLatencyMs() {
            return embeddingLatencyMs;
        }

        public void setEmbeddingLatencyMs(long embeddingLatencyMs) {
            this.embeddingLatencyMs = embeddingLatencyMs;
        }

        public long getVectorSearchLatencyMs() {
            return vectorSearchLatencyMs;
        }

        public void setVectorSearchLatencyMs(long vectorSearchLatencyMs) {
            this.vectorSearchLatencyMs = vectorSearchLatencyMs;
        }

        public long getRerankingLatencyMs() {
            return rerankingLatencyMs;
        }

        public void setRerankingLatencyMs(long rerankingLatencyMs) {
            this.rerankingLatencyMs = rerankingLatencyMs;
        }

        public int getRetrievedChunkCount() {
            return retrievedChunkCount;
        }

        public void setRetrievedChunkCount(int retrievedChunkCount) {
            this.retrievedChunkCount = retrievedChunkCount;
        }

        public Integer getCandidateCount() {
            return candidateCount;
        }

        public void setCandidateCount(Integer candidateCount) {
            this.candidateCount = candidateCount;
        }
    }
}
