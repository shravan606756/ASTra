package com.shravan.jcode_intelligence.service.impl;

import com.shravan.jcode_intelligence.service.GitService;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Implementation of {@link GitService} leveraging Eclipse JGit for local repository operations.
 * <p>
 * Employs shallow cloning (depth 1, single branch, no tags) to dramatically accelerate repository
 * acquisition for AST parsing and vector indexing. Also manages repository lifecycles and fail-safe
 * cleanup on Windows and POSIX filesystems.
 */
@Service
public class GitServiceImpl implements GitService {

    private static final Logger log = LoggerFactory.getLogger(GitServiceImpl.class);

    /**
     * {@inheritDoc}
     */
    @Override
    public Path cloneRepository(String gitUrl) throws IOException {
        return cloneRepository(gitUrl, null);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Configures JGit {@link CloneCommand} with:
     * <ul>
     *   <li>{@code .setDepth(1)}: limits commit history to the latest commit</li>
     *   <li>{@code .setCloneAllBranches(false)}: restricts acquisition to the target/default branch</li>
     *   <li>{@code .setNoTags()}: avoids fetching unneeded git tags</li>
     *   <li>{@code .setBranch(selectedRef)}: targets the resolved branch or release tag</li>
     *   <li>{@code .setBranchesToClone(List.of(selectedRef))}: restricts remote fetch refspecs strictly to
     *       the selected branch or release tag, avoiding downloading unrequested branch tips</li>
     * </ul>
     * If an error occurs during cloning (e.g. nonexistent branch or unreachable remote), any
     * partially created temporary directory is cleaned up immediately before re-throwing {@link IOException}.
     */
    @Override
    public Path cloneRepository(String gitUrl, String branch) throws IOException {
        Path tempDir = Files.createTempDirectory("astra-repo-");
        return cloneRepository(gitUrl, branch, tempDir);
    }

    /**
     * Internal implementation of repository cloning into a designated target directory.
     *
     * @param gitUrl  the remote Git repository URL
     * @param branch  the target branch, tag, or {@code null}/blank for default branch
     * @param tempDir the destination directory for the clone
     * @return the destination directory
     * @throws IOException if cloning fails
     */
    public Path cloneRepository(String gitUrl, String branch, Path tempDir) throws IOException {
        long startTime = System.currentTimeMillis();
        String branchDisplay = (branch != null && !branch.isBlank()) ? branch.trim() : "DEFAULT";
        try {
            log.info("Clone Started - URL: {}, Branch: {}, Directory: {}", gitUrl, branchDisplay, tempDir);

            // Step 1: Query remote refs via lsRemote to discover HEAD and available branches/tags
            java.util.Map<String, org.eclipse.jgit.lib.Ref> remoteRefs = Git.lsRemoteRepository()
                    .setRemote(gitUrl)
                    .callAsMap();

            // Step 2: Resolve the target ref (branch or tag) to clone
            String selectedRef = resolveTargetRef(remoteRefs, branch, gitUrl);

            CloneCommand cloneCommand = Git.cloneRepository()
                    .setURI(gitUrl)
                    .setDirectory(tempDir.toFile())
                    .setDepth(1)
                    .setCloneAllBranches(false)
                    .setNoTags()
                    .setBranch(selectedRef)
                    .setBranchesToClone(java.util.Collections.singletonList(selectedRef));

            try (Git git = cloneCommand.call()) {
                // Ensure JGit repository handle is closed immediately
            }

            long duration = System.currentTimeMillis() - startTime;
            log.info("Clone Completed - URL: {}, Branch: {}, Elapsed Time: {} ms", gitUrl, branchDisplay, duration);
            return tempDir;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("Clone Failed - URL: {}, Branch: {}, Elapsed Time: {} ms, Error: {}",
                    gitUrl, branchDisplay, duration, e.getMessage());
            cleanupRepository(tempDir);
            String branchInfo = (branch != null && !branch.isBlank()) ? " (branch: " + branch.trim() + ")" : "";
            throw new IOException("Failed to clone git repository: " + gitUrl + branchInfo, e);
        }
    }

    private String resolveTargetRef(java.util.Map<String, org.eclipse.jgit.lib.Ref> remoteRefs,
                                    String branch,
                                    String gitUrl) throws IOException {
        if (remoteRefs == null || remoteRefs.isEmpty()) {
            throw new IOException("Remote repository has no refs available: " + gitUrl);
        }

        if (branch == null || branch.isBlank()) {
            // Default branch request: resolve remote HEAD
            org.eclipse.jgit.lib.Ref headRef = remoteRefs.get("HEAD");
            if (headRef != null) {
                if (headRef.isSymbolic()) {
                    return headRef.getTarget().getName();
                }
                // If HEAD is direct ObjectId, find the matching branch ref
                for (java.util.Map.Entry<String, org.eclipse.jgit.lib.Ref> entry : remoteRefs.entrySet()) {
                    if (entry.getKey().startsWith("refs/heads/")
                            && entry.getValue().getObjectId() != null
                            && entry.getValue().getObjectId().equals(headRef.getObjectId())) {
                        return entry.getKey();
                    }
                }
            }
            return "refs/heads/main";
        }

        String trimmed = branch.trim();

        // Check if already a full ref name
        if (remoteRefs.containsKey(trimmed)) {
            return trimmed;
        }

        // Check branch: refs/heads/<trimmed>
        String branchRef = "refs/heads/" + trimmed;
        if (remoteRefs.containsKey(branchRef)) {
            return branchRef;
        }

        // Check release tag: refs/tags/<trimmed>
        String tagRef = "refs/tags/" + trimmed;
        if (remoteRefs.containsKey(tagRef)) {
            return tagRef;
        }

        // If not found in lsRemote, return branchRef so JGit triggers appropriate ref-not-found error
        return branchRef;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Performs a recursive walk across the target directory, marking each file writable before
     * deletion to avoid access violations on read-only git index / pack files (especially on Windows).
     */
    @Override
    public void cleanupRepository(Path directory) {
        if (directory != null && Files.exists(directory)) {
            String repoName = directory.getFileName() != null ? directory.getFileName().toString() : "unknown";
            log.info("\n=========================================================\n" +
                     "Git Repository Cleanup\n\n" +
                     "Repository Id : {}\n" +
                     "Directory     : {}\n\n" +
                     "Cleanup Started...", repoName, directory);
            
            long startTime = System.currentTimeMillis();
            try {
                java.nio.file.Files.walkFileTree(directory, new java.nio.file.SimpleFileVisitor<Path>() {
                    @Override
                    public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) throws java.io.IOException {
                        file.toFile().setWritable(true);
                        java.nio.file.Files.delete(file);
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }
                    @Override
                    public java.nio.file.FileVisitResult postVisitDirectory(Path dir, java.io.IOException exc) throws java.io.IOException {
                        dir.toFile().setWritable(true);
                        java.nio.file.Files.delete(dir);
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }
                });
                long duration = System.currentTimeMillis() - startTime;
                
                log.info("\nCleanup Completed\n\n" +
                         "Elapsed Time : {} ms\n" +
                         "Status       : SUCCESS\n" +
                         "=========================================================", duration);
            } catch (Exception e) {
                long duration = System.currentTimeMillis() - startTime;
                
                String exceptionName = e.getClass().getSimpleName();
                String lockedFile = e.getMessage(); // In AccessDeniedException, message is often the locked file
                
                log.error("\n=========================================================\n" +
                          "Git Repository Cleanup\n\n" +
                          "Status : FAILED\n\n" +
                          "Exception : {}\n\n" +
                          "Locked File:\n" +
                          "{}\n\n" +
                          "Elapsed Time : {} ms\n" +
                          "=========================================================", exceptionName, lockedFile, duration);
            }
        }
    }
}

