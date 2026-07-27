package com.shravan.jcode_intelligence;

import com.github.javaparser.JavaParser;
import com.shravan.jcode_intelligence.config.ChunkingConfig;
import com.shravan.jcode_intelligence.config.JavaParserConfig;
import com.shravan.jcode_intelligence.model.CodeChunk;
import com.shravan.jcode_intelligence.parser.*;
import com.shravan.jcode_intelligence.parser.spi.ChunkBudgetEstimator;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class StandaloneJavaProjectParserTest {

    @Test
    public void testJavaProjectParserWithInjectedBean() throws Exception {
        System.out.println("=== STANDALONE JAVA PROJECT PARSER TEST ===");

        // 1. Initialize JavaParser bean via JavaParserConfig
        JavaParserConfig configFactory = new JavaParserConfig();
        JavaParser javaParser = configFactory.javaParser();

        System.out.println("Configured JavaParser bean LanguageLevel: " + javaParser.getParserConfiguration().getLanguageLevel());

        // 2. Instantiate parser pipeline with new hierarchical chunking components
        MetadataExtractor metadataExtractor = new MetadataExtractor();
        ClassSummaryBuilder summaryBuilder = new ClassSummaryBuilder();
        ChunkingConfig chunkingConfig = createTestChunkingConfig();
        ChunkBudgetEstimator budgetEstimator = new CharBasedBudgetEstimator(chunkingConfig);

        MethodFragmenter methodFragmenter = new MethodFragmenter(metadataExtractor, budgetEstimator);
        ChunkGenerator chunkGenerator = new ChunkGenerator(
                metadataExtractor, summaryBuilder, methodFragmenter, budgetEstimator);
        AstVisitor astVisitor = new AstVisitor(chunkGenerator);

        JavaProjectParser parser = new JavaProjectParser(javaParser, astVisitor);

        // 3. Parse whole src/main/java project root
        Path projectRoot = Path.of("src/main/java");
        List<CodeChunk> chunks = parser.parse(projectRoot);

        System.out.println("Total chunks parsed from src/main/java: " + chunks.size());

        boolean foundMetadataExtractor = chunks.stream()
                .anyMatch(c -> "MetadataExtractor".equals(c.getClassName()));

        boolean foundSymbolExtractor = chunks.stream()
                .anyMatch(c -> "SymbolExtractor".equals(c.getClassName()));

        System.out.println("MetadataExtractor present: " + foundMetadataExtractor);
        System.out.println("SymbolExtractor present: " + foundSymbolExtractor);

        assertTrue(foundMetadataExtractor, "MetadataExtractor must be parsed without errors");
        assertTrue(foundSymbolExtractor, "SymbolExtractor must be parsed without errors");

        // 4. Verify hierarchical decomposition
        long classChunks = chunks.stream()
                .filter(c -> "CLASS".equals(c.getType()) || "INTERFACE".equals(c.getType()))
                .count();
        long methodChunks = chunks.stream()
                .filter(c -> "METHOD".equals(c.getType()))
                .count();
        long fieldChunks = chunks.stream()
                .filter(c -> "FIELD".equals(c.getType()))
                .count();
        long constructorChunks = chunks.stream()
                .filter(c -> "CONSTRUCTOR".equals(c.getType()))
                .count();

        System.out.println("CLASS/INTERFACE chunks: " + classChunks);
        System.out.println("METHOD chunks: " + methodChunks);
        System.out.println("FIELD chunks: " + fieldChunks);
        System.out.println("CONSTRUCTOR chunks: " + constructorChunks);

        assertTrue(classChunks > 0, "Should have class summary chunks");
        assertTrue(methodChunks > 0, "Should have method chunks");
        assertTrue(fieldChunks > 0, "Should have field chunks");

        // 5. Verify class chunks contain summaries (not full source)
        for (CodeChunk chunk : chunks) {
            if ("CLASS".equals(chunk.getType()) || "INTERFACE".equals(chunk.getType())) {
                assertNotNull(chunk.getContent(), "Class chunk must have content");
                assertFalse(chunk.getContent().contains("public void ") && chunk.getContent().contains("{")
                                && chunk.getContent().contains("return "),
                        "Class chunk should NOT contain method bodies");
            }
        }

        // 6. Verify parent-child hierarchy
        long chunksWithParent = chunks.stream()
                .filter(c -> c.getParentChunkId() != null)
                .count();
        System.out.println("Chunks with parentChunkId: " + chunksWithParent);
        assertTrue(chunksWithParent > 0, "Member chunks should have parentChunkId set");
    }

    private ChunkingConfig createTestChunkingConfig() {
        return new ChunkingConfig() {
            @Override
            public int getMaxMethodChars() { return 6000; }
            @Override
            public int getMaxFragmentChars() { return 3000; }
        };
    }
}
