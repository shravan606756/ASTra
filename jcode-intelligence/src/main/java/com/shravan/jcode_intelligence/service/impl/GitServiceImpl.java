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
        try {
            log.info("Cloning repository: {} into {}", gitUrl, tempDir);
            Git.cloneRepository()
                    .setURI(gitUrl)
                    .setDirectory(tempDir.toFile())
                    .setCloneAllBranches(false)
                    .call()
                    .close();
            return tempDir;
        } catch (Exception e) {
            cleanupRepository(tempDir);
            throw new IOException("Failed to clone git repository: " + gitUrl, e);
        }
    }

    @Override
    public void cleanupRepository(Path directory) {
        if (directory != null && Files.exists(directory)) {
            try {
                FileSystemUtils.deleteRecursively(directory);
                log.info("Cleaned up temporary directory: {}", directory);
            } catch (Exception e) {
                log.error("Failed to clean up directory: {} - {}", directory, e.getMessage(), e);
            }
        }
    }
}

