package com.shravan.jcode_intelligence.calibration;

import com.github.javaparser.JavaParser;
import com.shravan.jcode_intelligence.config.ChunkingConfig;
import com.shravan.jcode_intelligence.config.EmbeddingQueueConfig;
import com.shravan.jcode_intelligence.config.JavaParserConfig;
import com.shravan.jcode_intelligence.config.LargeRepoIndexingConfig;
import com.shravan.jcode_intelligence.converter.DocumentConverter;
import com.shravan.jcode_intelligence.model.EmbeddedRecord;
import com.shravan.jcode_intelligence.parser.*;
import com.shravan.jcode_intelligence.parser.spi.ChunkBudgetEstimator;
import com.shravan.jcode_intelligence.service.BatchEmbeddingHelper;
import com.shravan.jcode_intelligence.service.EmbeddingQueue;
import com.shravan.jcode_intelligence.service.classification.LocAnalysis;
import com.shravan.jcode_intelligence.service.classification.LocAnalyzer;
import com.shravan.jcode_intelligence.service.streaming.StreamingIndexingService;
import com.sun.management.GarbageCollectionNotificationInfo;
import com.sun.management.GcInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;

import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Opt-in memory calibration and verification harness for the LARGE streaming indexing pipeline.
 *
 * <p>Activated only when {@code -Dastra.calibration.large-repo.path=/path/to/repo} is specified.
 * Forces the {@link StreamingIndexingService} pipeline, employs an in-memory counting drainer,
 * samples post-GC live heap via GC notifications, and reports peak memory, chunk totals, and batch count.
 *
 * <p>Recommended execution command with constrained heap:
 * <pre>{@code
 * mvn test -Dtest=LargeRepoStreamingMemoryCalibrationTest \
 *   -Dastra.calibration.large-repo.path=/path/to/large-repo \
 *   -DargLine="-Xmx512m -Xlog:gc*"
 * }</pre>
 */
@EnabledIfSystemProperty(named = "astra.calibration.large-repo.path", matches = ".+")
public class LargeRepoStreamingMemoryCalibrationTest {

    @Test
    @DisplayName("Calibrate and verify streaming memory behavior on large repository checkout")
    void testCalibrateLargeRepoStreamingMemory() throws Exception {
        String pathStr = System.getProperty("astra.calibration.large-repo.path");
        assertNotNull(pathStr, "System property 'astra.calibration.large-repo.path' must be provided");
        Path repoRoot = Path.of(pathStr);
        assertTrue(Files.exists(repoRoot), "Configured repository path does not exist: " + repoRoot);

        // 1. Wire real parser and discovery components
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
        JavaProjectParser parser = new JavaProjectParser(javaParser, astVisitor, discovery);
        LocAnalyzer locAnalyzer = new LocAnalyzer(discovery);

        DocumentConverter converter = new DocumentConverter();
        EmbeddingBudgetValidator budgetValidator = new EmbeddingBudgetValidator(budgetEstimator);
        PackageSummaryGenerator packageSummaryGenerator = new PackageSummaryGenerator();

        // 2. Discover eligible files and total LOC
        LocAnalysis locAnalysis = locAnalyzer.analyze(repoRoot);
        long totalLoc = locAnalysis.totalLoc();
        List<Path> eligibleFiles = locAnalysis.eligibleFiles();

        assertTrue(totalLoc > 0, "Discovered LOC must be positive");
        assertFalse(eligibleFiles.isEmpty(), "Eligible files must not be empty");

        // 3. Configure streaming pipeline with large-repo threshold (default 2000 or system property)
        int configuredBatchSize = 2000;
        String batchSizeProp = System.getProperty("astra.indexing.large-repo.batch-size");
        if (batchSizeProp != null && !batchSizeProp.isBlank()) {
            try {
                configuredBatchSize = Integer.parseInt(batchSizeProp);
            } catch (NumberFormatException ignored) {
            }
        }
        LargeRepoIndexingConfig largeRepoConfig = new LargeRepoIndexingConfig(configuredBatchSize);

        EmbeddingQueueConfig queueConfig = new EmbeddingQueueConfig();
        EmbeddingQueue embeddingQueue = new EmbeddingQueue(queueConfig);

        // Deterministic mock/stub embedding model returning 1536-dimensional float vector without network
        EmbeddingModel dummyModel = org.mockito.Mockito.mock(EmbeddingModel.class);
        org.mockito.Mockito.when(dummyModel.embed(org.mockito.ArgumentMatchers.anyList())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            List<float[]> embeddings = new ArrayList<>(texts.size());
            float[] sample = new float[1536];
            for (int i = 0; i < texts.size(); i++) {
                embeddings.add(sample);
            }
            return embeddings;
        });
        ObjectProvider<EmbeddingModel> modelProvider = new ObjectProvider<>() {
            @Override
            public EmbeddingModel getObject(Object... args) {
                return dummyModel;
            }

            @Override
            public EmbeddingModel getIfAvailable() {
                return dummyModel;
            }

            @Override
            public EmbeddingModel getIfUnique() {
                return dummyModel;
            }

            @Override
            public EmbeddingModel getObject() {
                return dummyModel;
            }
        };

