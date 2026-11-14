package com.shravan.jcode_intelligence.calibration;

import com.github.javaparser.JavaParser;
import com.shravan.jcode_intelligence.config.ChunkingConfig;
import com.shravan.jcode_intelligence.config.JavaParserConfig;
import com.shravan.jcode_intelligence.config.MemoryClassificationConfig;
import com.shravan.jcode_intelligence.model.CodeChunk;
import com.shravan.jcode_intelligence.parser.AstVisitor;
import com.shravan.jcode_intelligence.parser.CharBasedBudgetEstimator;
import com.shravan.jcode_intelligence.parser.ClassSummaryBuilder;
import com.shravan.jcode_intelligence.parser.ChunkGenerator;
import com.shravan.jcode_intelligence.parser.IndexableJavaFileFilter;
import com.shravan.jcode_intelligence.parser.JavaProjectParser;
import com.shravan.jcode_intelligence.parser.JavaSourceDiscovery;
import com.shravan.jcode_intelligence.parser.MetadataExtractor;
import com.shravan.jcode_intelligence.parser.MethodFragmenter;
import com.shravan.jcode_intelligence.parser.spi.ChunkBudgetEstimator;
import com.shravan.jcode_intelligence.service.classification.LocAnalysis;
import com.shravan.jcode_intelligence.service.classification.LocAnalyzer;
import com.shravan.jcode_intelligence.service.classification.MaxHeapProvider;
import com.shravan.jcode_intelligence.service.classification.RepositoryClass;
import com.shravan.jcode_intelligence.service.classification.RepositoryClassificationResult;
import com.shravan.jcode_intelligence.service.classification.RepositoryClassifier;
import com.sun.management.GarbageCollectionNotificationInfo;
import com.sun.management.GcInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in memory calibration harness and verification test for JavaParser.
 *
 * <p>Activated only when {@code -Dastra.calibration.javaparser.path=/path/to/javaparser} is specified.
 * Measures post-GC live heap occupancy during AST parsing and chunk generation to calibrate
 * {@code astra.indexing.memory.bytes-per-loc}.
 */
@EnabledIfSystemProperty(named = "astra.calibration.javaparser.path", matches = ".+")
public class JavaParserMemoryCalibrationTest {

    /** Calibration heap size used for reproducible verification: 512 MiB (>= need / 0.4). */
    public static final long CALIBRATION_HEAP_BYTES = 512L * 1024L * 1024L;

