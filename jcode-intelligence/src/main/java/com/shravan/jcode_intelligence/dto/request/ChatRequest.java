package com.shravan.jcode_intelligence.dto.request;

import jakarta.validation.constraints.NotBlank;

public class ChatRequest {

    @NotBlank(message = "Query must not be blank")
    private String query;

    private Integer topK;

    private String repositoryId;

    private ChatMode mode;

    public ChatRequest() {
    }

    public ChatRequest(String query, Integer topK, String repositoryId) {
        this.query = query;
        this.topK = topK;
        this.repositoryId = repositoryId;
    }

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public Integer getTopK() {
        return topK != null ? topK : 5;
    }

    public void setTopK(Integer topK) {
        this.topK = topK;
    }

    public String getRepositoryId() {
        return repositoryId;
    }

    public void setRepositoryId(String repositoryId) {
        this.repositoryId = repositoryId;
    }

    public ChatMode getMode() {
        return mode != null ? mode : ChatMode.AUTO;
    }

    public void setMode(ChatMode mode) {
        this.mode = mode;
    }
}

