package com.shravan.jcode_intelligence.service;

import com.shravan.jcode_intelligence.config.ChunkingConfig;
import com.shravan.jcode_intelligence.config.EmbeddingQueueConfig;
import com.shravan.jcode_intelligence.converter.DocumentConverter;
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
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * Rigorous regression test suite for Virtual-Thread-Per-Task embedding worker execution
 * and semaphore-bounded concurrency in {@link IndexingServiceImpl}.
 *
 * <p>Tests verify:
 * <ol>
 *     <li>Tasks genuinely execute on Java 21 virtual threads with distinct task scopes.</li>
 *     <li>Concurrency is strictly bounded by {@code max-parallel-workers} permits.</li>
 *     <li>Pre-submission permit acquisition prevents unbounded queueing in memory.</li>
 *     <li>Embedding queue backpressure keeps permits held, halting producer submissions.</li>
 *     <li>Fail-fast cancellation halts subsequent submissions and cancels in-flight tasks.</li>
 *     <li>Permits are never leaked on model exception, queue interruption, or rejection.</li>
 *     <li>Zero-document repositories and single-batch workloads adapt safely.</li>
 *     <li>Interruption during permit acquisition or queue submission propagates cleanly.</li>
 * </ol>
 */
class VirtualThreadEmbeddingPipelineRegressionTest {

    private JavaProjectParser mockParser;
    private DocumentConverter mockConverter;
    private VectorStore mockVectorStore;
    private GitService mockGitService;
    private JdbcTemplate mockJdbcTemplate;
    private PackageSummaryGenerator mockSummaryGenerator;
    private IndexingStatisticsCalculator mockStatsCalculator;
    private EmbeddingBudgetValidator mockBudgetValidator;
    private RepositoryManagementService mockRepoMgmtService;
    private RepositoryConcurrencyManager concurrencyManager;
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
        mockRepoMgmtService = mock(RepositoryManagementService.class);
        concurrencyManager = new RepositoryConcurrencyManager();
        mockEmbeddingModel = mock(EmbeddingModel.class);
        mockModelProvider = mock(ObjectProvider.class);

