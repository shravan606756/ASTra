package com.shravan.jcode_intelligence.dto.request;

/**
 * Data Transfer Object representing an indexing request for local projects or remote Git repositories.
 */
public class IndexRequest {

    /**
     * Absolute or relative path to a local directory containing Java source files.
     */
    private String projectPath;

    /**
     * Remote Git repository URL (e.g. HTTPS or file URI).
     */
    private String gitUrl;

    /**
     * Optional custom identifier used to group and scope vector records.
     */
    private String repositoryId;

    /**
     * Optional branch or tag to clone when indexing a Git repository.
     * When omitted or null, the remote's default branch (HEAD) is cloned.
     */
    private String branch;

    /**
     * Default no-args constructor for JSON deserialization.
     */
    public IndexRequest() {
    }

    /**
     * Constructs an {@link IndexRequest} without an explicit branch specification.
     *
     * @param projectPath  path to local project directory
     * @param gitUrl       remote Git repository URL
     * @param repositoryId unique repository identifier
     */
    public IndexRequest(String projectPath, String gitUrl, String repositoryId) {
        this(projectPath, gitUrl, repositoryId, null);
    }

    /**
     * Constructs an {@link IndexRequest} with all parameters including branch.
     *
     * @param projectPath  path to local project directory
     * @param gitUrl       remote Git repository URL
     * @param repositoryId unique repository identifier
     * @param branch       target branch or ref name to clone
     */
    public IndexRequest(String projectPath, String gitUrl, String repositoryId, String branch) {
        this.projectPath = projectPath;
        this.gitUrl = gitUrl;
        this.repositoryId = repositoryId;
        this.branch = branch;
    }

    /**
     * Returns the local project path.
     *
     * @return the local project path
     */
    public String getProjectPath() {
        return projectPath;
    }

    /**
     * Sets the local project path.
     *
     * @param projectPath the local project path
     */
    public void setProjectPath(String projectPath) {
        this.projectPath = projectPath;
    }

    /**
     * Returns the remote Git repository URL.
     *
     * @return the Git URL
     */
    public String getGitUrl() {
        return gitUrl;
    }

    /**
     * Sets the remote Git repository URL.
     *
     * @param gitUrl the Git URL
     */
    public void setGitUrl(String gitUrl) {
        this.gitUrl = gitUrl;
    }

    /**
     * Returns the unique repository identifier.
     *
     * @return the repository ID
     */
    public String getRepositoryId() {
        return repositoryId;
    }

    /**
     * Sets the unique repository identifier.
     *
     * @param repositoryId the repository ID
     */
    public void setRepositoryId(String repositoryId) {
        this.repositoryId = repositoryId;
    }

    /**
     * Returns the target Git branch or ref name.
     *
     * @return the target branch name, or {@code null} if default branch
     */
    public String getBranch() {
        return branch;
    }

    /**
     * Sets the target Git branch or ref name.
     *
     * @param branch the branch name to clone
     */
    public void setBranch(String branch) {
        this.branch = branch;
    }
}

