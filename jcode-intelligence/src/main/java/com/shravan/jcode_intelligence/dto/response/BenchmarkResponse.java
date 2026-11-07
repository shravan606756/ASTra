package com.shravan.jcode_intelligence.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Strongly-typed response payload for retrieval evaluation benchmarks.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BenchmarkResponse {

    private String repositoryName;
    private String repositoryId;
    private String mode; // "DATASET" or "CUSTOM_QUESTIONS"
    private String suiteName;
    private String retrievalConfiguration;
    private int topK;
    private String timestamp;
    private BenchmarkSummaryDTO summary;
    private List<QueryBenchmarkResultDTO> results = new ArrayList<>();

    public BenchmarkResponse() {
        this.timestamp = Instant.now().toString();
    }

    public BenchmarkResponse(String repositoryName, String suiteName, String retrievalConfiguration,
                             int topK, BenchmarkSummaryDTO summary, List<QueryBenchmarkResultDTO> results) {
        this.repositoryName = repositoryName;
        this.repositoryId = repositoryName;
        this.suiteName = suiteName;
        this.retrievalConfiguration = retrievalConfiguration;
        this.topK = topK;
        this.timestamp = Instant.now().toString();
        this.summary = summary;
        this.results = (results != null) ? results : new ArrayList<>();
    }

    public String getRepositoryName() {
        return repositoryName != null ? repositoryName : repositoryId;
    }

    public void setRepositoryName(String repositoryName) {
        this.repositoryName = repositoryName;
        if (this.repositoryId == null) {
            this.repositoryId = repositoryName;
        }
    }

    public String getRepositoryId() {
        return repositoryId != null ? repositoryId : repositoryName;
    }

    public void setRepositoryId(String repositoryId) {
        this.repositoryId = repositoryId;
        if (this.repositoryName == null) {
            this.repositoryName = repositoryId;
        }
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getSuiteName() {
        return suiteName;
    }

    public void setSuiteName(String suiteName) {
        this.suiteName = suiteName;
    }

    public String getRetrievalConfiguration() {
        return retrievalConfiguration;
    }

    public void setRetrievalConfiguration(String retrievalConfiguration) {
        this.retrievalConfiguration = retrievalConfiguration;
    }

    public int getTopK() {
        return topK;
    }

    public void setTopK(int topK) {
        this.topK = topK;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }

    public BenchmarkSummaryDTO getSummary() {
        return summary;
    }

    public void setSummary(BenchmarkSummaryDTO summary) {
        this.summary = summary;
    }

    public List<QueryBenchmarkResultDTO> getResults() {
        return results;
    }

    public void setResults(List<QueryBenchmarkResultDTO> results) {
        this.results = results;
    }
}

