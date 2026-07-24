package com.shravan.jcode_intelligence.dto.request;

public class IndexRequest {

    private String projectPath;
    private String gitUrl;
    private String repositoryId;

    public IndexRequest() {
    }

    public IndexRequest(String projectPath, String gitUrl, String repositoryId) {
        this.projectPath = projectPath;
        this.gitUrl = gitUrl;
        this.repositoryId = repositoryId;
    }

    public String getProjectPath() {
        return projectPath;
    }

    public void setProjectPath(String projectPath) {
        this.projectPath = projectPath;
    }

    public String getGitUrl() {
        return gitUrl;
    }

    public void setGitUrl(String gitUrl) {
        this.gitUrl = gitUrl;
    }

    public String getRepositoryId() {
        return repositoryId;
    }

    public void setRepositoryId(String repositoryId) {
        this.repositoryId = repositoryId;
    }
}

