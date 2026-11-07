package com.shravan.jcode_intelligence;

import com.shravan.jcode_intelligence.config.ChunkingConfig;
import com.shravan.jcode_intelligence.service.GitService;
import com.shravan.jcode_intelligence.service.impl.IndexingServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression and edge case tests verifying {@link IndexingServiceImpl}'s Git acquisition,
 * branch forwarding, fail-safe cleanup, and repository name parsing.
 */
public class IndexingServiceGitRegressionTest {

    private MockGitService mockGitService;
    private TestableIndexingServiceImpl indexingService;
    private Path fakeTempDir;

    @BeforeEach
    public void setUp() throws IOException {
        fakeTempDir = Files.createTempDirectory("astra-test-git-dir-");
        mockGitService = new MockGitService(fakeTempDir);
        indexingService = new TestableIndexingServiceImpl(mockGitService);
    }

    @Test
    public void indexGitRepositoryPassesBranchAndCleansUpOnSuccess() throws IOException {
        int result = indexingService.indexGitRepository(
                "https://github.com/shravan606756/ASTra.git",
                "astra-custom-id",
                "feature/v2"
        );

        assertEquals(77, result);
        assertEquals("https://github.com/shravan606756/ASTra.git", mockGitService.lastGitUrl.get());
        assertEquals("feature/v2", mockGitService.lastBranch.get());
        assertEquals("astra-custom-id", indexingService.lastIndexedRepositoryId);
        assertEquals(fakeTempDir.toString(), indexingService.lastIndexedProjectPath);

        assertTrue(mockGitService.cleanedUp.get(), "cleanupRepository must be invoked after indexing completes");
        assertEquals(fakeTempDir, mockGitService.lastCleanedUpDir.get());
    }

    @Test
    public void indexGitRepositoryGuaranteesCleanupWhenIndexProjectFails() {
        indexingService.shouldThrowOnIndexProject = true;

        IOException ex = assertThrows(IOException.class, () ->
                indexingService.indexGitRepository(
                        "https://github.com/shravan606756/ASTra.git",
                        "astra-custom-id",
                        "bugfix/branch"
                )
        );

        assertEquals("Simulated indexing failure", ex.getMessage());
        assertTrue(mockGitService.cleanedUp.get(),
                "Regression test: cleanupRepository MUST be called in finally block even on indexing failure");
        assertEquals(fakeTempDir, mockGitService.lastCleanedUpDir.get());
    }

    @Test
    public void indexGitRepositoryTwoArgDelegatesWithNullBranch() throws IOException {
        int result = indexingService.indexGitRepository(
                "https://github.com/example/lib.git",
                "lib-repo"
        );

        assertEquals(77, result);
        assertEquals("https://github.com/example/lib.git", mockGitService.lastGitUrl.get());
        assertNull(mockGitService.lastBranch.get(), "2-arg overload must pass null for branch");
        assertEquals("lib-repo", indexingService.lastIndexedRepositoryId);
        assertTrue(mockGitService.cleanedUp.get());
    }

    @Test
    public void indexGitRepositoryOneArgExtractsRepositoryName() throws IOException {
        indexingService.indexGitRepository("https://github.com/shravan606756/ASTra.git");
        assertEquals("ASTra", indexingService.lastIndexedRepositoryId);
        assertNull(mockGitService.lastBranch.get());

        indexingService.indexGitRepository("https://github.com/shravan606756/ASTra");
        assertEquals("ASTra", indexingService.lastIndexedRepositoryId);

        indexingService.indexGitRepository("project.git");
        assertEquals("project", indexingService.lastIndexedRepositoryId);

        indexingService.indexGitRepository("https://github.com/shravan606756/");
        assertEquals("git-repo", indexingService.lastIndexedRepositoryId);

        indexingService.indexGitRepository(null);
        assertEquals("git-repo", indexingService.lastIndexedRepositoryId);

        indexingService.indexGitRepository("");
        assertEquals("git-repo", indexingService.lastIndexedRepositoryId);
    }

    private static class MockGitService implements GitService {
        final Path returnDir;
        final AtomicReference<String> lastGitUrl = new AtomicReference<>();
        final AtomicReference<String> lastBranch = new AtomicReference<>();
        final AtomicReference<Path> lastCleanedUpDir = new AtomicReference<>();
        final AtomicBoolean cleanedUp = new AtomicBoolean(false);

        MockGitService(Path returnDir) {
            this.returnDir = returnDir;
        }

        @Override
        public Path cloneRepository(String gitUrl, String branch) {
            lastGitUrl.set(gitUrl);
            lastBranch.set(branch);
            return returnDir;
        }

        @Override
        public void cleanupRepository(Path directory) {
            cleanedUp.set(true);
            lastCleanedUpDir.set(directory);
            if (directory != null) {
                try {
                    Files.deleteIfExists(directory);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static class TestableIndexingServiceImpl extends IndexingServiceImpl {
        String lastIndexedProjectPath;
        String lastIndexedRepositoryId;
        boolean shouldThrowOnIndexProject = false;

        TestableIndexingServiceImpl(GitService gitService) {
            super(null, null, null, gitService, null, null, null, null, new ChunkingConfig(), null);
        }

        @Override
        public int indexProject(String projectPath, String repositoryId) throws IOException {
            this.lastIndexedProjectPath = projectPath;
            this.lastIndexedRepositoryId = repositoryId;
            if (shouldThrowOnIndexProject) {
                throw new IOException("Simulated indexing failure");
            }
            return 77;
        }
    }
}

