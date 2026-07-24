package com.shravan.jcode_intelligence.dto.response;

public class IndexResponse {

    private String status;
    private String message;
    private int indexedChunksCount;
    private String repositoryId;

    public IndexResponse() {
    }

    public IndexResponse(String status, String message, int indexedChunksCount, String repositoryId) {
        this.status = status;
        this.message = message;
        this.indexedChunksCount = indexedChunksCount;
        this.repositoryId = repositoryId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public int getIndexedChunksCount() {
        return indexedChunksCount;
    }

    public void setIndexedChunksCount(int indexedChunksCount) {
        this.indexedChunksCount = indexedChunksCount;
    }

    public String getRepositoryId() {
        return repositoryId;
    }

    public void setRepositoryId(String repositoryId) {
        this.repositoryId = repositoryId;
    }
}

