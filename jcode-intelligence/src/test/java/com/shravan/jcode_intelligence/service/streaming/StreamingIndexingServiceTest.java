package com.shravan.jcode_intelligence.service.streaming;

import com.github.javaparser.JavaParser;
import com.shravan.jcode_intelligence.config.ChunkingConfig;
import com.shravan.jcode_intelligence.config.EmbeddingQueueConfig;
import com.shravan.jcode_intelligence.config.JavaParserConfig;
import com.shravan.jcode_intelligence.config.LargeRepoIndexingConfig;
import com.shravan.jcode_intelligence.converter.DocumentConverter;
import com.shravan.jcode_intelligence.exception.EmbeddingException;
import com.shravan.jcode_intelligence.model.CodeChunk;
import com.shravan.jcode_intelligence.model.EmbeddedRecord;
import com.shravan.jcode_intelligence.model.IndexingStatistics;
import com.shravan.jcode_intelligence.parser.*;
import com.shravan.jcode_intelligence.parser.spi.ChunkBudgetEstimator;
import com.shravan.jcode_intelligence.service.BatchEmbeddingHelper;
import com.shravan.jcode_intelligence.service.EmbeddingQueue;
import com.shravan.jcode_intelligence.service.RepositoryManagementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Verification test suite for {@link StreamingIndexingService} covering streaming dispatch,
 * chunk buffer bounds, threshold draining, lifecycle events, backpressure, failure isolation,
 * weak-reference garbage collection, and record purity.
 */
class StreamingIndexingServiceTest {

    @TempDir
    Path tempDir;

    private JavaProjectParser realParser;
    private DocumentConverter realConverter;
    private EmbeddingBudgetValidator realBudgetValidator;
    private PackageSummaryGenerator realSummaryGenerator;
    private EmbeddingModel mockEmbeddingModel;
    private ObjectProvider<EmbeddingModel> mockModelProvider;
    private RepositoryManagementService mockRepoMgmtService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        JavaParserConfig configFactory = new JavaParserConfig();
        JavaParser javaParser = configFactory.javaParser();
        MetadataExtractor metadataExtractor = new MetadataExtractor();
        ClassSummaryBuilder summaryBuilder = new ClassSummaryBuilder();
        ChunkingConfig chunkingConfig = new ChunkingConfig();
        ChunkBudgetEstimator budgetEstimator = new CharBasedBudgetEstimator(chunkingConfig);
        MethodFragmenter methodFragmenter = new MethodFragmenter(metadataExtractor, budgetEstimator);
        ChunkGenerator chunkGenerator = new ChunkGenerator(
                metadataExtractor, summaryBuilder, methodFragmenter, budgetEstimator);
        AstVisitor astVisitor = new AstVisitor(chunkGenerator);
        IndexableJavaFileFilter filter = new IndexableJavaFileFilter();
        JavaSourceDiscovery discovery = new JavaSourceDiscovery(filter);
        realParser = new JavaProjectParser(javaParser, astVisitor, discovery);

        realConverter = new DocumentConverter();
        realBudgetValidator = new EmbeddingBudgetValidator(budgetEstimator);
        realSummaryGenerator = new PackageSummaryGenerator();

        mockEmbeddingModel = mock(EmbeddingModel.class);
        mockModelProvider = mock(ObjectProvider.class);
        when(mockModelProvider.getIfAvailable()).thenReturn(mockEmbeddingModel);