        // 4. Counting drainer in background
        AtomicInteger totalDrainedBatches = new AtomicInteger(0);
        AtomicInteger totalDrainedRecords = new AtomicInteger(0);
        AtomicBoolean drainerActive = new AtomicBoolean(true);

        Thread drainerThread = new Thread(() -> {
            try {
                while (drainerActive.get() && !Thread.currentThread().isInterrupted()) {
                    List<EmbeddedRecord> batch = embeddingQueue.drainBatch(200, 50);
                    if (batch != null && !batch.isEmpty()) {
                        totalDrainedBatches.incrementAndGet();
                        totalDrainedRecords.addAndGet(batch.size());
                        for (EmbeddedRecord r : batch) {
                            embeddingQueue.recordPersisted(r);
                        }
                    }
                    Thread.sleep(5);
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (Exception ignored) {
            }
        });
        drainerThread.setDaemon(true);
        drainerThread.start();

        // 5. Measure baseline post-GC heap
        long baselineHeap = measureStablePostGcHeap();

        // 6. Register GC notification listener
        AtomicLong peakPostGcHeap = new AtomicLong(baselineHeap);
        AtomicInteger gcCount = new AtomicInteger(0);
        List<NotificationEmitter> emitters = new ArrayList<>();

        NotificationListener gcListener = (notification, handback) -> {
            if (GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION.equals(notification.getType())) {
                GarbageCollectionNotificationInfo info = GarbageCollectionNotificationInfo.from(
                        (CompositeData) notification.getUserData());
                GcInfo gcInfo = info.getGcInfo();
                if (gcInfo != null) {
                    Map<String, MemoryUsage> afterUsage = gcInfo.getMemoryUsageAfterGc();
                    long currentHeapAfterGc = 0;
                    for (Map.Entry<String, MemoryUsage> entry : afterUsage.entrySet()) {
                        if (isHeapPool(entry.getKey())) {
                            currentHeapAfterGc += entry.getValue().getUsed();
                        }
                    }
                    if (currentHeapAfterGc > 0) {
                        peakPostGcHeap.accumulateAndGet(currentHeapAfterGc, Math::max);
                        gcCount.incrementAndGet();
                    }
                }
            }
        };

        for (GarbageCollectorMXBean gcBean : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (gcBean instanceof NotificationEmitter emitter) {
                emitter.addNotificationListener(gcListener, null, null);
                emitters.add(emitter);
            }
        }

        int submittedDocs = 0;
        long startTime = System.currentTimeMillis();
        boolean completedSampleObtained = false;

        try {
            StreamingIndexingService streamingService = new StreamingIndexingService(
                    parser, converter, budgetValidator, packageSummaryGenerator,
                    largeRepoConfig, chunkingConfig, embeddingQueue, modelProvider,
                    null, null
            );

            submittedDocs = streamingService.index("calibration-large-repo", eligibleFiles);

            System.gc();
            Thread.sleep(100);

            long completedPostGcHeap = sumPostGcHeapUsage();
            if (completedPostGcHeap > 0) {
                peakPostGcHeap.accumulateAndGet(completedPostGcHeap, Math::max);
                completedSampleObtained = true;
            }
        } finally {
            drainerActive.set(false);
            drainerThread.interrupt();

            for (NotificationEmitter emitter : emitters) {
                try {
                    emitter.removeNotificationListener(gcListener);
                } catch (Exception ignored) {
                }
            }
        }

        long durationMs = System.currentTimeMillis() - startTime;
        assertTrue(completedSampleObtained, "Failed to obtain post-GC completed sample");
        assertTrue(submittedDocs > 0, "Submitted documents must be positive");

        long peak = peakPostGcHeap.get();
        long deltaBytes = Math.max(0, peak - baselineHeap);
        long bytesPerLoc = totalLoc > 0 ? (deltaBytes / totalLoc) : 0;
        long maxMemory = Runtime.getRuntime().maxMemory();

        String commit = resolveCommit(repoRoot);
        String gcName = getGcNames();

        // 7. Output calibration report
        System.out.println("================================================================================");
        System.out.println("ASTra Large Repository Streaming Memory Calibration Report");
        System.out.println("================================================================================");
        System.out.println("Repository Root              : " + repoRoot.toAbsolutePath());
        System.out.println("Pinned Commit / Tag          : " + commit);
        System.out.println("Total LOC (non-blank)        : " + totalLoc);
        System.out.println("Eligible Source Files        : " + eligibleFiles.size());
        System.out.println("Streaming Batch Size (Slice) : " + configuredBatchSize);
        System.out.println("Total Chunks Submitted       : " + submittedDocs);
        System.out.println("Total Batches Drained        : " + totalDrainedBatches.get());
        System.out.println("Total Records Drained        : " + totalDrainedRecords.get());
        System.out.println("JVM Version                  : " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")");
        System.out.println("Garbage Collector            : " + gcName);
        System.out.println("Runtime maxMemory()          : " + maxMemory + " bytes (" + (maxMemory / 1024 / 1024) + " MB)");
        System.out.println("Baseline Post-GC Heap        : " + baselineHeap + " bytes (" + (baselineHeap / 1024 / 1024) + " MB)");
        System.out.println("Peak Post-GC Live Heap       : " + peak + " bytes (" + (peak / 1024 / 1024) + " MB)");
        System.out.println("Live Heap Delta (Peak - Base): " + deltaBytes + " bytes (" + (deltaBytes / 1024 / 1024) + " MB)");
        System.out.println("GC Cycles Observed           : " + gcCount.get());
        System.out.println("Streaming Peak bytes-per-loc : " + bytesPerLoc + " bytes/LOC");
        System.out.println("Elapsed Time                 : " + durationMs + " ms");
        System.out.println("================================================================================");
    }

    private static long measureStablePostGcHeap() throws InterruptedException {
        long prev = -1;
        long current = -1;
        for (int i = 0; i < 10; i++) {
            System.gc();
            Thread.sleep(100);
            current = sumPostGcHeapUsage();
            if (prev > 0 && Math.abs(current - prev) < 2 * 1024 * 1024) {
                break;
            }
            prev = current;
        }
        return current;
    }

    private static long sumPostGcHeapUsage() {
        long total = 0;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP) {
                MemoryUsage usage = pool.getCollectionUsage();
                if (usage != null && usage.getUsed() > 0) {
                    total += usage.getUsed();
                } else {
                    MemoryUsage current = pool.getUsage();
                    if (current != null) {
                        total += current.getUsed();
                    }
                }
            }
        }
        return total;
    }

    private static boolean isHeapPool(String poolName) {
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getName().equals(poolName)) {
                return pool.getType() == MemoryType.HEAP;
            }
        }
        return false;
    }

    private static String getGcNames() {
        List<String> names = new ArrayList<>();
        for (GarbageCollectorMXBean gcBean : ManagementFactory.getGarbageCollectorMXBeans()) {
            names.add(gcBean.getName());
        }
        return String.join(", ", names);
    }

    private static String resolveCommit(Path repoRoot) {
        String commitProp = System.getProperty("astra.calibration.large-repo.commit");
        if (commitProp != null && !commitProp.isBlank()) {
            return commitProp;
        }
        Path gitHead = repoRoot.resolve(".git").resolve("HEAD");
        if (Files.exists(gitHead)) {
            try {
                String headContent = Files.readString(gitHead).trim();
                if (headContent.startsWith("ref: ")) {
                    Path refPath = repoRoot.resolve(".git").resolve(headContent.substring(5).trim());
                    if (Files.exists(refPath)) {
                        return Files.readString(refPath).trim();
                    }
                }
                return headContent;
            } catch (Exception ignored) {
            }
        }
        return "unknown";
    }
}
