package com.shravan.jcode_intelligence;

import com.github.javaparser.JavaParser;
import com.shravan.jcode_intelligence.config.JavaParserConfig;
import com.shravan.jcode_intelligence.model.CodeChunk;
import com.shravan.jcode_intelligence.parser.*;
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

        // 2. Instantiate JavaProjectParser and dependencies
        MetadataExtractor metadataExtractor = new MetadataExtractor();
        ChunkGenerator chunkGenerator = new ChunkGenerator(metadataExtractor);
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
    }
}