    @Test
    @DisplayName("Calibrate bytes-per-loc using real JavaParser components and post-GC live heap")
    void testCalibrateBytesPerLoc() throws Exception {
        String pathStr = System.getProperty("astra.calibration.javaparser.path");
        assertNotNull(pathStr, "System property 'astra.calibration.javaparser.path' must be provided");
        Path repoRoot = Path.of(pathStr);
        assertTrue(Files.exists(repoRoot), "Configured JavaParser path does not exist: " + repoRoot);

        // 1. Wire real parser and discovery components (matching StandaloneJavaProjectParserTest)
        JavaParserConfig configFactory = new JavaParserConfig();
        JavaParser javaParser = configFactory.javaParser();
        MetadataExtractor metadataExtractor = new MetadataExtractor();
        ClassSummaryBuilder summaryBuilder = new ClassSummaryBuilder();
        ChunkingConfig chunkingConfig = new ChunkingConfig() {
            @Override
            public int getMaxMethodChars() {
                return 6000;
            }

            @Override
            public int getMaxFragmentChars() {
                return 3000;
            }
        };
        ChunkBudgetEstimator budgetEstimator = new CharBasedBudgetEstimator(chunkingConfig);
        MethodFragmenter methodFragmenter = new MethodFragmenter(metadataExtractor, budgetEstimator);
        ChunkGenerator chunkGenerator = new ChunkGenerator(
                metadataExtractor, summaryBuilder, methodFragmenter, budgetEstimator);
        AstVisitor astVisitor = new AstVisitor(chunkGenerator);
        IndexableJavaFileFilter filter = new IndexableJavaFileFilter();
        JavaSourceDiscovery discovery = new JavaSourceDiscovery(filter);
        JavaProjectParser parser = new JavaProjectParser(javaParser, astVisitor, discovery);
        LocAnalyzer locAnalyzer = new LocAnalyzer(discovery);

        // 2. Discover files and calculate total LOC
        LocAnalysis locAnalysis = locAnalyzer.analyze(repoRoot);
        long totalLoc = locAnalysis.totalLoc();
        List<Path> eligibleFiles = locAnalysis.eligibleFiles();

        assertTrue(totalLoc > 0, "Discovered LOC must be positive");
        assertFalse(eligibleFiles.isEmpty(), "Eligible files must not be empty");

        // 3. Request GC until baseline post-GC occupancy is stable
        long baselineHeap = measureStablePostGcHeap();

        // 4. Register GarbageCollectionNotificationInfo listener to track peak post-GC live heap
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

        List<CodeChunk> chunks = null;
        try {
            // 5. Parse eligible files into chunks while GC listener tracks post-GC peaks
            chunks = parser.parse(eligibleFiles);
        } finally {
            // Unregister GC listener
            for (NotificationEmitter emitter : emitters) {
                try {
                    emitter.removeNotificationListener(gcListener);
                } catch (Exception ignored) {
                }
            }
        }

        // Keep chunks reachable until measurement ends
        assertNotNull(chunks, "Generated chunks must not be null");
        assertFalse(chunks.isEmpty(), "Generated chunks must not be empty");
        int chunkCount = chunks.size();

        // Require at least one GC during the stage
        assertTrue(gcCount.get() > 0,
                "No GC occurred during parsing (GC count = 0). A valid post-GC peak requires at least one GC. "
                        + "Please run with a smaller -Xmx to trigger garbage collection during parsing.");

        long peak = peakPostGcHeap.get();
        assertTrue(peak > baselineHeap,
                "Peak post-GC heap (" + peak + ") must exceed baseline (" + baselineHeap + ")");

        long deltaBytes = peak - baselineHeap;
        long rawBytesPerLoc = deltaBytes / totalLoc;
        long marginedBytesPerLoc = Math.round(rawBytesPerLoc * 1.25);
        long maxMemory = Runtime.getRuntime().maxMemory();
        long minCalibrationHeap = Math.round((deltaBytes * 1.25) / 0.4);

        String commit = resolveCommit(repoRoot);
        String gcName = getGcNames();

        // 6. Print calibration report
        System.out.println("================================================================================");
        System.out.println("ASTra Memory Calibration Report (JavaParser)");
        System.out.println("================================================================================");
        System.out.println("Repository Root          : " + repoRoot.toAbsolutePath());
        System.out.println("Pinned Commit / Tag      : " + commit);
        System.out.println("Total LOC (non-blank)    : " + totalLoc);
        System.out.println("Eligible Source Files    : " + eligibleFiles.size());
        System.out.println("Parsed Chunks Generated  : " + chunkCount);
        System.out.println("JVM Version              : " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")");
        System.out.println("Garbage Collector        : " + gcName);
        System.out.println("Runtime maxMemory()      : " + maxMemory + " bytes (" + (maxMemory / 1024 / 1024) + " MB)");
        System.out.println("Baseline Post-GC Heap    : " + baselineHeap + " bytes (" + (baselineHeap / 1024 / 1024) + " MB)");
        System.out.println("Peak Post-GC Live Heap   : " + peak + " bytes (" + (peak / 1024 / 1024) + " MB)");
        System.out.println("Live Heap Delta (Peak-B) : " + deltaBytes + " bytes (" + (deltaBytes / 1024 / 1024) + " MB)");
        System.out.println("GC Cycles Observed       : " + gcCount.get());
        System.out.println("Raw bytes-per-loc        : " + rawBytesPerLoc + " bytes/LOC");
        System.out.println("Margined bytes-per-loc   : " + marginedBytesPerLoc + " bytes/LOC (×1.25 safety margin)");
        System.out.println("Min Calibration Heap     : " + minCalibrationHeap + " bytes (~" + (minCalibrationHeap / 1024 / 1024) + " MB)");
        System.out.println("Note: Cross-check recommended with JVM flag: -Xlog:gc*");
        System.out.println("================================================================================");
    }

    @Test
    @DisplayName("Verify JavaParser is classified as NORMAL with default config and calibration heap")
    void testJavaParserClassifiedNormalUnderDefaultConfig() throws IOException {
        String pathStr = System.getProperty("astra.calibration.javaparser.path");
        assertNotNull(pathStr, "System property 'astra.calibration.javaparser.path' must be provided");
        Path repoRoot = Path.of(pathStr);
        assertTrue(Files.exists(repoRoot), "Configured JavaParser path does not exist: " + repoRoot);

        JavaSourceDiscovery discovery = new JavaSourceDiscovery(new IndexableJavaFileFilter());
        LocAnalyzer locAnalyzer = new LocAnalyzer(discovery);

        // Fake provider returning calibration heap (512 MiB, which is >= need / 0.4)
        MaxHeapProvider fakeHeapProvider = () -> CALIBRATION_HEAP_BYTES;

        MemoryClassificationConfig config = new MemoryClassificationConfig();
        RepositoryClassifier classifier = new RepositoryClassifier(locAnalyzer, fakeHeapProvider, config);

        RepositoryClassificationResult result = classifier.classify(repoRoot);

        assertEquals(RepositoryClass.NORMAL, result.classification(),
                "JavaParser must be classified as NORMAL on the calibration heap");
        assertFalse(result.overrideUsed(), "Manual threshold override must not be used");
        assertTrue(result.totalLoc() > 0, "LOC must be positive");
        assertTrue(result.estimatedNeedBytes() <= result.budgetBytes(),
                "Estimated need (" + result.estimatedNeedBytes() + ") must be <= budget (" + result.budgetBytes() + ")");
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
        String commitProp = System.getProperty("astra.calibration.javaparser.commit");
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
        return "javaparser-3.28.2 (tag: javaparser-parent-3.28.2 / pinned commit d8c5f6e)";
    }
}