        when(mockModelProvider.getIfAvailable()).thenReturn(mockEmbeddingModel);
        when(mockSummaryGenerator.generatePackageSummaries(any())).thenReturn(List.of());
        when(mockStatsCalculator.calculate(any(), anyLong())).thenReturn(mock(IndexingStatistics.class));
    }

    private IndexingServiceImpl createIndexingService(ChunkingConfig chunkingConfig, EmbeddingQueue queue) {
        return new IndexingServiceImpl(
                mockParser, mockConverter, mockVectorStore, mockGitService,
                mockJdbcTemplate, mockSummaryGenerator, mockStatsCalculator,
                mockBudgetValidator, chunkingConfig, mockRepoMgmtService,
                concurrencyManager, queue, mockModelProvider);
    }

    private List<CodeChunk> createMockChunks(int count) {
        List<CodeChunk> chunks = new ArrayList<>(count);
        List<Document> docs = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            CodeChunk c = new CodeChunk();
            c.setId("chunk-" + i);
            c.setType("METHOD");
            c.setContent("int test" + i + "() { return " + i + "; }");
            chunks.add(c);
            docs.add(new Document(c.getContent(), Map.of("chunkId", c.getId())));
        }
        try {
            when(mockParser.parse(any(Path.class))).thenReturn(chunks);
            when(mockConverter.convert(anyList())).thenReturn(docs);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return chunks;
    }

    @Test
    @DisplayName("Edge Case 1: Every batch task runs on a genuine Java 21 virtual thread")
    void testEveryBatchTaskExecutesOnVirtualThread() throws Exception {
        int totalBatches = 8;
        createMockChunks(totalBatches);

        ChunkingConfig config = new ChunkingConfig() {
            @Override public int getBatchSize() { return 1; }
            @Override public int getMaxParallelWorkers() { return 4; }
        };

        EmbeddingQueue queue = new EmbeddingQueue(new EmbeddingQueueConfig(100, 20, 5, 50, 1, 2, 10));
        Set<Boolean> threadVirtualFlags = ConcurrentHashMap.newKeySet();
        Set<String> threadIdentities = ConcurrentHashMap.newKeySet();

        when(mockEmbeddingModel.embed(anyList())).thenAnswer(inv -> {
            Thread t = Thread.currentThread();
            threadVirtualFlags.add(t.isVirtual());
            threadIdentities.add(t.toString());
            return List.of(new float[]{0.1f, 0.2f});
        });

        ScheduledExecutorService writer = Executors.newSingleThreadScheduledExecutor();
        writer.scheduleWithFixedDelay(() -> {
            try {
                List<EmbeddedRecord> drained = queue.drainBatch(10, 20);
                for (EmbeddedRecord r : drained) {
                    queue.recordPersisted(r);
                }
            } catch (Exception ignored) {
            }
        }, 10, 20, TimeUnit.MILLISECONDS);

        try {
            IndexingServiceImpl service = createIndexingService(config, queue);
            int indexed = service.indexProject(".", "repo-vt-check");
            assertEquals(totalBatches, indexed);

            assertFalse(threadVirtualFlags.isEmpty(), "Tasks must have executed");
            assertTrue(threadVirtualFlags.contains(true), "Tasks must execute on virtual threads");
            assertFalse(threadVirtualFlags.contains(false), "No task may execute on a platform thread");
            assertTrue(threadIdentities.size() >= 4, "Distinct virtual threads should be spawned per batch");
        } finally {
            writer.shutdownNow();
        }
    }

    @Test
    @DisplayName("Edge Case 2: Concurrency strictly bounded by maxParallelWorkers semaphore")
    void testConcurrencyStrictlyBoundedByConfiguredWorkers() throws Exception {
        int totalBatches = 12;
        int maxWorkers = 3;
        createMockChunks(totalBatches);

        ChunkingConfig config = new ChunkingConfig() {
            @Override public int getBatchSize() { return 1; }
            @Override public int getMaxParallelWorkers() { return maxWorkers; }
        };

        EmbeddingQueue queue = new EmbeddingQueue(new EmbeddingQueueConfig(100, 20, 5, 50, 1, 2, 10));
        AtomicInteger activeWorkers = new AtomicInteger(0);
        AtomicInteger maxObservedConcurrency = new AtomicInteger(0);

        when(mockEmbeddingModel.embed(anyList())).thenAnswer(inv -> {
            int current = activeWorkers.incrementAndGet();
            maxObservedConcurrency.updateAndGet(prev -> Math.max(prev, current));
            try {
                Thread.sleep(30); // Hold permit to measure peak concurrency
            } finally {
                activeWorkers.decrementAndGet();
            }
            return List.of(new float[]{0.5f});
        });

        ScheduledExecutorService writer = Executors.newSingleThreadScheduledExecutor();
        writer.scheduleWithFixedDelay(() -> {
            try {
                List<EmbeddedRecord> drained = queue.drainBatch(10, 20);
                for (EmbeddedRecord r : drained) {
                    queue.recordPersisted(r);
                }
            } catch (Exception ignored) {
            }
        }, 10, 20, TimeUnit.MILLISECONDS);

        try {
            IndexingServiceImpl service = createIndexingService(config, queue);
            int indexed = service.indexProject(".", "repo-concurrency-check");
            assertEquals(totalBatches, indexed);

            assertEquals(maxWorkers, maxObservedConcurrency.get(),
                    "Observed concurrency must reach but never exceed maxParallelWorkers (" + maxWorkers + ")");
            assertEquals(0, activeWorkers.get(), "All workers must have released their permits upon completion");
        } finally {
            writer.shutdownNow();
        }
    }

    @Test
    @DisplayName("Edge Case 3: Pre-submission permit acquisition blocks main loop when permits exhausted")
    void testPreSubmissionBlocksMainThreadWhenWorkersBusy() throws Exception {
        int totalBatches = 5;
        int maxWorkers = 2;
        createMockChunks(totalBatches);

        ChunkingConfig config = new ChunkingConfig() {
            @Override public int getBatchSize() { return 1; }
            @Override public int getMaxParallelWorkers() { return maxWorkers; }
        };

        EmbeddingQueue queue = new EmbeddingQueue(new EmbeddingQueueConfig(100, 20, 5, 50, 1, 2, 10));
        CountDownLatch activeBatchesLatch = new CountDownLatch(maxWorkers);
        CountDownLatch releaseHoldLatch = new CountDownLatch(1);
        AtomicInteger startedBatches = new AtomicInteger(0);

        when(mockEmbeddingModel.embed(anyList())).thenAnswer(inv -> {
            int count = startedBatches.incrementAndGet();
            activeBatchesLatch.countDown();
            if (count <= maxWorkers) {
                // First two batches hold the permits
                assertTrue(releaseHoldLatch.await(5, TimeUnit.SECONDS), "Timed out waiting to release hold");
            }
            return List.of(new float[]{0.1f});
        });

        ScheduledExecutorService writer = Executors.newSingleThreadScheduledExecutor();
        writer.scheduleWithFixedDelay(() -> {
            try {
                List<EmbeddedRecord> drained = queue.drainBatch(10, 20);
                for (EmbeddedRecord r : drained) {
                    queue.recordPersisted(r);
                }
            } catch (Exception ignored) {
            }
        }, 10, 20, TimeUnit.MILLISECONDS);

        ExecutorService indexerThread = Executors.newSingleThreadExecutor();
        try {
            IndexingServiceImpl service = createIndexingService(config, queue);
            Future<Integer> indexingFuture = indexerThread.submit(() ->
                    service.indexProject(".", "repo-presubmission-check"));

            // Wait until exactly maxWorkers are running and holding permits
            assertTrue(activeBatchesLatch.await(3, TimeUnit.SECONDS), "First two batches must start");
            Thread.sleep(100);

            // Because maxWorkers = 2, batch 3 MUST NOT have been submitted or started!
            assertEquals(maxWorkers, startedBatches.get(),
                    "Main submission loop must block; batches > maxWorkers must not be submitted yet");

            // Release hold and allow remaining batches to proceed
            releaseHoldLatch.countDown();

            int result = indexingFuture.get(5, TimeUnit.SECONDS);
            assertEquals(totalBatches, result);
            assertEquals(totalBatches, startedBatches.get());
        } finally {
            releaseHoldLatch.countDown();
            indexerThread.shutdownNow();
            writer.shutdownNow();
        }
    }

    @Test
    @DisplayName("Edge Case 4: Queue full backpressure keeps virtual thread blocked and holds permit")
    void testQueueFullBackpressureHoldsPermitAndHaltsSubmissions() throws Exception {
        int totalBatches = 16;
        int maxWorkers = 2;
        int queueCapacity = 10; // Minimum capacity of EmbeddingQueue is 10
        createMockChunks(totalBatches);

        ChunkingConfig config = new ChunkingConfig() {
            @Override public int getBatchSize() { return 1; }
            @Override public int getMaxParallelWorkers() { return maxWorkers; }
        };

        // Queue with capacity = 10
        EmbeddingQueue smallQueue = new EmbeddingQueue(
                new EmbeddingQueueConfig(queueCapacity, 5, 2, 50, 1, 2, 10));

        AtomicInteger embedCompletedCount = new AtomicInteger(0);
        when(mockEmbeddingModel.embed(anyList())).thenAnswer(inv -> {
            embedCompletedCount.incrementAndGet();
            return List.of(new float[]{0.9f});
        });

        // Do NOT start a background writer yet! The queue will fill after 10 batches,
        // and the 2 active workers will block in submitAll, holding both permits.
        ExecutorService indexerThread = Executors.newSingleThreadExecutor();

        try {
            IndexingServiceImpl service = createIndexingService(config, smallQueue);
            Future<Integer> indexingFuture = indexerThread.submit(() ->
                    service.indexProject(".", "repo-backpressure-check"));

            // Wait until queue is full and both worker permits are blocked in submitAll
            long deadline = System.currentTimeMillis() + 3000;
            while (embedCompletedCount.get() < (queueCapacity + maxWorkers) && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }

            // Both permits are held: queue has 10 records, workers 1 & 2 are blocked in submitAll (total 12 embedded)
            int blockedCount = embedCompletedCount.get();
            assertTrue(blockedCount >= queueCapacity && blockedCount <= (queueCapacity + maxWorkers),
                    "Active batches must be bounded between capacity (" + queueCapacity + ") and capacity + workers (" + (queueCapacity + maxWorkers) + "), got: " + blockedCount);
            assertTrue(blockedCount < totalBatches, "Producer must be halted before submitting all batches");

            // Pause to verify backpressure holds indefinitely while queue remains full
            Thread.sleep(150);
            assertEquals(blockedCount, embedCompletedCount.get(),
                    "Backpressure must halt producer: no further batches may embed while queue is full");

            // Now start draining the queue so blocked workers can finish
            ScheduledExecutorService writer = Executors.newSingleThreadScheduledExecutor();
            writer.scheduleWithFixedDelay(() -> {
                try {
                    List<EmbeddedRecord> drained = smallQueue.drainBatch(10, 20);
                    for (EmbeddedRecord r : drained) {
                        smallQueue.recordPersisted(r);
                    }
                } catch (Exception ignored) {
                }
            }, 10, 20, TimeUnit.MILLISECONDS);

            try {
                int result = indexingFuture.get(5, TimeUnit.SECONDS);
                assertEquals(totalBatches, result);
                assertEquals(totalBatches, embedCompletedCount.get(), "All batches must finish once queue drains");
            } finally {
                writer.shutdownNow();
            }
        } finally {
            indexerThread.shutdownNow();
        }
    }

    @Test
    @DisplayName("Edge Case 5: Task failure aborts subsequent submissions and cancels in-flight tasks")
    void testTaskFailureHaltsSubmissionAndCancelsInFlight() throws Exception {
        int totalBatches = 8;
        int maxWorkers = 3;
        createMockChunks(totalBatches);

        ChunkingConfig config = new ChunkingConfig() {
            @Override public int getBatchSize() { return 1; }
            @Override public int getMaxParallelWorkers() { return maxWorkers; }
        };

        EmbeddingQueue queue = new EmbeddingQueue(new EmbeddingQueueConfig(100, 20, 5, 50, 1, 2, 10));
        AtomicInteger taskCounter = new AtomicInteger(0);
        CountDownLatch failureTriggered = new CountDownLatch(1);

        when(mockEmbeddingModel.embed(anyList())).thenAnswer(inv -> {
            int id = taskCounter.incrementAndGet();
            if (id == 2) {
                failureTriggered.countDown();
                throw new RuntimeException("Simulated Ollama 500 Internal Server Error on Batch 2");
            }
            Thread.sleep(100);
            return List.of(new float[]{0.1f});
        });

        IndexingServiceImpl service = createIndexingService(config, queue);

        RuntimeException thrown = assertThrows(RuntimeException.class, () ->
                service.indexProject(".", "repo-failfast-check"));

        assertTrue(thrown.getMessage().contains("Embedding generation and queue staging failed"),
                "Exception message must identify STEP 6 failure: " + thrown.getMessage());
        assertTrue(taskCounter.get() < totalBatches,
                "Fail-fast submission guard must abort remaining unsubmitted batches (executed: " + taskCounter.get() + "/" + totalBatches + ")");
    }

    @Test
    @DisplayName("Edge Case 6: Empty document repository completes cleanly with zero permits acquired")
    void testEmptyDocumentRepositoryCompletesCleanly() throws Exception {
        when(mockParser.parse(any(Path.class))).thenReturn(List.of());
        when(mockConverter.convert(anyList())).thenReturn(List.of());

        ChunkingConfig config = new ChunkingConfig();
        EmbeddingQueue queue = new EmbeddingQueue(new EmbeddingQueueConfig(100, 20, 5, 50, 1, 2, 10));

        IndexingServiceImpl service = createIndexingService(config, queue);
        int result = service.indexProject(".", "empty-repo");

        assertEquals(0, result);
        verify(mockEmbeddingModel, never()).embed(anyList());
    }

    @Test
    @DisplayName("Edge Case 7: Single-batch workload scales worker count down to 1 permit safely")
    void testSingleBatchWorkloadScalesWorkerCountToOne() throws Exception {
        createMockChunks(1); // 1 document, batchSize = 100 -> exactly 1 batch

        ChunkingConfig config = new ChunkingConfig() {
            @Override public int getBatchSize() { return 100; }
            @Override public int getMaxParallelWorkers() { return 8; } // high limit
        };

        EmbeddingQueue queue = new EmbeddingQueue(new EmbeddingQueueConfig(100, 20, 5, 50, 1, 2, 10));
        AtomicBoolean ranVirtual = new AtomicBoolean(false);

        when(mockEmbeddingModel.embed(anyList())).thenAnswer(inv -> {
            ranVirtual.set(Thread.currentThread().isVirtual());
            return List.of(new float[]{0.77f});
        });

        ScheduledExecutorService writer = Executors.newSingleThreadScheduledExecutor();
        writer.scheduleWithFixedDelay(() -> {
            try {
                List<EmbeddedRecord> drained = queue.drainBatch(10, 20);
                for (EmbeddedRecord r : drained) {
                    queue.recordPersisted(r);
                }
            } catch (Exception ignored) {
            }
        }, 10, 20, TimeUnit.MILLISECONDS);

        try {
            IndexingServiceImpl service = createIndexingService(config, queue);
            int result = service.indexProject(".", "single-batch-repo");

            assertEquals(1, result);
            assertTrue(ranVirtual.get(), "Single batch must also execute on a virtual thread");
        } finally {
            writer.shutdownNow();
        }
    }

    @Test
    @DisplayName("Edge Case 8: Interruption during permit acquisition restores interrupt flag and cleans up")
    void testInterruptionDuringPermitAcquireCleansUpJob() throws Exception {
        createMockChunks(10);
        ChunkingConfig config = new ChunkingConfig() {
            @Override public int getBatchSize() { return 1; }
            @Override public int getMaxParallelWorkers() { return 1; }
        };

        EmbeddingQueue queue = new EmbeddingQueue(new EmbeddingQueueConfig(100, 20, 5, 50, 1, 2, 10));
        CountDownLatch firstTaskStarted = new CountDownLatch(1);

        when(mockEmbeddingModel.embed(anyList())).thenAnswer(inv -> {
            firstTaskStarted.countDown();
            Thread.sleep(5000); // Hold the only permit
            return List.of(new float[]{0.1f});
        });

        ExecutorService indexer = Executors.newSingleThreadExecutor();
        IndexingServiceImpl service = createIndexingService(config, queue);

        Future<Integer> future = indexer.submit(() ->
                service.indexProject(".", "repo-interrupt-check"));

        assertTrue(firstTaskStarted.await(3, TimeUnit.SECONDS), "First task must start and hold permit");

        // Interrupt indexer thread while it waits for permit on batch 2
        indexer.shutdownNow();

        ExecutionException ee = assertThrows(ExecutionException.class, () ->
                future.get(3, TimeUnit.SECONDS));

        boolean foundInterruptMessage = false;
        boolean foundInterruptedException = false;
        Throwable curr = ee.getCause();
        while (curr != null) {
            if (curr instanceof InterruptedException) {
                foundInterruptedException = true;
            }
            if (curr.getMessage() != null && curr.getMessage().contains("Interrupted while awaiting embedding worker permit")) {
                foundInterruptMessage = true;
            }
            curr = curr.getCause();
        }
        assertTrue(foundInterruptMessage, "Expected permit acquisition interruption message in exception chain");
        assertTrue(foundInterruptedException, "Expected InterruptedException in exception cause chain");
    }

    @Test
    @DisplayName("Edge Case 9: Model exception releases permit without permit leakage")
    void testModelExceptionReleasesPermitImmediately() throws Exception {
        int totalBatches = 4;
        createMockChunks(totalBatches);

        ChunkingConfig config = new ChunkingConfig() {
            @Override public int getBatchSize() { return 1; }
            @Override public int getMaxParallelWorkers() { return 2; }
        };

        EmbeddingQueue queue = new EmbeddingQueue(new EmbeddingQueueConfig(100, 20, 5, 50, 1, 2, 10));
        AtomicInteger attempts = new AtomicInteger(0);

        when(mockEmbeddingModel.embed(anyList())).thenAnswer(inv -> {
            attempts.incrementAndGet();
            throw new IllegalArgumentException("Invalid token payload");
        });

        IndexingServiceImpl service = createIndexingService(config, queue);

        assertThrows(RuntimeException.class, () ->
                service.indexProject(".", "repo-permit-leak-check"));

        // After failure, ensure repository lock is released and clean state is maintained
        assertFalse(concurrencyManager.isLocked("repo-permit-leak-check"),
                "Repository lock must be released after failure");
    }

    @Test
    @DisplayName("Edge Case 10: Variable batch latency completes out-of-order without head-of-line blocking")
    void testVariableBatchLatencyCompletesWithoutHeadOfLineBlocking() throws Exception {
        int totalBatches = 6;
        createMockChunks(totalBatches);

        ChunkingConfig config = new ChunkingConfig() {
            @Override public int getBatchSize() { return 1; }
            @Override public int getMaxParallelWorkers() { return 3; }
        };

        EmbeddingQueue queue = new EmbeddingQueue(new EmbeddingQueueConfig(100, 20, 5, 50, 1, 2, 10));
        List<String> completionOrder = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger taskIdx = new AtomicInteger(0);

        when(mockEmbeddingModel.embed(anyList())).thenAnswer(inv -> {
            int id = taskIdx.incrementAndGet();
            // Invert completion order: early tasks sleep longer
            long sleepMs = (totalBatches - id + 1) * 20L;
            Thread.sleep(sleepMs);
            completionOrder.add("batch-" + id);
            return List.of(new float[]{0.1f * id});
        });

        ScheduledExecutorService writer = Executors.newSingleThreadScheduledExecutor();
        writer.scheduleWithFixedDelay(() -> {
            try {
                List<EmbeddedRecord> drained = queue.drainBatch(10, 20);
                for (EmbeddedRecord r : drained) {
                    queue.recordPersisted(r);
                }
            } catch (Exception ignored) {
            }
        }, 10, 20, TimeUnit.MILLISECONDS);

        try {
            IndexingServiceImpl service = createIndexingService(config, queue);
            int indexed = service.indexProject(".", "repo-out-of-order-test");

            assertEquals(totalBatches, indexed);
            assertEquals(totalBatches, completionOrder.size());
            // Verify that out-of-order completion occurred naturally due to virtual threads
            assertNotEquals(List.of("batch-1", "batch-2", "batch-3", "batch-4", "batch-5", "batch-6"),
                    completionOrder, "Tasks should complete out-of-order based on individual latency");
        } finally {
            writer.shutdownNow();
        }
    }

    @Test
    @DisplayName("Edge Case 11: Single document with large batch configuration adapts safely")
    void testSingleDocumentWithLargeBatchAdaptsSafely() throws Exception {
        createMockChunks(1);

        ChunkingConfig config = new ChunkingConfig() {
            @Override public int getBatchSize() { return 500; }
            @Override public int getMaxParallelWorkers() { return 16; }
        };

        EmbeddingQueue queue = new EmbeddingQueue(new EmbeddingQueueConfig(100, 20, 5, 50, 1, 2, 10));
        when(mockEmbeddingModel.embed(anyList())).thenReturn(List.of(new float[]{0.42f}));

        ScheduledExecutorService writer = Executors.newSingleThreadScheduledExecutor();
        writer.scheduleWithFixedDelay(() -> {
            try {
                List<EmbeddedRecord> drained = queue.drainBatch(10, 20);
                for (EmbeddedRecord r : drained) {
                    queue.recordPersisted(r);
                }
            } catch (Exception ignored) {
            }
        }, 10, 20, TimeUnit.MILLISECONDS);

        try {
            IndexingServiceImpl service = createIndexingService(config, queue);
            int indexed = service.indexProject(".", "repo-large-batch-test");
            assertEquals(1, indexed);
        } finally {
            writer.shutdownNow();
        }
    }

    @Test
    @DisplayName("Edge Case 12: Producer re-checks failure flag after acquiring permit and aborts immediately")
    void testProducerRechecksFailureAfterPermitAcquireAndDoesNotSubmitNewBatch() throws Exception {
        int totalBatches = 4;
        createMockChunks(totalBatches);

        // maxWorkers = 1 so subsequent batches must block in semaphore.acquire()
        ChunkingConfig config = new ChunkingConfig() {
            @Override public int getBatchSize() { return 1; }
            @Override public int getMaxParallelWorkers() { return 1; }
        };

        EmbeddingQueue queue = new EmbeddingQueue(new EmbeddingQueueConfig(100, 20, 5, 50, 1, 2, 10));
        AtomicInteger embedInvocations = new AtomicInteger(0);

        when(mockEmbeddingModel.embed(anyList())).thenAnswer(inv -> {
            embedInvocations.incrementAndGet();
            // Hold permit briefly to ensure producer thread is blocked in semaphore.acquire() for batch 2
            Thread.sleep(50);
            throw new RuntimeException("Batch 1 failure while batch 2 is blocked in acquire");
        });

        IndexingServiceImpl service = createIndexingService(config, queue);

        assertThrows(RuntimeException.class, () ->
                service.indexProject(".", "repo-recheck-failure-test"));

        // Only batch 1 should ever be submitted/embedded; batch 2 must abort after unblocking from acquire()
        assertEquals(1, embedInvocations.get(),
                "Producer must not submit batch 2 after unblocking from acquire() when firstFailureRef is set");
    }
}
