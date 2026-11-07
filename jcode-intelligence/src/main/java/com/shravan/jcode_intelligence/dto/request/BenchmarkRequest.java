package com.shravan.jcode_intelligence.dto.request;

import jakarta.validation.constraints.Positive;
import java.util.ArrayList;
import java.util.List;

/**
 * Request payload for executing retrieval benchmarks.
 * Supports both dataset-based benchmarking (via suiteName) and custom
 * developer-provided questions.
 */
public class BenchmarkRequest {

    private String repositoryName;
    private String repositoryId;

    private String suiteName = "default";

    private List<String> questions = new ArrayList<>();
    private List<CustomBenchmarkQuestionDTO> customQuestions = new ArrayList<>();

    @Positive(message = "topK must be greater than 0")
    private int topK = 5;

    private String retrievalConfiguration = "DENSE_ONLY";

    private boolean async = false;

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

    public BenchmarkRequest(String repositoryName, String suiteName, int topK, String retrievalConfiguration, boolean async) {
        this.repositoryName = repositoryName;
        this.repositoryId = repositoryName;
        this.suiteName = (suiteName != null && !suiteName.isBlank()) ? suiteName : "default";
        this.topK = (topK > 0) ? topK : 5;
        this.retrievalConfiguration = (retrievalConfiguration != null && !retrievalConfiguration.isBlank())
                ? retrievalConfiguration : "DENSE_ONLY";
        this.async = async;
    }

    /**
     * Resolves the target repository identifier preferring repositoryName over repositoryId.
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
     * Checks whether this request is for custom question evaluation rather than a predefined suite.
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
                ? retrievalConfiguration : "DENSE_ONLY";
    }

    public boolean isAsync() {
        return async;
    }

    public void setAsync(boolean async) {
        this.async = async;
    }
}
