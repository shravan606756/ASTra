package com.shravan.jcode_intelligence.service.streaming;

import com.github.javaparser.JavaParser;
import com.shravan.jcode_intelligence.config.ChunkingConfig;
import com.shravan.jcode_intelligence.config.JavaParserConfig;
import com.shravan.jcode_intelligence.model.CodeChunk;
import com.shravan.jcode_intelligence.model.IndexingStatistics;
import com.shravan.jcode_intelligence.parser.*;
import com.shravan.jcode_intelligence.parser.spi.ChunkBudgetEstimator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Parity test suite verifying that streaming AST chunking, package summaries, and statistics
 * produce identical outputs to whole-repository batch processing across complex language features
 * (nested types, enums, records, fragmented methods/constructors), capping bounds, and tie handling.
 */
class StreamingIndexingParityTest {

    @TempDir
    Path tempDir;

    private JavaProjectParser parser;
    private PackageSummaryGenerator summaryGenerator;
    private IndexingStatisticsCalculator statsCalculator;

    @BeforeEach
    void setUp() {
        JavaParserConfig configFactory = new JavaParserConfig();
        JavaParser javaParser = configFactory.javaParser();
        MetadataExtractor metadataExtractor = new MetadataExtractor();
        ClassSummaryBuilder summaryBuilder = new ClassSummaryBuilder();
        ChunkingConfig chunkingConfig = new ChunkingConfig() {
            @Override
            public int getMaxMethodChars() {
                return 500; // Small threshold to trigger fragmentation in tests
            }

            @Override
            public int getMaxFragmentChars() {
                return 250;
            }
        };
        ChunkBudgetEstimator budgetEstimator = new CharBasedBudgetEstimator(chunkingConfig);
        MethodFragmenter methodFragmenter = new MethodFragmenter(metadataExtractor, budgetEstimator);
        ChunkGenerator chunkGenerator = new ChunkGenerator(
                metadataExtractor, summaryBuilder, methodFragmenter, budgetEstimator);
        AstVisitor astVisitor = new AstVisitor(chunkGenerator);
        IndexableJavaFileFilter filter = new IndexableJavaFileFilter();
        JavaSourceDiscovery discovery = new JavaSourceDiscovery(filter);
        parser = new JavaProjectParser(javaParser, astVisitor, discovery);
        summaryGenerator = new PackageSummaryGenerator();
        statsCalculator = new IndexingStatisticsCalculator();
    }

    @Test
    @DisplayName("Ordered CodeChunk fields parity between streaming accumulation and whole-repository parsing")
    void testOrderedCodeChunkFieldsParity() throws Exception {
        // 1. Nested types (inner class and interface)
        Path nestedFile = tempDir.resolve("Container.java");
        Files.writeString(nestedFile, """
                package com.example.complex;
                public class Container {
                    private int id;
                    public static class NestedClass {
                        public void nestedAction() {}
                    }
                    public interface NestedInterface {
                        void contractMethod();
                    }
                }
                """);

        // 2. Enum with custom methods and constants
        Path enumFile = tempDir.resolve("Status.java");
        Files.writeString(enumFile, """
                package com.example.complex;
                public enum Status {
                    INITIAL,
                    PROCESSING,
                    COMPLETED;
                    public boolean isTerminal() {
                        return this == COMPLETED;
                    }
                }
                """);

        // 3. Record
        Path recordFile = tempDir.resolve("UserData.java");
        Files.writeString(recordFile, """
                package com.example.complex;
                public record UserData(String username, String email, int level) {
                    public void validate() {}
                }
                """);

        // 4. Long method and constructor triggering fragmentation (> 500 chars)
        StringBuilder longBody = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            longBody.append("        int var_").append(i).append(" = ").append(i).append(" * 10;\n");
        }
        Path fragmentedFile = tempDir.resolve("Fragmented.java");
        Files.writeString(fragmentedFile, "package com.example.complex;\n" +
                "public class Fragmented {\n" +
                "    public Fragmented() {\n" +
                longBody +
                "    }\n" +
                "    public void veryLongMethod() {\n" +
                longBody +
                "    }\n" +
                "}\n");

        List<Path> files = List.of(nestedFile, enumFile, recordFile, fragmentedFile);

