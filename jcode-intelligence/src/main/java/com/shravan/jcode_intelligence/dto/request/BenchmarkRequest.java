package com.shravan.jcode_intelligence.dto.request;

import jakarta.validation.constraints.Positive;
import java.util.ArrayList;
import java.util.List;

/**
 * Request payload for executing retrieval benchmarks.
 * Supports both dataset-based benchmarking (via suiteName) and custom
 * developer-provided questions.
 * <p>
 * Retrieval configuration options:
 * <ul>
 * <li>{@code DENSE_ONLY}: Evaluates vector-only dense retrieval (maps to
 * ChatMode.AUTO with RetrievalStrategy.DENSE override).</li>
 * <li>{@code SPARSE_ONLY}: Evaluates Lucene BM25 sparse retrieval (maps to
 * ChatMode.AUTO with RetrievalStrategy.SPARSE override).</li>
 * <li>{@code HYBRID_RRF}: Evaluates hybrid retrieval fusing dense and sparse
 * rankings via Reciprocal Rank Fusion (maps to ChatMode.AUTO with
 * RetrievalStrategy.HYBRID override).</li>
 * <li>{@code AUTO}: Maps to ChatMode.AUTO and uses the configured default
 * retrieval strategy (astra.retrieval.strategy).</li>
 * <li>Explicit ChatMode (e.g. {@code EXPLAIN_CLASS}, {@code SEARCH_CODE}):
 * Preserves explicit chat mode and uses the configured default retrieval
 * strategy.</li>
 * </ul>
 */
public class BenchmarkRequest {

    private String repositoryName;
    private String repositoryId;

    private String suiteName = "default";

    private List<String> questions = new ArrayList<>();
    private List<CustomBenchmarkQuestionDTO> customQuestions = new ArrayList<>();

    @Positive(message = "topK must be greater than 0")
    private int topK = 5;

    /**
     * Retrieval strategy or chat mode configuration.
     * Supported values: DENSE_ONLY, SPARSE_ONLY, HYBRID_RRF, AUTO, or any valid
     * ChatMode enum name.
     */
    private String retrievalConfiguration = "DENSE_ONLY";

    /**
     * Optional reranking strategy override (e.g. "heuristic", "cross-encoder").
     */
    private String rerankingStrategy;

    private boolean async = false;

    /**
     * Opt-in flag to evaluate generation and citation quality.
     */
    private boolean evaluateGeneration = false;

    /**
     * Repository root path on disk for deterministic citation validation.
     */
    private String sourceRoot;

    /**
     * Target source revision for citation checkout checks (overrides suite and query revision).
     */
    private String sourceRevision;

    public BenchmarkRequest() {
    }

    public BenchmarkRequest(String repositoryName) {
        this.repositoryName = repositoryName;
        this.repositoryId = repositoryName;
    }

    public BenchmarkRequest(String repositoryName, List<String> questions) {
        this.repositoryName = repositoryName;
        this.repositoryId = repositoryName;
        this.questions = (questions != null) ? questions : new ArrayList<>();
    }

    public BenchmarkRequest(String repositoryName, String suiteName, int topK, String retrievalConfiguration,
            boolean async) {
        this.repositoryName = repositoryName;
        this.repositoryId = repositoryName;
        this.suiteName = (suiteName != null && !suiteName.isBlank()) ? suiteName : "default";
        this.topK = (topK > 0) ? topK : 5;
        this.retrievalConfiguration = (retrievalConfiguration != null && !retrievalConfiguration.isBlank())
                ? retrievalConfiguration
                : "DENSE_ONLY";
        this.async = async;
    }

    /**
     * Resolves the target repository identifier preferring repositoryName over
     * repositoryId.
     */
    public String getResolvedRepositoryName() {
        if (repositoryName != null && !repositoryName.isBlank()) {
            return repositoryName.trim();
        }
        if (repositoryId != null && !repositoryId.isBlank()) {
            return repositoryId.trim();
        }
        return null;
    }

    /**
     * Checks whether this request is for custom question evaluation rather than a
     * predefined suite.
     */
    public boolean isCustomQuestionMode() {
        return (questions != null && !questions.isEmpty())
                || (customQuestions != null && !customQuestions.isEmpty());
    }

    public String getRepositoryName() {
        return repositoryName;
    }

    public void setRepositoryName(String repositoryName) {
        this.repositoryName = repositoryName;
        if (this.repositoryId == null) {
            this.repositoryId = repositoryName;
        }
    }

    public String getRepositoryId() {
        return repositoryId;
    }

    public void setRepositoryId(String repositoryId) {
        this.repositoryId = repositoryId;
        if (this.repositoryName == null) {
            this.repositoryName = repositoryId;
        }
    }

    public String getSuiteName() {
        return suiteName;
    }

    public void setSuiteName(String suiteName) {
        this.suiteName = (suiteName != null && !suiteName.isBlank()) ? suiteName : "default";
    }

    public List<String> getQuestions() {
        return questions;
    }

    public void setQuestions(List<String> questions) {
        this.questions = (questions != null) ? questions : new ArrayList<>();
    }

    public List<CustomBenchmarkQuestionDTO> getCustomQuestions() {
        return customQuestions;
    }

    public void setCustomQuestions(List<CustomBenchmarkQuestionDTO> customQuestions) {
        this.customQuestions = (customQuestions != null) ? customQuestions : new ArrayList<>();
    }

    public int getTopK() {
        return topK;
    }

    public void setTopK(int topK) {
        this.topK = topK;
    }

    public String getRetrievalConfiguration() {
        return retrievalConfiguration;
    }

    public void setRetrievalConfiguration(String retrievalConfiguration) {
        this.retrievalConfiguration = (retrievalConfiguration != null && !retrievalConfiguration.isBlank())
                ? retrievalConfiguration
                : "DENSE_ONLY";
    }

    public String getRerankingStrategy() {
        return rerankingStrategy;
    }

    public void setRerankingStrategy(String rerankingStrategy) {
        this.rerankingStrategy = (rerankingStrategy != null && !rerankingStrategy.isBlank())
                ? rerankingStrategy.trim()
                : null;
    }

    public boolean isAsync() {
        return async;
    }

    public void setAsync(boolean async) {
        this.async = async;
    }

    public boolean isEvaluateGeneration() {
        return evaluateGeneration;
    }

    public void setEvaluateGeneration(boolean evaluateGeneration) {
        this.evaluateGeneration = evaluateGeneration;
    }

    public String getSourceRoot() {
        return sourceRoot;
    }

    public void setSourceRoot(String sourceRoot) {
        this.sourceRoot = sourceRoot;
    }

    public String getSourceRevision() {
        return sourceRevision;
    }

    public void setSourceRevision(String sourceRevision) {
        this.sourceRevision = sourceRevision;
    }
}
