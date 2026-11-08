package com.shravan.jcode_intelligence.service;

import com.shravan.jcode_intelligence.config.ChunkingConfig;
import com.shravan.jcode_intelligence.config.EmbeddingQueueConfig;
import com.shravan.jcode_intelligence.converter.DocumentConverter;
import com.shravan.jcode_intelligence.exception.IndexingConflictException;
import com.shravan.jcode_intelligence.model.CodeChunk;
import com.shravan.jcode_intelligence.model.EmbeddedRecord;
import com.shravan.jcode_intelligence.model.IndexingStatistics;
import com.shravan.jcode_intelligence.parser.EmbeddingBudgetValidator;
import com.shravan.jcode_intelligence.parser.IndexingStatisticsCalculator;
import com.shravan.jcode_intelligence.parser.JavaProjectParser;
import com.shravan.jcode_intelligence.parser.PackageSummaryGenerator;
import com.shravan.jcode_intelligence.service.impl.IndexingServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests verifying repository concurrency isolation, conflict detection, and
 * embedding queue staging in {@link IndexingServiceImpl}.
 */
class IndexingServiceConcurrencyAndQueueTest {

    private JavaProjectParser mockParser;
    private DocumentConverter mockConverter;
    private VectorStore mockVectorStore;
    private GitService mockGitService;
    private JdbcTemplate mockJdbcTemplate;
    private PackageSummaryGenerator mockSummaryGenerator;
    private IndexingStatisticsCalculator mockStatsCalculator;
    private EmbeddingBudgetValidator mockBudgetValidator;
    private ChunkingConfig chunkingConfig;
    private RepositoryManagementService mockRepoMgmtService;
    private RepositoryConcurrencyManager concurrencyManager;
    private EmbeddingQueue embeddingQueue;
    private EmbeddingModel mockEmbeddingModel;
    private ObjectProvider<EmbeddingModel> mockModelProvider;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws IOException {
        mockParser = mock(JavaProjectParser.class);
        mockConverter = mock(DocumentConverter.class);
        mockVectorStore = mock(VectorStore.class);
        mockGitService = mock(GitService.class);
        mockJdbcTemplate = mock(JdbcTemplate.class);
        mockSummaryGenerator = mock(PackageSummaryGenerator.class);
        mockStatsCalculator = mock(IndexingStatisticsCalculator.class);
        mockBudgetValidator = mock(EmbeddingBudgetValidator.class);
        chunkingConfig = new ChunkingConfig();
        mockRepoMgmtService = mock(RepositoryManagementService.class);
        concurrencyManager = new RepositoryConcurrencyManager();
        embeddingQueue = new EmbeddingQueue(new EmbeddingQueueConfig(100, 20, 5, 50, 1, 2, 10));
        mockEmbeddingModel = mock(EmbeddingModel.class);
        mockModelProvider = mock(ObjectProvider.class);
        when(mockModelProvider.getIfAvailable()).thenReturn(mockEmbeddingModel);

        when(mockParser.parse(any(Path.class))).thenReturn(List.of());
        when(mockSummaryGenerator.generatePackageSummaries(any())).thenReturn(List.of());
        when(mockConverter.convert(anyList())).thenReturn(List.of());
        when(mockStatsCalculator.calculate(any(), anyLong())).thenReturn(mock(IndexingStatistics.class));
    }

    @Test
    @DisplayName("indexProject acquires and releases repository lock during indexing")
    void testIndexProjectAcquiresAndReleasesLock() throws Exception {
        IndexingServiceImpl indexingService = new IndexingServiceImpl(
                mockParser, mockConverter, mockVectorStore, mockGitService,
                mockJdbcTemplate, mockSummaryGenerator, mockStatsCalculator,
                mockBudgetValidator, chunkingConfig, mockRepoMgmtService,
                concurrencyManager, embeddingQueue, mockModelProvider);

        String repoId = "test-repo-lock";
        assertFalse(concurrencyManager.isLocked(repoId));

        int result = indexingService.indexProject(".", repoId);

        assertEquals(0, result);
        assertFalse(concurrencyManager.isLocked(repoId), "Lock must be released after indexing completes");
    }

