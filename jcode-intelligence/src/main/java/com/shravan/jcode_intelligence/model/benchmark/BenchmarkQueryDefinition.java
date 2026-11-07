package com.shravan.jcode_intelligence.model.benchmark;

import java.util.ArrayList;
import java.util.List;

/**
 * Definition of a single ground-truth query in a benchmark suite.
 */
public class BenchmarkQueryDefinition {

    private String queryId;
    private String query;
    private String category;
    private String repositoryId;
    private String expectedIntent;
    private List<RelevantChunkDefinition> relevantChunks = new ArrayList<>();
    private String referenceAnswer;
    private List<String> expectedCitations = new ArrayList<>();

    public BenchmarkQueryDefinition() {
    }

    public BenchmarkQueryDefinition(String queryId, String query, String category, String repositoryId,
                                   String expectedIntent, List<RelevantChunkDefinition> relevantChunks,
                                   String referenceAnswer, List<String> expectedCitations) {
        this.queryId = queryId;
        this.query = query;
        this.category = category;
        this.repositoryId = repositoryId;
        this.expectedIntent = expectedIntent;
        this.relevantChunks = (relevantChunks != null) ? relevantChunks : new ArrayList<>();
        this.referenceAnswer = referenceAnswer;
        this.expectedCitations = (expectedCitations != null) ? expectedCitations : new ArrayList<>();
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

    public String getRepositoryId() {
        return repositoryId;
    }

    public void setRepositoryId(String repositoryId) {
        this.repositoryId = repositoryId;
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

    public List<String> getExpectedCitations() {
        return expectedCitations;
    }

    public void setExpectedCitations(List<String> expectedCitations) {
        this.expectedCitations = expectedCitations;
    }
}

