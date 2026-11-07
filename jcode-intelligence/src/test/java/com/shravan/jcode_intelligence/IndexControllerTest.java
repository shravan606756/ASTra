package com.shravan.jcode_intelligence;

import com.shravan.jcode_intelligence.controller.IndexController;
import com.shravan.jcode_intelligence.dto.request.IndexRequest;
import com.shravan.jcode_intelligence.dto.response.IndexResponse;
import com.shravan.jcode_intelligence.service.IndexingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests verifying {@link IndexController} request validation, branch passing, and routing edge cases.
 */
public class IndexControllerTest {

    private MockIndexingService mockIndexingService;
    private IndexController controller;

    @BeforeEach
    public void setUp() {
        mockIndexingService = new MockIndexingService();
        controller = new IndexController(mockIndexingService);
    }

    @Test
    public void indexGitWithNullGitUrlReturns400() throws IOException {
        IndexRequest request = new IndexRequest();
        request.setGitUrl(null);

        ResponseEntity<IndexResponse> response = controller.indexGit(request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("ERROR", response.getBody().getStatus());
        assertTrue(response.getBody().getMessage().contains("gitUrl must be provided"));
    }

    @Test
    public void indexGitWithBlankGitUrlReturns400() throws IOException {
        IndexRequest request = new IndexRequest();
        request.setGitUrl("   ");

        ResponseEntity<IndexResponse> response = controller.indexGit(request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("ERROR", response.getBody().getStatus());
        assertTrue(response.getBody().getMessage().contains("gitUrl must be provided"));
    }

    @Test
    public void indexGitWithValidUrlAndBranchPassesBranchToService() throws IOException {
        IndexRequest request = new IndexRequest(
                null,
                "https://github.com/example/repo.git",
                "custom-repo-id",
                "feature/shallow-test"
        );
        mockIndexingService.stubbedCount = 42;

        ResponseEntity<IndexResponse> response = controller.indexGit(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("SUCCESS", response.getBody().getStatus());
        assertEquals(42, response.getBody().getIndexedChunksCount());
        assertEquals("custom-repo-id", response.getBody().getRepositoryId());

        assertEquals("https://github.com/example/repo.git", mockIndexingService.lastGitUrl.get());
        assertEquals("custom-repo-id", mockIndexingService.lastRepositoryId.get());
        assertEquals("feature/shallow-test", mockIndexingService.lastBranch.get());
    }

    @Test
    public void indexLocalWithNullProjectPathReturns400() throws IOException {
        IndexRequest request = new IndexRequest();
        request.setProjectPath(null);

        ResponseEntity<IndexResponse> response = controller.indexLocal(request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("ERROR", response.getBody().getStatus());
    }

    @Test
    public void indexLocalWithBlankProjectPathReturns400() throws IOException {
        IndexRequest request = new IndexRequest();
        request.setProjectPath("  \t ");

        ResponseEntity<IndexResponse> response = controller.indexLocal(request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("ERROR", response.getBody().getStatus());
    }

    @Test
    public void indexLocalWithValidPathCallsService() throws IOException {
        IndexRequest request = new IndexRequest("/path/to/src", null, "my-repo");
        mockIndexingService.stubbedCount = 15;

        ResponseEntity<IndexResponse> response = controller.indexLocal(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("SUCCESS", response.getBody().getStatus());
        assertEquals(15, response.getBody().getIndexedChunksCount());
        assertEquals("/path/to/src", mockIndexingService.lastProjectPath.get());
        assertEquals("my-repo", mockIndexingService.lastRepositoryId.get());
    }

    @Test
    public void indexUnifiedRoutesToGitWhenGitUrlPresent() throws IOException {
        IndexRequest request = new IndexRequest(
                "/some/local/path",
                "https://github.com/example/repo.git",
                "repo-id",
                "v2.0"
        );
        mockIndexingService.stubbedCount = 100;

        ResponseEntity<IndexResponse> response = controller.indexUnified(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(mockIndexingService.gitIndexed.get(), "Should route to git indexing when gitUrl is present");
        assertEquals("v2.0", mockIndexingService.lastBranch.get());
    }

    @Test
    public void indexUnifiedRoutesToLocalWhenProjectPathPresent() throws IOException {
        IndexRequest request = new IndexRequest("/some/local/path", null, "repo-id");
        mockIndexingService.stubbedCount = 20;

        ResponseEntity<IndexResponse> response = controller.indexUnified(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(mockIndexingService.localIndexed.get(), "Should route to local indexing when only projectPath is present");
    }

    @Test
    public void indexUnifiedReturns400WhenNeitherPresent() throws IOException {
        IndexRequest request = new IndexRequest();

        ResponseEntity<IndexResponse> response = controller.indexUnified(request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("ERROR", response.getBody().getStatus());
        assertTrue(response.getBody().getMessage().contains("Either projectPath or gitUrl must be provided"));
    }

    private static class MockIndexingService implements IndexingService {
        int stubbedCount = 10;
        final AtomicReference<String> lastProjectPath = new AtomicReference<>();
        final AtomicReference<String> lastGitUrl = new AtomicReference<>();
        final AtomicReference<String> lastRepositoryId = new AtomicReference<>();
        final AtomicReference<String> lastBranch = new AtomicReference<>();
        final AtomicBoolean gitIndexed = new AtomicBoolean(false);
        final AtomicBoolean localIndexed = new AtomicBoolean(false);

        @Override
        public int indexProject(String projectPath) {
            return indexProject(projectPath, "default");
        }

        @Override
        public int indexProject(String projectPath, String repositoryId) {
            localIndexed.set(true);
            lastProjectPath.set(projectPath);
            lastRepositoryId.set(repositoryId);
            return stubbedCount;
        }

        @Override
        public int indexGitRepository(String gitUrl) {
            return indexGitRepository(gitUrl, "default", null);
        }

        @Override
        public int indexGitRepository(String gitUrl, String repositoryId, String branch) {
            gitIndexed.set(true);
            lastGitUrl.set(gitUrl);
            lastRepositoryId.set(repositoryId);
            lastBranch.set(branch);
            return stubbedCount;
        }
    }
}

