package com.shravan.jcode_intelligence.model.benchmark;

import java.util.ArrayList;
import java.util.List;

/**
 * Top-level container representing an externalized benchmark evaluation suite.
 */
public class BenchmarkSuite {

    private String suiteName;
    private String description;
    private String version = "1.0.0";
    private String defaultRepositoryId;
    private List<BenchmarkQueryDefinition> queries = new ArrayList<>();

    public BenchmarkSuite() {
    }

    public BenchmarkSuite(String suiteName, String description, String version,
                          String defaultRepositoryId, List<BenchmarkQueryDefinition> queries) {
        this.suiteName = suiteName;
        this.description = description;
        this.version = version;
        this.defaultRepositoryId = defaultRepositoryId;
        this.queries = (queries != null) ? queries : new ArrayList<>();
    }

    public String getSuiteName() {
        return suiteName;
    }

    public void setSuiteName(String suiteName) {
        this.suiteName = suiteName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getDefaultRepositoryId() {
        return defaultRepositoryId;
    }

    public void setDefaultRepositoryId(String defaultRepositoryId) {
        this.defaultRepositoryId = defaultRepositoryId;
    }

    public List<BenchmarkQueryDefinition> getQueries() {
        return queries;
    }

    public void setQueries(List<BenchmarkQueryDefinition> queries) {
        this.queries = queries;
    }
}