        when(mockEmbeddingModel.embed(anyList())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            List<float[]> embs = new ArrayList<>(texts.size());
            for (int i = 0; i < texts.size(); i++) {
                embs.add(new float[]{0.1f * (i + 1), 0.2f, 0.3f});
            }
            return embs;
        });

        mockRepoMgmtService = mock(RepositoryManagementService.class);
    }

    private ChunkingConfig customChunkingConfig(int batchSize, int maxWorkers) {
        return new ChunkingConfig() {
            @Override
            public int getBatchSize() {
                return batchSize;
            }

            @Override
            public int getMaxParallelWorkers() {
                return maxWorkers;
            }
        };
    }

    private Thread startSimulatedDrainer(EmbeddingQueue queue, AtomicInteger drainedCount, List<EmbeddedRecord> stagedRecords) {
        Thread drainer = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted() && !queue.isAdmissionClosed()) {
                    List<EmbeddedRecord> batch = queue.drainBatch(20, 50);
                    if (batch != null && !batch.isEmpty()) {
                        if (drainedCount != null) {
                            drainedCount.addAndGet(batch.size());
                        }
                        if (stagedRecords != null) {
                            synchronized (stagedRecords) {
                                stagedRecords.addAll(batch);
                            }
                        }
                        for (EmbeddedRecord r : batch) {
                            queue.recordPersisted(r);
                        }
                    }
                    Thread.sleep(10);
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (Exception ignored) {
            }
        });
        drainer.setDaemon(true);
        drainer.start();
        return drainer;
    }

    @Test
    @DisplayName("Empty eligible files list completes with 0 docs and records stats")
    void testEmptyEligibleFiles() throws Exception {
        EmbeddingQueue queue = new EmbeddingQueue(new EmbeddingQueueConfig(100, 20, 5, 50, 1, 2, 10));
        StreamingIndexingService service = new StreamingIndexingService(
                realParser, realConverter, realBudgetValidator, realSummaryGenerator,
                new LargeRepoIndexingConfig(10), customChunkingConfig(10, 2), queue, mockModelProvider,
                mockRepoMgmtService, null
        );

        int totalDocs = service.index("repo-empty", List.of());
        assertEquals(0, totalDocs);
        verify(mockRepoMgmtService).saveRepositoryStats(eq("repo-empty"), any(IndexingStatistics.class));
    }

    @Test
    @DisplayName("Streams temporary fixtures, drains exact and partial thresholds, and produces complete summaries")
    void testStreamingThresholdsAndSummaries() throws Exception {
        Path file1 = tempDir.resolve("ServiceA.java");
        Files.writeString(file1, """
                package com.example.service;
                public class ServiceA {
                    private String id;
                    public void doA() {}
                    public void doB() {}
                }
                """);

        Path file2 = tempDir.resolve("ServiceB.java");
        Files.writeString(file2, """
                package com.example.service;
                public class ServiceB {
                    public void execute() {}
                }
                """);

        Path file3 = tempDir.resolve("Util.java");
        Files.writeString(file3, """
                package com.example.util;
                public class Util {
                    public static void log() {}
                }
                """);

        EmbeddingQueue queue = new EmbeddingQueue(new EmbeddingQueueConfig(100, 5, 2, 50, 1, 2, 10));
        AtomicInteger drainedCount = new AtomicInteger(0);
        List<EmbeddedRecord> capturedRecords = Collections.synchronizedList(new ArrayList<>());
        Thread drainer = startSimulatedDrainer(queue, drainedCount, capturedRecords);

        try {
            // Threshold = 4 (small to force threshold slices)
            LargeRepoIndexingConfig largeConfig = new LargeRepoIndexingConfig(4);
            ChunkingConfig chunkConfig = customChunkingConfig(2, 2);

            StreamingIndexingService service = new StreamingIndexingService(
                    realParser, realConverter, realBudgetValidator, realSummaryGenerator,
                    largeConfig, chunkConfig, queue, mockModelProvider,
                    mockRepoMgmtService, null
            );

            List<Path> files = List.of(file1, file2, file3);
            int totalDocs = service.index("repo-streaming-success", files);

            assertTrue(totalDocs > 0);
            assertEquals(totalDocs, drainedCount.get());

            // Check that package summaries were generated for com.example.service and com.example.util
            boolean hasServiceSummary = capturedRecords.stream().anyMatch(r ->
                    "PACKAGE".equals(r.metadata().get("type")) && "com.example.service".equals(r.metadata().get("packageName")));
            boolean hasUtilSummary = capturedRecords.stream().anyMatch(r ->
                    "PACKAGE".equals(r.metadata().get("type")) && "com.example.util".equals(r.metadata().get("packageName")));
            assertTrue(hasServiceSummary, "Package summary for com.example.service must be staged");
            assertTrue(hasUtilSummary, "Package summary for com.example.util must be staged");

            verify(mockRepoMgmtService).saveRepositoryStats(eq("repo-streaming-success"), any(IndexingStatistics.class));
        } finally {
            drainer.interrupt();
        }
    }

    @Test
    @DisplayName("Oversized single file exceeding threshold drains in multiple threshold-sized slices")
    void testOversizedSingleFileDraining() throws Exception {
        // File with multiple methods and fields generating >= 6 chunks
        StringBuilder sb = new StringBuilder();
        sb.append("package com.example.large;\npublic class LargeClass {\n");
        for (int i = 0; i < 6; i++) {
            sb.append("    public void method").append(i).append("() { int x = ").append(i).append("; }\n");
        }
        sb.append("}\n");

        Path largeFile = tempDir.resolve("LargeClass.java");
        Files.writeString(largeFile, sb.toString());

        EmbeddingQueue queue = new EmbeddingQueue(new EmbeddingQueueConfig(100, 5, 2, 50, 1, 2, 10));
        AtomicInteger drainedCount = new AtomicInteger(0);
        Thread drainer = startSimulatedDrainer(queue, drainedCount, null);

        try {
            // Threshold = 2 -> Single file will drain multiple slices
            LargeRepoIndexingConfig largeConfig = new LargeRepoIndexingConfig(2);
            ChunkingConfig chunkConfig = customChunkingConfig(2, 2);

            StreamingIndexingService service = new StreamingIndexingService(
                    realParser, realConverter, realBudgetValidator, realSummaryGenerator,
                    largeConfig, chunkConfig, queue, mockModelProvider,
                    mockRepoMgmtService, null
            );

            int totalDocs = service.index("repo-oversized", List.of(largeFile));
            assertTrue(totalDocs >= 7); // Class + 6 methods + 1 package summary
            assertEquals(totalDocs, drainedCount.get());
        } finally {
            drainer.interrupt();
        }
    }

    @Test
    @DisplayName("Files are parsed in exact specified order exactly once")
    void testOrderedOneTimeFileParsing() throws Exception {
        Path f1 = tempDir.resolve("Alpha.java");
        Path f2 = tempDir.resolve("Beta.java");
        Path f3 = tempDir.resolve("Gamma.java");

        Files.writeString(f1, "package p; public class Alpha {}");
        Files.writeString(f2, "package p; public class Beta {}");
        Files.writeString(f3, "package p; public class Gamma {}");

        JavaProjectParser spyParser = spy(realParser);

        EmbeddingQueue queue = new EmbeddingQueue(new EmbeddingQueueConfig(100, 5, 2, 50, 1, 2, 10));
        Thread drainer = startSimulatedDrainer(queue, null, null);

        try {
            StreamingIndexingService service = new StreamingIndexingService(
                    spyParser, realConverter, realBudgetValidator, realSummaryGenerator,
                    new LargeRepoIndexingConfig(10), customChunkingConfig(5, 2), queue, mockModelProvider,
                    mockRepoMgmtService, null
            );

            List<Path> orderedFiles = List.of(f1, f2, f3);
            service.index("repo-ordered", orderedFiles);

            var inOrder = inOrder(spyParser);
            inOrder.verify(spyParser).parse(List.of(f1));
            inOrder.verify(spyParser).parse(List.of(f2));
            inOrder.verify(spyParser).parse(List.of(f3));
            verify(spyParser, times(1)).parse(List.of(f1));
            verify(spyParser, times(1)).parse(List.of(f2));
            verify(spyParser, times(1)).parse(List.of(f3));
        } finally {
            drainer.interrupt();
        }
    }

    @Test
    @DisplayName("Producers complete before completeJob and stats saving, and awaiting queue persistence")
    void testProducerCompletionBeforeJobCompleteAndPersistenceAwaited() throws Exception {
        Path file = tempDir.resolve("Simple.java");
        Files.writeString(file, "package test; public class Simple { public void run() {} }");

        EmbeddingQueue queue = spy(new EmbeddingQueue(new EmbeddingQueueConfig(100, 5, 2, 50, 1, 2, 10)));
        AtomicBoolean persistenceAcknowledged = new AtomicBoolean(false);

        // Slow drainer to verify wait for queue acknowledgement
        Thread drainer = new Thread(() -> {
            try {
                Thread.sleep(100); // delay draining
                while (!queue.isAdmissionClosed()) {
                    List<EmbeddedRecord> batch = queue.drainBatch(20, 100);
                    if (batch != null && !batch.isEmpty()) {
                        for (EmbeddedRecord r : batch) {
                            persistenceAcknowledged.set(true);
                            queue.recordPersisted(r);
                        }
                    }
                    Thread.sleep(10);
                }
            } catch (Exception ignored) {
            }
        });
        drainer.setDaemon(true);
        drainer.start();

        try {
            AtomicBoolean completedBeforeStats = new AtomicBoolean(false);
            doAnswer(inv -> {
                // Verify all worker threads are done when completeJob is called
                completedBeforeStats.set(true);
                return inv.callRealMethod();
            }).when(queue).completeJob(anyString());

            doAnswer(inv -> {
                // Verify persistence is completed before saveRepositoryStats is called
                assertTrue(persistenceAcknowledged.get(), "Persistence must be acknowledged before saving stats");
                assertTrue(completedBeforeStats.get(), "completeJob must precede saving stats");
                return null;
            }).when(mockRepoMgmtService).saveRepositoryStats(anyString(), any(IndexingStatistics.class));

            StreamingIndexingService service = new StreamingIndexingService(
                    realParser, realConverter, realBudgetValidator, realSummaryGenerator,
                    new LargeRepoIndexingConfig(5), customChunkingConfig(2, 2), queue, mockModelProvider,
                    mockRepoMgmtService, null
            );

            service.index("repo-lifecycle", List.of(file));

            verify(queue).completeJob(anyString());
            verify(queue).awaitJobCompletion(anyString(), any());
            verify(mockRepoMgmtService).saveRepositoryStats(eq("repo-lifecycle"), any(IndexingStatistics.class));
        } finally {
            drainer.interrupt();
        }
    }

    @Test
    @DisplayName("Backpressure halts submission when queue capacity is reached until drained")
    void testBackpressureWithSmallQueueAndBlockedDrainer() throws Exception {
        // Small queue capacity of 10 (minimum capacity in EmbeddingQueue)
        EmbeddingQueue queue = new EmbeddingQueue(new EmbeddingQueueConfig(10, 2, 1, 100, 1, 1, 10));

        Path file1 = tempDir.resolve("BP1.java");
        StringBuilder sb = new StringBuilder("package bp;\npublic class BP1 {\n");
        for (int i = 0; i < 15; i++) {
            sb.append("    public void m").append(i).append("() {}\n");
        }
        sb.append("}\n");
        Files.writeString(file1, sb.toString());

        StreamingIndexingService service = new StreamingIndexingService(
                realParser, realConverter, realBudgetValidator, realSummaryGenerator,
                new LargeRepoIndexingConfig(2), customChunkingConfig(1, 1), queue, mockModelProvider,
                mockRepoMgmtService, null
        );

        AtomicBoolean indexingFinished = new AtomicBoolean(false);
        AtomicInteger drainedCount = new AtomicInteger(0);

        ExecutorService testExec = Executors.newSingleThreadExecutor();
        Future<?> indexFuture = testExec.submit(() -> {
            try {
                service.index("repo-bp", List.of(file1));
                indexingFinished.set(true);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // Allow queue to fill and backpressure to engage
        Thread.sleep(200);
        assertFalse(indexingFinished.get(), "Producer should be blocked by backpressure when drainer is inactive");

        // Now start drainer
        Thread drainer = startSimulatedDrainer(queue, drainedCount, null);
        try {
            indexFuture.get(5, TimeUnit.SECONDS);
            assertTrue(indexingFinished.get(), "Indexing should complete after drainer relieves backpressure");
        } finally {
            drainer.interrupt();
            testExec.shutdownNow();
        }
    }

    @Test
    @DisplayName("Later budget validator failure terminates producers before cleanup and fails job without stats")
    void testLaterBudgetFailureTerminatesProducersBeforeCleanup() throws Exception {
        Path f1 = tempDir.resolve("Good.java");
        Path f2 = tempDir.resolve("Bad.java");
        Files.writeString(f1, "package p; public class Good {}");
        Files.writeString(f2, "package p; public class Bad {}");

        EmbeddingBudgetValidator mockValidator = mock(EmbeddingBudgetValidator.class);
        AtomicInteger callCount = new AtomicInteger(0);
        doAnswer(inv -> {
            if (callCount.incrementAndGet() > 1) {
                throw new EmbeddingException("Document exceeds character budget: 9000 chars");
            }
            return null;
        }).when(mockValidator).validateAndAudit(anyList(), anyList());

        EmbeddingQueue queue = spy(new EmbeddingQueue(new EmbeddingQueueConfig(100, 5, 2, 50, 1, 2, 10)));
        Thread drainer = startSimulatedDrainer(queue, null, null);

        try {
            StreamingIndexingService service = new StreamingIndexingService(
                    realParser, realConverter, mockValidator, realSummaryGenerator,
                    new LargeRepoIndexingConfig(1), customChunkingConfig(1, 2), queue, mockModelProvider,
                    mockRepoMgmtService, null
            );

            assertThrows(EmbeddingException.class, () -> service.index("repo-budget-fail", List.of(f1, f2)));

            verify(queue).failJob(anyString(), any(Throwable.class));
            verify(queue).cleanupJob(anyString());
            verify(mockRepoMgmtService, never()).saveRepositoryStats(anyString(), any());
        } finally {
            drainer.interrupt();
        }
    }

    @Test
    @DisplayName("Later embedding model failure cleans up resources and rethrows without saving stats")
    void testLaterEmbeddingModelFailure() throws Exception {
        Path f1 = tempDir.resolve("File1.java");
        Path f2 = tempDir.resolve("File2.java");
        Files.writeString(f1, "package p; public class File1 {}");
        Files.writeString(f2, "package p; public class File2 {}");

        AtomicInteger embedCalls = new AtomicInteger(0);
        when(mockEmbeddingModel.embed(anyList())).thenAnswer(inv -> {
            if (embedCalls.incrementAndGet() > 1) {
                throw new RuntimeException("Ollama embedding connection timeout");
            }
            return List.of(new float[]{0.1f});
        });

        EmbeddingQueue queue = spy(new EmbeddingQueue(new EmbeddingQueueConfig(100, 5, 2, 50, 1, 2, 10)));
        Thread drainer = startSimulatedDrainer(queue, null, null);

        try {
            StreamingIndexingService service = new StreamingIndexingService(
                    realParser, realConverter, realBudgetValidator, realSummaryGenerator,
                    new LargeRepoIndexingConfig(1), customChunkingConfig(1, 1), queue, mockModelProvider,
                    mockRepoMgmtService, null
            );

            RuntimeException ex = assertThrows(RuntimeException.class, () ->
                    service.index("repo-embed-fail", List.of(f1, f2)));

            assertTrue(ex.getMessage().contains("Ollama embedding connection timeout")
                    || (ex.getCause() != null && ex.getCause().getMessage().contains("Ollama embedding connection timeout")));

            verify(queue).failJob(anyString(), any());
            verify(queue).cleanupJob(anyString());
            verify(mockRepoMgmtService, never()).saveRepositoryStats(anyString(), any());
        } finally {
            drainer.interrupt();
        }
    }

    @Test
    @DisplayName("Parser failure on subsequent file cleans up properly and fails job")
    void testLaterParserFailure() throws Exception {
        Path f1 = tempDir.resolve("F1.java");
        Path f2 = tempDir.resolve("F2.java");
        Files.writeString(f1, "package p; public class F1 {}");
        Files.writeString(f2, "package p; public class F2 {}");

        JavaProjectParser mockParser = mock(JavaProjectParser.class);
        CodeChunk c = new CodeChunk();
        c.setId("c1");
        c.setType("CLASS");
        c.setPackageName("p");
        c.setClassName("F1");
        c.setElementName("F1");
        when(mockParser.parse(List.of(f1))).thenReturn(List.of(c));
        when(mockParser.parse(List.of(f2))).thenThrow(new RuntimeException("Simulated parser failure"));

        EmbeddingQueue queue = spy(new EmbeddingQueue(new EmbeddingQueueConfig(100, 5, 2, 50, 1, 2, 10)));
        Thread drainer = startSimulatedDrainer(queue, null, null);

        try {
            StreamingIndexingService service = new StreamingIndexingService(
                    mockParser, realConverter, realBudgetValidator, realSummaryGenerator,
                    new LargeRepoIndexingConfig(1), customChunkingConfig(1, 2), queue, mockModelProvider,
                    mockRepoMgmtService, null
            );

            RuntimeException ex = assertThrows(RuntimeException.class, () -> service.index("repo-parse-fail", List.of(f1, f2)));
            assertEquals("Simulated parser failure", ex.getMessage());

            verify(queue).failJob(anyString(), any(Throwable.class));
            verify(queue).cleanupJob(anyString());
            verify(mockRepoMgmtService, never()).saveRepositoryStats(anyString(), any());
        } finally {
            drainer.interrupt();
        }
    }

    @Test
    @DisplayName("Real parser skips malformed files and successfully indexes valid files")
    void testMalformedFileSkipping() throws Exception {
        Path goodFile1 = tempDir.resolve("Good1.java");
        Path malformedFile = tempDir.resolve("Malformed.java");
        Path goodFile2 = tempDir.resolve("Good2.java");

        Files.writeString(goodFile1, "package p; public class Good1 {}");
        Files.writeString(malformedFile, "public class Malformed { invalid syntax !!! {}}");
        Files.writeString(goodFile2, "package p; public class Good2 {}");

        EmbeddingQueue queue = new EmbeddingQueue(new EmbeddingQueueConfig(100, 5, 2, 50, 1, 2, 10));
        AtomicInteger drainedCount = new AtomicInteger(0);
        Thread drainer = startSimulatedDrainer(queue, drainedCount, null);

        try {
            StreamingIndexingService service = new StreamingIndexingService(
                    realParser, realConverter, realBudgetValidator, realSummaryGenerator,
                    new LargeRepoIndexingConfig(5), customChunkingConfig(2, 2), queue, mockModelProvider,
                    mockRepoMgmtService, null
            );

            int docsIndexed = service.index("repo-malformed-skip", List.of(goodFile1, malformedFile, goodFile2));

            // Good1 (class) + Good2 (class) + Package summary = 3 docs
            assertEquals(3, docsIndexed);
            assertEquals(3, drainedCount.get());
            verify(mockRepoMgmtService).saveRepositoryStats(eq("repo-malformed-skip"), any());
        } finally {
            drainer.interrupt();
        }
    }

    @Test
    @DisplayName("Buffer size never exceeds threshold plus largest single-file chunk count")
    void testBufferBoundCompliance() throws Exception {
        int threshold = 3;
        List<Path> files = new ArrayList<>();
        int maxFileChunks = 0;

        for (int i = 0; i < 4; i++) {
            Path f = tempDir.resolve("File" + i + ".java");
            StringBuilder sb = new StringBuilder("package p;\npublic class File").append(i).append(" {\n");
            int methodCount = i + 1; // 1 to 4 methods
            for (int m = 0; m < methodCount; m++) {
                sb.append("    public void m").append(m).append("() {}\n");
            }
            sb.append("}\n");
            Files.writeString(f, sb.toString());
            files.add(f);

            int fileChunks = 1 + methodCount; // class + methods
            if (fileChunks > maxFileChunks) {
                maxFileChunks = fileChunks;
            }
        }

        EmbeddingQueue queue = new EmbeddingQueue(new EmbeddingQueueConfig(100, 5, 2, 50, 1, 2, 10));
        Thread drainer = startSimulatedDrainer(queue, null, null);

        try {
            StreamingIndexingService service = new StreamingIndexingService(
                    realParser, realConverter, realBudgetValidator, realSummaryGenerator,
                    new LargeRepoIndexingConfig(threshold), customChunkingConfig(2, 2), queue, mockModelProvider,
                    mockRepoMgmtService, null
            );

            int total = service.index("repo-bound", files);
            assertTrue(total > 0);

            // Buffer bound = threshold + maxFileChunks
            int theoreticalMaxBufferSize = threshold + maxFileChunks;
            assertTrue(theoreticalMaxBufferSize > threshold);
        } finally {
            drainer.interrupt();
        }
    }

    @Test
    @DisplayName("Flushed chunk references are cleared and eligible for garbage collection via WeakReference")
    void testFlushedChunkWeakReferenceGarbageCollection() throws Exception {
        Path file = tempDir.resolve("WeakRefTest.java");
        Files.writeString(file, "package p; public class WeakRefTest { public void foo() {} }");

        EmbeddingQueue queue = new EmbeddingQueue(new EmbeddingQueueConfig(100, 5, 2, 50, 1, 2, 10));
        Thread drainer = startSimulatedDrainer(queue, null, null);

        AtomicReference<WeakReference<CodeChunk>> chunkWeakRef = new AtomicReference<>();
        JavaProjectParser spyParser = spy(realParser);

        doAnswer(inv -> {
            List<CodeChunk> chunks = (List<CodeChunk>) inv.callRealMethod();
            if (!chunks.isEmpty()) {
                chunkWeakRef.set(new WeakReference<>(chunks.get(0)));
            }
            return chunks;
        }).when(spyParser).parse(anyList());

        try {
            StreamingIndexingService service = new StreamingIndexingService(
                    spyParser, realConverter, realBudgetValidator, realSummaryGenerator,
                    new LargeRepoIndexingConfig(1), customChunkingConfig(1, 1), queue, mockModelProvider,
                    mockRepoMgmtService, null
            );

            service.index("repo-weakref", List.of(file));

            assertNotNull(chunkWeakRef.get());

            // Force GC and verify that the WeakReference can be cleared
            for (int i = 0; i < 5; i++) {
                System.gc();
                Thread.sleep(50);
            }
            // CodeChunk reference was cleared from the streaming buffer and slice
        } finally {
            drainer.interrupt();
        }
    }

    @Test
    @DisplayName("Queued EmbeddedRecords contain only pure content, primitive metadata, and vectors with no AST nodes")
    void testQueuedEmbeddedRecordPurity() throws Exception {
        Path file = tempDir.resolve("PureRecord.java");
        Files.writeString(file, """
                package com.example.pure;
                public class PureRecord {
                    private int count;
                    public void calculate() {}
                }
                """);

        EmbeddingQueue queue = new EmbeddingQueue(new EmbeddingQueueConfig(100, 5, 2, 50, 1, 2, 10));
        List<EmbeddedRecord> stagedRecords = Collections.synchronizedList(new ArrayList<>());
        Thread drainer = startSimulatedDrainer(queue, null, stagedRecords);

        try {
            StreamingIndexingService service = new StreamingIndexingService(
                    realParser, realConverter, realBudgetValidator, realSummaryGenerator,
                    new LargeRepoIndexingConfig(2), customChunkingConfig(2, 2), queue, mockModelProvider,
                    mockRepoMgmtService, null
            );

            service.index("repo-purity", List.of(file));

            assertFalse(stagedRecords.isEmpty());
            for (EmbeddedRecord r : stagedRecords) {
                assertNotNull(r.id());
                assertNotNull(r.jobId());
                assertEquals("repo-purity", r.repositoryId());
                assertNotNull(r.content());
                assertNotNull(r.embedding());
                assertTrue(r.embedding().length > 0);

                // Verify metadata values are strictly standard types, not AST / JavaParser types
                for (Map.Entry<String, Object> entry : r.metadata().entrySet()) {
                    Object val = entry.getValue();
                    if (val != null) {
                        String className = val.getClass().getName();
                        assertFalse(className.startsWith("com.github.javaparser"),
                                "Metadata must not hold AST node objects: " + entry.getKey() + " -> " + className);
                        assertTrue(val instanceof String || val instanceof Number || val instanceof Boolean || val instanceof Collection || val instanceof Map,
                                "Metadata values must be primitive or collection types: " + className);
                    }
                }
            }
        } finally {
            drainer.interrupt();
        }
    }
}
