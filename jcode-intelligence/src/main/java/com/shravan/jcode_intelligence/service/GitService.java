package com.shravan.jcode_intelligence.service;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Service responsible for Git repository acquisition and lifecycle cleanup.
 * <p>
 * Implementations use shallow cloning (depth 1, single branch, no tags) to minimize
 * network transfer overhead, disk consumption, and acquisition latency during indexing.
 */
public interface GitService {

    /**
     * Performs a shallow clone of the remote repository's default branch (depth 1).
     *
     * @param gitUrl the remote Git repository URL (HTTPS or file URI)
     * @return the local {@link Path} to the temporary clone directory
     * @throws IOException if cloning fails or the repository cannot be accessed
     */
    default Path cloneRepository(String gitUrl) throws IOException {
        return cloneRepository(gitUrl, null);
    }

    /**
     * Performs a shallow clone (depth 1, single branch, no tags) of the remote repository
     * at the specified branch. If {@code branch} is null, empty, or blank, JGit clones the
     * remote's default branch (HEAD).
     *
     * @param gitUrl the remote Git repository URL (HTTPS or file URI)
     * @param branch the specific branch or ref to clone, or {@code null}/blank for default branch
     * @return the local {@link Path} to the temporary clone directory
     * @throws IOException if cloning fails (e.g. invalid URL, nonexistent branch)
     */
    Path cloneRepository(String gitUrl, String branch) throws IOException;

    /**
     * Recursively and safely removes the specified repository directory from disk,
     * ensuring read-only attributes are cleared before file deletion.
     *
     * @param directory the path to the repository directory to clean up
     */
    void cleanupRepository(Path directory);
}