        // Whole-repository path:
        List<CodeChunk> wholeRepoChunks = parser.parse(files);
        List<CodeChunk> wholeRepoSummaries = summaryGenerator.generatePackageSummaries(wholeRepoChunks);
        List<CodeChunk> expectedTotal = new ArrayList<>(wholeRepoChunks);
        expectedTotal.addAll(wholeRepoSummaries);

        // Streaming path:
        PackageSummaryGenerator.Accumulator streamingAccumulator = summaryGenerator.createAccumulator();
        List<CodeChunk> streamedChunks = new ArrayList<>();

        for (Path file : files) {
            List<CodeChunk> fileChunks = parser.parse(List.of(file));
            streamingAccumulator.accumulate(fileChunks);
            streamedChunks.addAll(fileChunks);
        }
        List<CodeChunk> streamedSummaries = streamingAccumulator.generateSummaries();
        streamedChunks.addAll(streamedSummaries);

        // Assert size equality
        assertEquals(expectedTotal.size(), streamedChunks.size(),
                "Streamed total chunks must match whole-repo chunks");

        // Assert field-by-field equality in order
        for (int i = 0; i < expectedTotal.size(); i++) {
            CodeChunk exp = expectedTotal.get(i);
            CodeChunk act = streamedChunks.get(i);

            assertEquals(exp.getType(), act.getType(), "Type mismatch at index " + i);
            assertEquals(exp.getPackageName(), act.getPackageName(), "Package mismatch at index " + i);
            assertEquals(exp.getClassName(), act.getClassName(), "ClassName mismatch at index " + i);
            assertEquals(exp.getElementName(), act.getElementName(), "ElementName mismatch at index " + i);
            assertEquals(exp.getOriginalElementLength(), act.getOriginalElementLength(), "Length mismatch at index " + i);
            assertEquals(exp.getNestingDepth(), act.getNestingDepth(), "NestingDepth mismatch at index " + i);
            assertEquals(exp.getStartLine(), act.getStartLine(), "StartLine mismatch at index " + i);
            assertEquals(exp.getEndLine(), act.getEndLine(), "EndLine mismatch at index " + i);
            assertEquals(exp.getContent(), act.getContent(), "Content mismatch at index " + i);
        }
    }

    @Test
    @DisplayName("Package summary 100-type cap parity between accumulator and batch generator")
    void testPackageSummary100TypeCapParity() {
        List<CodeChunk> chunks = new ArrayList<>();
        // Generate 105 distinct top-level classes in the same package
        for (int i = 0; i < 105; i++) {
            CodeChunk c = new CodeChunk();
            c.setId("chunk-" + i);
            c.setType("CLASS");
            c.setPackageName("com.example.cap");
            c.setClassName("Type" + i);
            c.setElementName("Type" + i);
            c.setNestingDepth(0);
            c.setOriginalElementLength(100);
            c.setContent("public class Type" + i + " {}");
            chunks.add(c);
        }

        // Whole-repository generator
        List<CodeChunk> batchSummaries = summaryGenerator.generatePackageSummaries(chunks);
        assertEquals(1, batchSummaries.size());
        CodeChunk batchSummary = batchSummaries.get(0);

        // Streaming accumulator
        PackageSummaryGenerator.Accumulator accumulator = summaryGenerator.createAccumulator();
        // Accumulate in slices
        accumulator.accumulate(chunks.subList(0, 50));
        accumulator.accumulate(chunks.subList(50, 105));
        List<CodeChunk> streamingSummaries = accumulator.generateSummaries();
        assertEquals(1, streamingSummaries.size());
        CodeChunk streamingSummary = streamingSummaries.get(0);

        // Verify identical output
        assertEquals(batchSummary.getContent(), streamingSummary.getContent(),
                "Summary content with 100-type cap must be identical");
        assertEquals(batchSummary.getElementName(), streamingSummary.getElementName());
        assertEquals(batchSummary.getPackageName(), streamingSummary.getPackageName());

        // Verify accumulator data map type counts
        assertEquals(105, accumulator.getPackageDataMap().get("com.example.cap").getTotalTopLevelTypes());

        // Verify content contains Type0 to Type99 and does NOT contain Type100
        assertTrue(streamingSummary.getContent().contains("Type0"));
        assertTrue(streamingSummary.getContent().contains("Type99"));
        assertFalse(streamingSummary.getContent().contains("Type100"));
        assertTrue(streamingSummary.getContent().contains("5 additional top-level types omitted"));
    }

    @Test
    @DisplayName("Member-only package summary parity with 0 top-level types")
    void testMemberOnlyPackageSummaryParity() {
        List<CodeChunk> chunks = new ArrayList<>();

        CodeChunk field = new CodeChunk();
        field.setId("f1");
        field.setType("FIELD");
        field.setPackageName("com.example.members");
        field.setClassName("MemberClass");
        field.setElementName("myField");
        field.setNestingDepth(1);
        field.setContent("int myField = 1;");
        chunks.add(field);

        CodeChunk method = new CodeChunk();
        method.setId("m1");
        method.setType("METHOD");
        method.setPackageName("com.example.members");
        method.setClassName("MemberClass");
        method.setElementName("myMethod");
        method.setNestingDepth(1);
        method.setContent("void myMethod() {}");
        chunks.add(method);

        List<CodeChunk> batchSummaries = summaryGenerator.generatePackageSummaries(chunks);
        assertEquals(1, batchSummaries.size());

        PackageSummaryGenerator.Accumulator accumulator = summaryGenerator.createAccumulator();
        accumulator.accumulate(chunks);
        List<CodeChunk> streamSummaries = accumulator.generateSummaries();
        assertEquals(1, streamSummaries.size());

        assertEquals(batchSummaries.get(0).getContent(), streamSummaries.get(0).getContent());
        assertEquals(0, accumulator.getPackageDataMap().get("com.example.members").getTotalTopLevelTypes());
    }

    @Test
    @DisplayName("Largest element ties parity: strict tie handling retains earlier encounter for classes and methods")
    void testLargestElementTiesParity() {
        List<CodeChunk> chunks = new ArrayList<>();

        // Two classes of identical length 500
        CodeChunk class1 = new CodeChunk();
        class1.setType("CLASS");
        class1.setPackageName("com.example.ties");
        class1.setClassName("FirstClass");
        class1.setElementName("FirstClass");
        class1.setOriginalElementLength(500);
        chunks.add(class1);

        CodeChunk class2 = new CodeChunk();
        class2.setType("CLASS");
        class2.setPackageName("com.example.ties");
        class2.setClassName("SecondClass");
        class2.setElementName("SecondClass");
        class2.setOriginalElementLength(500);
        chunks.add(class2);

        // Two methods of identical length 300
        CodeChunk method1 = new CodeChunk();
        method1.setType("METHOD");
        method1.setPackageName("com.example.ties");
        method1.setClassName("FirstClass");
        method1.setElementName("firstMethod");
        method1.setOriginalElementLength(300);
        chunks.add(method1);

        CodeChunk method2 = new CodeChunk();
        method2.setType("METHOD");
        method2.setPackageName("com.example.ties");
        method2.setClassName("FirstClass");
        method2.setElementName("secondMethod");
        method2.setOriginalElementLength(300);
        chunks.add(method2);

        // Calculator vs Accumulator
        IndexingStatistics calcStats = statsCalculator.calculate(chunks, 100);

        IndexingStatisticsAccumulator accumulator = new IndexingStatisticsAccumulator();
        accumulator.accumulate(chunks);
        IndexingStatistics accStats = accumulator.build(100);

        assertEquals("FirstClass", calcStats.getLargestClassName());
        assertEquals("FirstClass", accStats.getLargestClassName(),
                "Accumulator must retain first encountered class on strict tie");

        assertEquals("FirstClass#firstMethod", calcStats.getLargestMethodName());
        assertEquals("FirstClass#firstMethod", accStats.getLargestMethodName(),
                "Accumulator must retain first encountered method on strict tie");

        // Comprehensive equality of all metrics
        assertEquals(calcStats.getClasses(), accStats.getClasses());
        assertEquals(calcStats.getMethods(), accStats.getMethods());
        assertEquals(calcStats.getLargestClassSize(), accStats.getLargestClassSize());
        assertEquals(calcStats.getLargestMethodSize(), accStats.getLargestMethodSize());
        assertEquals(calcStats.getPackages(), accStats.getPackages());
        assertEquals(calcStats.getTotalChunks(), accStats.getTotalChunks());
    }
}
