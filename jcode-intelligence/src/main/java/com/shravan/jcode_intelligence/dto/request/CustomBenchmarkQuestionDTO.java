package com.shravan.jcode_intelligence.dto.request;

import com.shravan.jcode_intelligence.model.benchmark.RelevantChunkDefinition;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a developer-provided custom benchmark question with optional
 * ground-truth relevance annotations.
 */
public class CustomBenchmarkQuestionDTO {

    private String queryId;
    private String query;
    private String category = "Custom";
    private String expectedIntent;
    private List<RelevantChunkDefinition> relevantChunks = new ArrayList<>();
    private String referenceAnswer;

    public CustomBenchmarkQuestionDTO() {
    }

    public CustomBenchmarkQuestionDTO(String query) {
        this.query = query;
    }

    public CustomBenchmarkQuestionDTO(String queryId, String query, String category,
                                      String expectedIntent, List<RelevantChunkDefinition> relevantChunks,
                                      String referenceAnswer) {
        this.queryId = queryId;
        this.query = query;
        this.category = (category != null && !category.isBlank()) ? category : "Custom";
        this.expectedIntent = expectedIntent;
        this.relevantChunks = (relevantChunks != null) ? relevantChunks : new ArrayList<>();
        this.referenceAnswer = referenceAnswer;
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

    public List<RelevantChunkDefinition> getRelevantChunks() {
        return relevantChunks;
    }

    public void setRelevantChunks(List<RelevantChunkDefinition> relevantChunks) {
        this.relevantChunks = relevantChunks;
    }

    public String getReferenceAnswer() {
        return referenceAnswer;
    }

    public void setReferenceAnswer(String referenceAnswer) {
        this.referenceAnswer = referenceAnswer;
    }
}

