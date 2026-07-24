package com.shravan.jcode_intelligence;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.shravan.jcode_intelligence.config.JavaParserConfig;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class ActualFileParseTest {

    @Test
    public void testParseMetadataExtractorAndSymbolExtractor() throws Exception {
        System.out.println("=== ACTUAL FILE PARSE TEST ===");

        Path metadataExtractorPath = Path.of("src/main/java/com/shravan/jcode_intelligence/parser/MetadataExtractor.java");
        Path symbolExtractorPath = Path.of("src/main/java/com/shravan/jcode_intelligence/service/SymbolExtractor.java");

        assertTrue(new File(metadataExtractorPath.toUri()).exists());
        assertTrue(new File(symbolExtractorPath.toUri()).exists());

        // 1. Create JavaParser bean via factory
        JavaParserConfig configFactory = new JavaParserConfig();
        JavaParser javaParser = configFactory.javaParser();

        System.out.println("JavaParser bean LanguageLevel: " + javaParser.getParserConfiguration().getLanguageLevel());

        // 2. Parse MetadataExtractor.java with JavaParser bean
        ParseResult<CompilationUnit> resMeta = javaParser.parse(metadataExtractorPath);
        System.out.println("MetadataExtractor parse successful: " + resMeta.isSuccessful());
        assertTrue(resMeta.isSuccessful(), "MetadataExtractor must parse cleanly");

        // 3. Parse SymbolExtractor.java with JavaParser bean
        ParseResult<CompilationUnit> resSym = javaParser.parse(symbolExtractorPath);
        System.out.println("SymbolExtractor parse successful: " + resSym.isSuccessful());
        assertTrue(resSym.isSuccessful(), "SymbolExtractor must parse cleanly");
    }
}
