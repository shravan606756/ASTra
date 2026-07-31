package com.shravan.jcode_intelligence.service.impl;

import com.shravan.jcode_intelligence.service.GitService;
import org.eclipse.jgit.api.Git;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Service
public class GitServiceImpl implements GitService {

    private static final Logger log = LoggerFactory.getLogger(GitServiceImpl.class);

    @Override
    public Path cloneRepository(String gitUrl) throws IOException {
        Path tempDir = Files.createTempDirectory("astra-repo-");
        long startTime = System.currentTimeMillis();
        try {
            log.info("Clone Started - URL: {}, Directory: {}", gitUrl, tempDir);
            Git.cloneRepository()
                    .setURI(gitUrl)
                    .setDirectory(tempDir.toFile())
                    .setCloneAllBranches(false)
                    .call()
                    .close();
            
            long duration = System.currentTimeMillis() - startTime;
            log.info("Clone Completed - URL: {}, Elapsed Time: {} ms", gitUrl, duration);
            return tempDir;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("Clone Failed - URL: {}, Elapsed Time: {} ms, Error: {}", gitUrl, duration, e.getMessage());
            cleanupRepository(tempDir);
            throw new IOException("Failed to clone git repository: " + gitUrl, e);
        }
    }

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

