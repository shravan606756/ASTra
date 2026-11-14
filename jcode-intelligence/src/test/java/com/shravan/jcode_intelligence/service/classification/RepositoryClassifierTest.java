package com.shravan.jcode_intelligence.service.classification;

import com.shravan.jcode_intelligence.config.MemoryClassificationConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class RepositoryClassifierTest {

    @Test
    public void testClassificationMemoryBoundaries() {
        // budgetFraction = 0.4, bytesPerLoc = 500, maxHeap = 1,000,000 bytes
        // budget = 1,000,000 * 0.4 = 400,000 bytes
        MaxHeapProvider heapProvider = () -> 1_000_000L;
        MemoryClassificationConfig config = new MemoryClassificationConfig(0.4, 500L, null);
        RepositoryClassifier classifier = new RepositoryClassifier(new LocAnalyzer(), heapProvider, config);

        List<Path> dummyFiles = List.of(Path.of("A.java"));

        // 1. Need strictly below budget: 700 LOC * 500 = 350,000 <= 400,000 -> NORMAL
        LocAnalysis analysisBelow = new LocAnalysis(700L, dummyFiles);
        RepositoryClassificationResult resBelow = classifier.classify(analysisBelow);
        assertEquals(RepositoryClass.NORMAL, resBelow.classification());
        assertEquals(350_000L, resBelow.estimatedNeedBytes());
        assertEquals(400_000L, resBelow.budgetBytes());
        assertEquals(1_000_000L, resBelow.maxHeapBytes());
        assertFalse(resBelow.overrideUsed());

        // 2. Need exactly equal to budget: 800 LOC * 500 = 400,000 == 400,000 -> NORMAL
        LocAnalysis analysisEqual = new LocAnalysis(800L, dummyFiles);
        RepositoryClassificationResult resEqual = classifier.classify(analysisEqual);
        assertEquals(RepositoryClass.NORMAL, resEqual.classification());
        assertEquals(400_000L, resEqual.estimatedNeedBytes());
        assertEquals(400_000L, resEqual.budgetBytes());
        assertFalse(resEqual.overrideUsed());

        // 3. Need strictly above budget: 801 LOC * 500 = 400,500 > 400,000 -> LARGE
        LocAnalysis analysisAbove = new LocAnalysis(801L, dummyFiles);
        RepositoryClassificationResult resAbove = classifier.classify(analysisAbove);
        assertEquals(RepositoryClass.LARGE, resAbove.classification());
        assertEquals(400_500L, resAbove.estimatedNeedBytes());
        assertEquals(400_000L, resAbove.budgetBytes());
        assertFalse(resAbove.overrideUsed());
    }

    @Test
    public void testSameRepositoryOnDifferentHeapSizes() {
        // Repository with 10,000 LOC, bytesPerLoc = 600 -> estimatedNeed = 6,000,000 bytes (~6 MB)
        MemoryClassificationConfig config = new MemoryClassificationConfig(0.4, 600L, null);
        LocAnalysis analysis = new LocAnalysis(10_000L, List.of(Path.of("Main.java")));

        // On a large heap (2 GB = 2,147,483,648 bytes, budget = ~859 MB): NORMAL
        MaxHeapProvider largeHeap = () -> 2L * 1024 * 1024 * 1024;
        RepositoryClassifier largeClassifier = new RepositoryClassifier(new LocAnalyzer(), largeHeap, config);
        RepositoryClassificationResult resLarge = largeClassifier.classify(analysis);
        assertEquals(RepositoryClass.NORMAL, resLarge.classification());
        assertFalse(resLarge.overrideUsed());

        // On a small heap (10 MB = 10,485,760 bytes, budget = ~4.19 MB < 6 MB): LARGE
        MaxHeapProvider smallHeap = () -> 10L * 1024 * 1024;
        RepositoryClassifier smallClassifier = new RepositoryClassifier(new LocAnalyzer(), smallHeap, config);
        RepositoryClassificationResult resSmall = smallClassifier.classify(analysis);
        assertEquals(RepositoryClass.LARGE, resSmall.classification());
        assertFalse(resSmall.overrideUsed());
    }

    @Test
    public void testOverrideBoundaries() {
        // maxHeap = 1 GB, bytesPerLoc = 600, threshold = 5,000 LOC
        MaxHeapProvider heapProvider = () -> 1024L * 1024 * 1024;
        MemoryClassificationConfig config = new MemoryClassificationConfig(0.4, 600L, 5_000L);
        RepositoryClassifier classifier = new RepositoryClassifier(new LocAnalyzer(), heapProvider, config);

        List<Path> dummyFiles = List.of(Path.of("Test.java"));

        // Below threshold -> NORMAL
        RepositoryClassificationResult resBelow = classifier.classify(new LocAnalysis(4_999L, dummyFiles));
        assertEquals(RepositoryClass.NORMAL, resBelow.classification());
        assertTrue(resBelow.overrideUsed());

        // Exactly equal to threshold -> NORMAL
        RepositoryClassificationResult resEqual = classifier.classify(new LocAnalysis(5_000L, dummyFiles));
        assertEquals(RepositoryClass.NORMAL, resEqual.classification());
        assertTrue(resEqual.overrideUsed());

        // Above threshold -> LARGE
        RepositoryClassificationResult resAbove = classifier.classify(new LocAnalysis(5_001L, dummyFiles));
        assertEquals(RepositoryClass.LARGE, resAbove.classification());
        assertTrue(resAbove.overrideUsed());
    }

    @Test
    public void testOverrideContradictsMemoryRule() {
        // Scenario A: Memory budget says LARGE (need = 60 MB, budget = 40 MB),
        // but override threshold = 200,000 LOC, totalLoc = 100,000 LOC -> classifies NORMAL
        MaxHeapProvider heapProviderA = () -> 100_000_000L; // 100 MB -> budget 40 MB
        MemoryClassificationConfig configA = new MemoryClassificationConfig(0.4, 600L, 200_000L);
        RepositoryClassifier classifierA = new RepositoryClassifier(new LocAnalyzer(), heapProviderA, configA);

        LocAnalysis analysisA = new LocAnalysis(100_000L, List.of(Path.of("A.java"))); // need = 60 MB > 40 MB
        RepositoryClassificationResult resA = classifierA.classify(analysisA);

        assertEquals(RepositoryClass.NORMAL, resA.classification());
        assertTrue(resA.overrideUsed());
        assertEquals(60_000_000L, resA.estimatedNeedBytes());
        assertEquals(40_000_000L, resA.budgetBytes());

        // Scenario B: Memory budget says NORMAL (need = 6 MB, budget = 400 MB),
        // but override threshold = 5,000 LOC, totalLoc = 10,000 LOC -> classifies LARGE
        MaxHeapProvider heapProviderB = () -> 1_000_000_000L; // 1 GB -> budget 400 MB
        MemoryClassificationConfig configB = new MemoryClassificationConfig(0.4, 600L, 5_000L);
        RepositoryClassifier classifierB = new RepositoryClassifier(new LocAnalyzer(), heapProviderB, configB);

        LocAnalysis analysisB = new LocAnalysis(10_000L, List.of(Path.of("B.java"))); // need = 6 MB <= 400 MB
        RepositoryClassificationResult resB = classifierB.classify(analysisB);

        assertEquals(RepositoryClass.LARGE, resB.classification());
        assertTrue(resB.overrideUsed());
        assertEquals(6_000_000L, resB.estimatedNeedBytes());
        assertEquals(400_000_000L, resB.budgetBytes());
    }

    @Test
    public void testAllResultFieldsPopulated() {
        MaxHeapProvider heapProvider = () -> 500_000_000L;
        MemoryClassificationConfig config = new MemoryClassificationConfig(0.4, 600L, null);
        RepositoryClassifier classifier = new RepositoryClassifier(new LocAnalyzer(), heapProvider, config);

        List<Path> files = List.of(Path.of("src/A.java"), Path.of("src/B.java"));
        LocAnalysis analysis = new LocAnalysis(1500L, files);

        RepositoryClassificationResult result = classifier.classify(analysis);

        assertEquals(RepositoryClass.NORMAL, result.classification());
        assertEquals(1500L, result.totalLoc());
        assertEquals(2, result.eligibleFileCount());
        assertEquals(files, result.eligibleFiles());
        assertEquals(500_000_000L, result.maxHeapBytes());
        assertEquals(200_000_000L, result.budgetBytes());
        assertEquals(900_000L, result.estimatedNeedBytes());
        assertFalse(result.overrideUsed());

        // Verify immutability of eligibleFiles
        assertThrows(UnsupportedOperationException.class, () -> result.eligibleFiles().add(Path.of("C.java")));
    }

    @Test
    public void testOverflowSafety() {
        MaxHeapProvider heapProvider = () -> 1_000_000_000L; // budget = 400 MB
        MemoryClassificationConfig config = new MemoryClassificationConfig(0.4, 600L, null);
        RepositoryClassifier classifier = new RepositoryClassifier(new LocAnalyzer(), heapProvider, config);

        // A massive LOC count that would overflow long multiplication: (Long.MAX_VALUE / 2) * 600
        long hugeLoc = Long.MAX_VALUE / 2;
        LocAnalysis analysis = new LocAnalysis(hugeLoc, List.of());

        RepositoryClassificationResult result = classifier.classify(analysis);

        assertEquals(RepositoryClass.LARGE, result.classification());
        assertEquals(Long.MAX_VALUE, result.estimatedNeedBytes(), "Need should saturate to Long.MAX_VALUE on overflow");
        assertEquals(400_000_000L, result.budgetBytes());
        assertFalse(result.overrideUsed());
    }

    @Test
    public void testLongMaxHeapHandling() {
        // maxHeap = Long.MAX_VALUE
        MaxHeapProvider maxHeapProvider = () -> Long.MAX_VALUE;
        MemoryClassificationConfig config = new MemoryClassificationConfig(0.4, 600L, null);
        RepositoryClassifier classifier = new RepositoryClassifier(new LocAnalyzer(), maxHeapProvider, config);

        LocAnalysis normalAnalysis = new LocAnalysis(10_000L, List.of());
        RepositoryClassificationResult result = classifier.classify(normalAnalysis);

        assertEquals(RepositoryClass.NORMAL, result.classification());
        assertEquals(Long.MAX_VALUE, result.maxHeapBytes());
        assertTrue(result.budgetBytes() > 0 && result.budgetBytes() < Long.MAX_VALUE);
        assertEquals(6_000_000L, result.estimatedNeedBytes());

        // Budget fraction 1.0 with Long.MAX_VALUE maxHeap
        MemoryClassificationConfig fullConfig = new MemoryClassificationConfig(1.0, 600L, null);
        RepositoryClassifier fullClassifier = new RepositoryClassifier(new LocAnalyzer(), maxHeapProvider, fullConfig);
        RepositoryClassificationResult fullResult = fullClassifier.classify(normalAnalysis);
        assertEquals(Long.MAX_VALUE, fullResult.budgetBytes());
    }

    @Test
    public void testClassifyWithPathRoot(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("App.java");
        Files.writeString(file, "public class App {\n    int a;\n}\n");

        MaxHeapProvider heapProvider = () -> 100_000_000L;
        MemoryClassificationConfig config = new MemoryClassificationConfig(0.4, 600L, null);
        RepositoryClassifier classifier = new RepositoryClassifier(new LocAnalyzer(), heapProvider, config);

        RepositoryClassificationResult result = classifier.classify(tempDir);

        assertEquals(RepositoryClass.NORMAL, result.classification());
        assertEquals(3L, result.totalLoc());
        assertEquals(1, result.eligibleFileCount());
        assertEquals(1, result.eligibleFiles().size());
        assertEquals(file, result.eligibleFiles().get(0));
    }
}
