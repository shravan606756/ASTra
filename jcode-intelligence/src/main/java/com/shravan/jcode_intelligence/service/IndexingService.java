package com.shravan.jcode_intelligence.service;

import java.io.IOException;

/**
 * Service orchestrating the AST parsing, package summarization, chunking, embedding generation,
 * and vector store ingestion of Java codebases from local filesystems or remote Git repositories.
 */
public interface IndexingService {

    /**
     * Parses and indexes a local Java project using its folder name as the default repository ID.
     *
     * @param projectPath filesystem path to the project root
     * @return total count of indexed document chunks
     * @throws IOException if source files cannot be read
     */
    int indexProject(String projectPath) throws IOException;

    /**
     * Parses and indexes a local Java project under the specified repository ID.
     *
     * @param projectPath  filesystem path to the project root
     * @param repositoryId identifier to associate with the generated chunks and vectors
     * @return total count of indexed document chunks
     * @throws IOException if source files cannot be read
     */
    int indexProject(String projectPath, String repositoryId) throws IOException;

    /**
     * Acquires a remote Git repository via shallow clone (default branch) and indexes its source files.
     *
     * @param gitUrl the remote Git repository URL
     * @return total count of indexed document chunks
     * @throws IOException if clone or parsing fails
     */
    int indexGitRepository(String gitUrl) throws IOException;

    /**
     * Acquires a remote Git repository via shallow clone on its default branch and indexes it
     * under the specified repository ID.
     *
     * @param gitUrl       the remote Git repository URL
     * @param repositoryId identifier to associate with the generated vectors
     * @return total count of indexed document chunks
     * @throws IOException if clone or parsing fails
     */
    default int indexGitRepository(String gitUrl, String repositoryId) throws IOException {
        return indexGitRepository(gitUrl, repositoryId, null);
    }

    /**
     * Acquires a remote Git repository via shallow clone (depth 1) at the specified branch
     * and indexes it under the specified repository ID.
     *
     * @param gitUrl       the remote Git repository URL
     * @param repositoryId identifier to associate with the generated vectors
     * @param branch       target branch or ref name to clone (or null/blank for default branch)
     * @return total count of indexed document chunks
     * @throws IOException if clone or parsing fails
     */
    int indexGitRepository(String gitUrl, String repositoryId, String branch) throws IOException;
}

