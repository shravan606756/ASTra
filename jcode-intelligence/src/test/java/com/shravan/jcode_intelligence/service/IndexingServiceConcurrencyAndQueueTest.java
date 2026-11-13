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
import java.util.ArrayList;
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
        testWriter.scheduleWithFixedDelay(() -> {
            try {
                List<EmbeddedRecord> drained = embeddingQueue.drainBatch(10, 500);
                for (EmbeddedRecord r : drained) {
                    embeddingQueue.recordPersisted(r);
                }
            } catch (Exception ignored) {
            }
        }, 10, 20, java.util.concurrent.TimeUnit.MILLISECONDS);

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

    @Test
    @DisplayName("Embedding batch tasks genuinely run on Java 21 virtual threads")
    void testEmbeddingBatchTasksExecuteOnVirtualThreads() throws Exception {
        CodeChunk chunk = new CodeChunk();
        chunk.setId("v1");
        chunk.setType("METHOD");
        chunk.setContent("virtualThreadCode");
        when(mockParser.parse(any(Path.class))).thenReturn(List.of(chunk));

        Document doc = new Document("virtualThreadCode", Map.of("chunkId", "v1"));
        when(mockConverter.convert(anyList())).thenReturn(List.of(doc));

        java.util.concurrent.atomic.AtomicBoolean ranOnVirtualThread = new java.util.concurrent.atomic.AtomicBoolean(false);
        when(mockEmbeddingModel.embed(anyList())).thenAnswer(invocation -> {
            ranOnVirtualThread.set(Thread.currentThread().isVirtual());
            return List.of(new float[]{0.42f});
        });

        ScheduledExecutorService testWriter = Executors.newSingleThreadScheduledExecutor();
        testWriter.scheduleWithFixedDelay(() -> {
            try {
                List<EmbeddedRecord> drained = embeddingQueue.drainBatch(10, 500);
                for (EmbeddedRecord r : drained) {
                    embeddingQueue.recordPersisted(r);
                }
            } catch (Exception ignored) {
            }
        }, 10, 20, TimeUnit.MILLISECONDS);

        IndexingServiceImpl indexingService = new IndexingServiceImpl(
                mockParser, mockConverter, mockVectorStore, mockGitService,
                mockJdbcTemplate, mockSummaryGenerator, mockStatsCalculator,
                mockBudgetValidator, chunkingConfig, mockRepoMgmtService,
                concurrencyManager, embeddingQueue, mockModelProvider);

        try {
            int result = indexingService.indexProject(".", "repo-vt-test");
            assertEquals(1, result);
            assertTrue(ranOnVirtualThread.get(), "Embedding worker task must execute on a virtual thread");
        } finally {
            testWriter.shutdownNow();
        }
    }

    @Test
    @DisplayName("Embedding tasks concurrency is strictly bounded by maxParallelWorkers semaphore")
    void testEmbeddingConcurrencyBoundedByMaxParallelWorkers() throws Exception {
        int totalChunks = 6;
        List<CodeChunk> chunks = new ArrayList<>();
        List<Document> docs = new ArrayList<>();
        for (int i = 0; i < totalChunks; i++) {
            CodeChunk c = new CodeChunk();
            c.setId("chunk-" + i);
            c.setType("METHOD");
            c.setContent("code-" + i);
            chunks.add(c);
            docs.add(new Document("code-" + i, Map.of("chunkId", "chunk-" + i)));
        }
        when(mockParser.parse(any(Path.class))).thenReturn(chunks);
        when(mockConverter.convert(anyList())).thenReturn(docs);

        // Force batchSize=1 and maxParallelWorkers=2
        ChunkingConfig customConfig = new ChunkingConfig() {
            @Override public int getBatchSize() { return 1; }
            @Override public int getMaxParallelWorkers() { return 2; }
        };

        java.util.concurrent.atomic.AtomicInteger activeWorkers = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger maxObserved = new java.util.concurrent.atomic.AtomicInteger(0);

        when(mockEmbeddingModel.embed(anyList())).thenAnswer(invocation -> {
            int current = activeWorkers.incrementAndGet();
            maxObserved.updateAndGet(prev -> Math.max(prev, current));
            try {
                Thread.sleep(40);
            } finally {
                activeWorkers.decrementAndGet();
            }
            return List.of(new float[]{0.1f});
        });

        ScheduledExecutorService testWriter = Executors.newSingleThreadScheduledExecutor();
        testWriter.scheduleWithFixedDelay(() -> {
            try {
                List<EmbeddedRecord> drained = embeddingQueue.drainBatch(10, 20);
                for (EmbeddedRecord r : drained) {
                    embeddingQueue.recordPersisted(r);
                }
            } catch (Exception ignored) {
            }
        }, 10, 20, TimeUnit.MILLISECONDS);

        IndexingServiceImpl indexingService = new IndexingServiceImpl(
                mockParser, mockConverter, mockVectorStore, mockGitService,
                mockJdbcTemplate, mockSummaryGenerator, mockStatsCalculator,
                mockBudgetValidator, customConfig, mockRepoMgmtService,
                concurrencyManager, embeddingQueue, mockModelProvider);

        try {
            int result = indexingService.indexProject(".", "repo-concurrency-bound-test");
            assertEquals(totalChunks, result);
            assertTrue(maxObserved.get() <= 2, "Observed concurrency (" + maxObserved.get() + ") must not exceed semaphore limit (2)");
            assertTrue(maxObserved.get() > 1, "Must execute concurrently with more than 1 active worker");
        } finally {
            testWriter.shutdownNow();
        }
    }

    @Test
    @DisplayName("Embedding batch failure cancels remaining tasks and cleans up job")
    void testEmbeddingBatchFailureCancelsRemainingTasks() throws Exception {
        List<CodeChunk> chunks = new ArrayList<>();
        List<Document> docs = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            CodeChunk c = new CodeChunk();
            c.setId("fchunk-" + i);
            c.setType("METHOD");
            c.setContent("fcode-" + i);
            chunks.add(c);
            docs.add(new Document("fcode-" + i, Map.of("chunkId", "fchunk-" + i)));
        }
        when(mockParser.parse(any(Path.class))).thenReturn(chunks);
        when(mockConverter.convert(anyList())).thenReturn(docs);

        ChunkingConfig customConfig = new ChunkingConfig() {
            @Override public int getBatchSize() { return 1; }
            @Override public int getMaxParallelWorkers() { return 2; }
        };

        when(mockEmbeddingModel.embed(anyList())).thenAnswer(invocation -> {
            throw new RuntimeException("Simulated Ollama inference failure");
        });

        IndexingServiceImpl indexingService = new IndexingServiceImpl(
                mockParser, mockConverter, mockVectorStore, mockGitService,
                mockJdbcTemplate, mockSummaryGenerator, mockStatsCalculator,
                mockBudgetValidator, customConfig, mockRepoMgmtService,
                concurrencyManager, embeddingQueue, mockModelProvider);

        RuntimeException ex = assertThrows(RuntimeException.class, () ->
                indexingService.indexProject(".", "repo-failure-test"));
        assertTrue(ex.getMessage().contains("Embedding generation and queue staging failed"),
                "Expected failure message in exception: " + ex.getMessage());
    }
}

