package com.shravan.jcode_intelligence;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.shravan.jcode_intelligence.config.ChunkingConfig;
import com.shravan.jcode_intelligence.config.JavaParserConfig;
import com.shravan.jcode_intelligence.model.CodeChunk;
import com.shravan.jcode_intelligence.model.IndexingStatistics;
import com.shravan.jcode_intelligence.parser.*;
import com.shravan.jcode_intelligence.parser.spi.ChunkBudgetEstimator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite for the refined hierarchical AST-aware chunking system.
 */
public class HierarchicalChunkingTest {

    private JavaParser javaParser;
    private ChunkGenerator chunkGenerator;
    private AstVisitor astVisitor;
    private ChunkingConfig defaultConfig;
    private ChunkBudgetEstimator budgetEstimator;

    @BeforeEach
    void setUp() {
        javaParser = new JavaParserConfig().javaParser();
        MetadataExtractor extractor = new MetadataExtractor();
        ClassSummaryBuilder summaryBuilder = new ClassSummaryBuilder();
        defaultConfig = createConfig(6000, 3000);
        budgetEstimator = new CharBasedBudgetEstimator(defaultConfig);
        MethodFragmenter fragmenter = new MethodFragmenter(extractor, budgetEstimator);
        chunkGenerator = new ChunkGenerator(extractor, summaryBuilder, fragmenter, budgetEstimator);
        astVisitor = new AstVisitor(chunkGenerator);
    }

    // ═══════════════════════════════════════════════════════════
    // Test 1: Normal class decomposition
    // ═══════════════════════════════════════════════════════════