    @Test
    @DisplayName("Concurrent indexProject calls for the same repository throw IndexingConflictException")
    void testConcurrentCallsForSameRepositoryThrowConflict() throws Exception {
        String repoId = "busy-repo";
        assertTrue(concurrencyManager.tryAcquire(repoId));

        IndexingServiceImpl indexingService = new IndexingServiceImpl(
                mockParser, mockConverter, mockVectorStore, mockGitService,
                mockJdbcTemplate, mockSummaryGenerator, mockStatsCalculator,
                mockBudgetValidator, chunkingConfig, mockRepoMgmtService,
                concurrencyManager, embeddingQueue, mockModelProvider);

        try {
            assertThrows(IndexingConflictException.class, () ->
                    indexingService.indexProject(".", repoId),
                    "Must reject concurrent indexing on the same repository");
        } finally {
            concurrencyManager.release(repoId);
        }
    }

    @Test
    @DisplayName("Concurrent indexProject calls from a different thread throw IndexingConflictException")
    void testConcurrentCallsFromDifferentThreadThrowConflict() throws Exception {
        String repoId = "thread-busy-repo";
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            // Acquire lock from a background thread
            java.util.concurrent.Future<Boolean> lockedFuture = executor.submit(() -> concurrencyManager.tryAcquire(repoId));
            assertTrue(lockedFuture.get(1, java.util.concurrent.TimeUnit.SECONDS));

            IndexingServiceImpl indexingService = new IndexingServiceImpl(
                    mockParser, mockConverter, mockVectorStore, mockGitService,
                    mockJdbcTemplate, mockSummaryGenerator, mockStatsCalculator,
                    mockBudgetValidator, chunkingConfig, mockRepoMgmtService,
                    concurrencyManager, embeddingQueue, mockModelProvider);

            assertThrows(IndexingConflictException.class, () ->
                    indexingService.indexProject(".", repoId),
                    "Must reject concurrent indexing when lock is held by another thread");
        } finally {
            executor.submit(() -> concurrencyManager.release(repoId)).get(1, java.util.concurrent.TimeUnit.SECONDS);
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("indexProject executes parallel batch embedding and queue staging")
    void testIndexProjectParallelBatchEmbeddingAndQueueStaging() throws Exception {
        CodeChunk chunk1 = new CodeChunk();
        chunk1.setId("c1");
        chunk1.setType("METHOD");
        chunk1.setContent("code1");
        when(mockParser.parse(any(Path.class))).thenReturn(List.of(chunk1));

        Document doc1 = new Document("code1", Map.of("chunkId", "c1"));
        when(mockConverter.convert(anyList())).thenReturn(List.of(doc1));

        when(mockEmbeddingModel.embed(anyList())).thenReturn(List.of(new float[]{0.1f}));

        java.util.concurrent.ScheduledExecutorService testWriter = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        testWriter.schedule(() -> {
            try {
                List<EmbeddedRecord> drained = embeddingQueue.drainBatch(10, 500);
                for (EmbeddedRecord r : drained) {
                    embeddingQueue.recordPersisted(r);
                }
            } catch (Exception ignored) {
            }
        }, 50, java.util.concurrent.TimeUnit.MILLISECONDS);

        IndexingServiceImpl indexingService = new IndexingServiceImpl(
                mockParser, mockConverter, mockVectorStore, mockGitService,
                mockJdbcTemplate, mockSummaryGenerator, mockStatsCalculator,
                mockBudgetValidator, chunkingConfig, mockRepoMgmtService,
                concurrencyManager, embeddingQueue, mockModelProvider);

        try {
            int result = indexingService.indexProject(".", "repo-batch-test");
            assertEquals(1, result);
        } finally {
            testWriter.shutdownNow();
        }
    }
}