    @Test
    void testNormalClassProducesHierarchicalChunks() {
        String code = """
                package com.example.service;
                
                import java.util.List;
                
                /**
                 * Manages user accounts.
                 */
                @Service
                public class UserService extends BaseService implements Serializable {
                
                    private final UserRepository repo;
                    private int maxRetries;
                
                    public UserService(UserRepository repo) {
                        this.repo = repo;
                    }
                
                    public User findById(long id) {
                        return repo.findById(id).orElseThrow();
                    }
                
                    public List<User> findAll() {
                        return repo.findAll();
                    }
                
                    private void validateUser(User user) {
                        if (user == null) throw new IllegalArgumentException();
                    }
                }
                """;

        List<CodeChunk> chunks = parseAndChunk(code);

        // Should produce: 1 CLASS + 2 FIELD + 1 CONSTRUCTOR + 3 METHOD = 7 chunks
        assertChunkCount(chunks, "CLASS", 1);
        assertChunkCount(chunks, "FIELD", 2);
        assertChunkCount(chunks, "CONSTRUCTOR", 1);
        assertChunkCount(chunks, "METHOD", 3);
        assertEquals(7, chunks.size(), "Total chunks should be 7");

        CodeChunk classChunk = findChunkByType(chunks, "CLASS");
        assertNotNull(classChunk);
        assertTrue(classChunk.getContent().contains("class UserService"));
        assertTrue(classChunk.getContent().contains("extends BaseService"));
        assertTrue(classChunk.getContent().contains("implements Serializable"));
        assertFalse(classChunk.getContent().contains("repo.findById(id).orElseThrow()"),
                "Class summary should not contain method body code");

        // Verify relationship metadata
        assertNotNull(classChunk.getRelationships());
        assertEquals("BaseService", classChunk.getSuperClass());
        assertTrue(classChunk.getRelationships().containsKey("extends"));
        assertTrue(classChunk.getRelationships().get("extends").contains("BaseService"));
        assertTrue(classChunk.getRelationships().containsKey("implements"));
        assertTrue(classChunk.getRelationships().get("implements").contains("Serializable"));

        // All member chunks should reference the class as parent
        for (CodeChunk chunk : chunks) {
            if (!"CLASS".equals(chunk.getType())) {
                assertNotNull(chunk.getParentChunkId(), chunk.getType() + " chunk should have parentChunkId");
                assertEquals(classChunk.getId(), chunk.getParentChunkId());
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Test 2: Nested Class (INNER_CLASS removed -> represented as CLASS)
    // ═══════════════════════════════════════════════════════════

    @Test
    void testInnerClassesRepresentedAsClassWithNestingMetadata() {
        String code = """
                package com.example;
                
                public class Outer {
                    private int x;
                
                    public void outerMethod() {
                        System.out.println("outer");
                    }
                
                    public static class Inner {
                        private String name;
                
                        public void innerMethod() {
                            System.out.println("inner");
                        }
                
                        public static class DeepInner {
                            public void deepMethod() {
                                System.out.println("deep");
                            }
                        }
                    }
                }
                """;

        List<CodeChunk> chunks = parseAndChunk(code);

        // Verify INNER_CLASS type does NOT exist anymore
        long innerClassTypeCount = chunks.stream().filter(c -> "INNER_CLASS".equals(c.getType())).count();
        assertEquals(0, innerClassTypeCount, "INNER_CLASS chunk type should be removed");

        // Outer: CLASS (depth 0)
        // Inner: CLASS (depth 1, outerClassName=Outer)
        // DeepInner: CLASS (depth 2, outerClassName=Inner)
        assertChunkCount(chunks, "CLASS", 3);

        CodeChunk outer = chunks.stream()
                .filter(c -> "CLASS".equals(c.getType()) && "Outer".equals(c.getElementName()))
                .findFirst().orElseThrow();
        assertEquals(0, outer.getNestingDepth());
        assertNull(outer.getOuterClassName());

        CodeChunk inner = chunks.stream()
                .filter(c -> "CLASS".equals(c.getType()) && "Inner".equals(c.getElementName()))
                .findFirst().orElseThrow();
        assertEquals(1, inner.getNestingDepth());
        assertEquals("Outer", inner.getOuterClassName());
        assertEquals(outer.getId(), inner.getParentChunkId());

        CodeChunk deepInner = chunks.stream()
                .filter(c -> "CLASS".equals(c.getType()) && "DeepInner".equals(c.getElementName()))
                .findFirst().orElseThrow();
        assertEquals(2, deepInner.getNestingDepth());
        assertEquals("Inner", deepInner.getOuterClassName());
        assertEquals(inner.getId(), deepInner.getParentChunkId());
    }

    // ═══════════════════════════════════════════════════════════
    // Test 3: Method Fragment Context Preamble
    // ═══════════════════════════════════════════════════════════

    @Test
    void testMethodFragmentationIncludesMinimalPrecedingContext() {
        ChunkingConfig tinyConfig = createConfig(200, 100);
        ChunkBudgetEstimator tinyEstimator = new CharBasedBudgetEstimator(tinyConfig);

        MetadataExtractor extractor = new MetadataExtractor();
        ClassSummaryBuilder summaryBuilder = new ClassSummaryBuilder();
        MethodFragmenter fragmenter = new MethodFragmenter(extractor, tinyEstimator);
        ChunkGenerator gen = new ChunkGenerator(extractor, summaryBuilder, fragmenter, tinyEstimator);
        AstVisitor visitor = new AstVisitor(gen);

        String code = """
                package com.example;
                public class BigProcessor {
                    public void execute() {
                        String inputData = "start";
                        int count = 42;
                        System.out.println(inputData);
                        System.out.println(count);
                        String secondVar = "next";
                        System.out.println(secondVar);
                    }
                }
                """;

        List<CodeChunk> chunks = parseFromVisitor(code, visitor);

        List<CodeChunk> fragments = chunks.stream()
                .filter(c -> "METHOD_FRAGMENT".equals(c.getType()))
                .toList();

        assertTrue(fragments.size() >= 2, "Should produce multiple fragments");

        // Fragment 1 (index 1) should contain variable declarations from Fragment 0
        CodeChunk secondFragment = fragments.get(1);
        assertTrue(secondFragment.isFragmented());
        assertTrue(secondFragment.getContent().contains("// Fragment 1 of method"));
    }

    // ═══════════════════════════════════════════════════════════
    // Test 4: Package Summary Generation
    // ═══════════════════════════════════════════════════════════

    @Test
    void testPackageSummaryGeneration() {
        String code1 = "package com.example.foo; public class FooOne {}";
        String code2 = "package com.example.foo; public class FooTwo {}";
        String code3 = "package com.example.bar; public class BarOne {}";

        List<CodeChunk> chunks1 = parseAndChunk(code1);
        List<CodeChunk> chunks2 = parseAndChunk(code2);
        List<CodeChunk> chunks3 = parseAndChunk(code3);

        List<CodeChunk> allElementChunks = new ArrayList<>();
        allElementChunks.addAll(chunks1);
        allElementChunks.addAll(chunks2);
        allElementChunks.addAll(chunks3);

        PackageSummaryGenerator pkgGen = new PackageSummaryGenerator();
        List<CodeChunk> pkgSummaries = pkgGen.generatePackageSummaries(allElementChunks);

        // Exactly 2 package summaries: com.example.foo and com.example.bar
        assertEquals(2, pkgSummaries.size());

        CodeChunk fooPkg = pkgSummaries.stream()
                .filter(p -> "com.example.foo".equals(p.getPackageName()))
                .findFirst().orElseThrow();
        assertEquals("PACKAGE", fooPkg.getType());
        assertTrue(fooPkg.getContent().contains("FooOne"));
        assertTrue(fooPkg.getContent().contains("FooTwo"));
    }

    // ═══════════════════════════════════════════════════════════
    // Test 5: Indexing Statistics Calculation
    // ═══════════════════════════════════════════════════════════

    @Test
    void testIndexingStatisticsCalculation() {
        String code = """
                package com.example;
                public class Sample {
                    private int x;
                    public Sample() {}
                    public void foo() {}
                }
                """;

        List<CodeChunk> chunks = parseAndChunk(code);
        IndexingStatisticsCalculator calc = new IndexingStatisticsCalculator();
        IndexingStatistics stats = calc.calculate(chunks, 100);

        assertEquals(1, stats.getPackages());
        assertEquals(1, stats.getClasses());
        assertEquals(1, stats.getFields());
        assertEquals(1, stats.getConstructors());
        assertEquals(1, stats.getMethods());
        assertEquals(4, stats.getTotalChunks());
        assertEquals("Sample", stats.getLargestClassName());
    }

    // ═══════════════════════════════════════════════════════════
    // Test 6: Self-indexing ASTra Codebase
    // ═══════════════════════════════════════════════════════════

    @Test
    void testSelfIndexingWithStatisticsAndPackageSummaries() throws Exception {
        JavaProjectParser parser = new JavaProjectParser(javaParser, astVisitor);

        List<CodeChunk> elementChunks = parser.parse(java.nio.file.Path.of("src/main/java"));
        PackageSummaryGenerator pkgGen = new PackageSummaryGenerator();
        List<CodeChunk> pkgSummaries = pkgGen.generatePackageSummaries(elementChunks);

        List<CodeChunk> allChunks = new ArrayList<>(elementChunks);
        allChunks.addAll(pkgSummaries);

        IndexingStatisticsCalculator calc = new IndexingStatisticsCalculator();
        IndexingStatistics stats = calc.calculate(allChunks, 500);

        System.out.println(stats);

        // Verify package summaries
        assertTrue(stats.getPackages() > 0);
        assertTrue(pkgSummaries.size() == stats.getPackages(),
                "Package summary count should match unique package count");

        // Verify INNER_CLASS is 0
        long innerClassCount = allChunks.stream().filter(c -> "INNER_CLASS".equals(c.getType())).count();
        assertEquals(0, innerClassCount, "No INNER_CLASS chunks should exist");

        // Diagnostic metadata checks
        for (CodeChunk chunk : elementChunks) {
            assertTrue(chunk.getContentLength() > 0, "contentLength should be set");
            assertTrue(chunk.getOriginalElementLength() > 0, "originalElementLength should be set");
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Utility methods
    // ═══════════════════════════════════════════════════════════

    private List<CodeChunk> parseAndChunk(String code) {
        return parseFromVisitor(code, astVisitor);
    }

    private List<CodeChunk> parseFromVisitor(String code, AstVisitor visitor) {
        ParseResult<CompilationUnit> result = javaParser.parse(code);
        assertTrue(result.isSuccessful(), "Code should parse successfully");

        CompilationUnit cu = result.getResult().orElseThrow();
        List<CodeChunk> chunks = new ArrayList<>();
        visitor.visit(cu, chunks);
        return chunks;
    }

    private void assertChunkCount(List<CodeChunk> chunks, String type, int expected) {
        long actual = chunks.stream().filter(c -> type.equals(c.getType())).count();
        assertEquals(expected, actual,
                String.format("Expected %d %s chunks but found %d. All types: %s",
                        expected, type, actual,
                        chunks.stream().map(c -> c.getType() + ":" + c.getElementName())
                                .collect(Collectors.joining(", "))));
    }

    private CodeChunk findChunkByType(List<CodeChunk> chunks, String type) {
        return chunks.stream()
                .filter(c -> type.equals(c.getType()))
                .findFirst()
                .orElse(null);
    }

    private ChunkingConfig createConfig(int maxMethodChars, int maxFragmentChars) {
        return new ChunkingConfig() {
            @Override
            public int getMaxMethodChars() { return maxMethodChars; }
            @Override
            public int getMaxFragmentChars() { return maxFragmentChars; }
        };
    }
}
