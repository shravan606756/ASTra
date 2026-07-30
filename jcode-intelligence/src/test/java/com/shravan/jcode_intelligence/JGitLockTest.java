package com.shravan.jcode_intelligence;

import com.shravan.jcode_intelligence.config.ChunkingConfig;
import com.shravan.jcode_intelligence.converter.DocumentConverter;
import com.shravan.jcode_intelligence.parser.*;
import com.shravan.jcode_intelligence.service.impl.GitServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.util.FileSystemUtils;
import com.shravan.jcode_intelligence.config.JGitConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class JGitLockTest {

    private final String TEST_REPO = java.nio.file.Paths.get("..").toAbsolutePath().toUri().toString();
    private GitServiceImpl gitService;

    @Test
    public void runExperiments() throws Exception {
        System.out.println("========== RUNNING EXPERIMENTS ==========");
        
        // Initialize Configuration manually since we are not loading the full Spring Context
        JGitConfiguration config = new JGitConfiguration();
        config.configureJGit();
        
        gitService = new GitServiceImpl();
        
        // TEST 1: Clone Only
        System.out.println("\n--- TEST A: Clone Only ---");
        Path dir1 = test1CloneOnly();
        checkDeleted(dir1, "Test A");

        // TEST 2: Clone + Parse
        System.out.println("\n--- TEST B: Clone + Parse ---");
        Path dir2 = test2CloneAndParse();
        checkDeleted(dir2, "Test B");

        // TEST 3: Clone + Parse + Convert
        System.out.println("\n--- TEST C: Clone + Parse + Convert ---");
        Path dir3 = test3CloneParseConvert();
        checkDeleted(dir3, "Test C");

        // TEST 4: Double Clone and Cleanup
        System.out.println("\n--- TEST 4: Double Clone and Cleanup ---");
        Path dir4a = test1CloneOnly();
        Path dir4b = test1CloneOnly();
        checkDeleted(dir4a, "Test 4A");
        checkDeleted(dir4b, "Test 4B");
    }

    private Path test1CloneOnly() throws IOException {
        Path tempDir = gitService.cloneRepository(TEST_REPO);
        System.out.println("Cloned to: " + tempDir);
        // Immediately clean up (no retry loop here)
        gitService.cleanupRepository(tempDir);
        return tempDir;
    }

    private Path test2CloneAndParse() throws IOException {
        Path tempDir = gitService.cloneRepository(TEST_REPO);
        System.out.println("Cloned to: " + tempDir);
        
        try {
            ChunkingConfig config = new ChunkingConfig();
            CharBasedBudgetEstimator budgetEstimator = new CharBasedBudgetEstimator(config);
            JavaProjectParser parser = new JavaProjectParser(
                new com.shravan.jcode_intelligence.config.JavaParserConfig().javaParser(),
                new AstVisitor(new ChunkGenerator(
                        new MetadataExtractor(),
                        new ClassSummaryBuilder(budgetEstimator),
                        new MethodFragmenter(new MetadataExtractor(), budgetEstimator),
                        budgetEstimator
                ))
            );
            
            System.out.println("Parsing directory...");
            var chunks = parser.parse(tempDir);
            System.out.println("Parsed chunks: " + chunks.size());
        } finally {
            gitService.cleanupRepository(tempDir);
        }
        return tempDir;
    }

    private Path test3CloneParseConvert() throws IOException {
        Path tempDir = gitService.cloneRepository(TEST_REPO);
        System.out.println("Cloned to: " + tempDir);
        
        try {
            ChunkingConfig config = new ChunkingConfig();
            CharBasedBudgetEstimator budgetEstimator = new CharBasedBudgetEstimator(config);
            JavaProjectParser parser = new JavaProjectParser(
                new com.shravan.jcode_intelligence.config.JavaParserConfig().javaParser(),
                new AstVisitor(new ChunkGenerator(
                        new MetadataExtractor(),
                        new ClassSummaryBuilder(budgetEstimator),
                        new MethodFragmenter(new MetadataExtractor(), budgetEstimator),
                        budgetEstimator
                ))
            );
            
            var chunks = parser.parse(tempDir);
            DocumentConverter converter = new DocumentConverter();
            List<Document> docs = converter.convert(chunks);
            System.out.println("Converted docs: " + docs.size());
        } finally {
            gitService.cleanupRepository(tempDir);
        }
        return tempDir;
    }

    private void checkDeleted(Path dir, String testName) {
        if (Files.exists(dir)) {
            System.err.println(testName + " FAILED: Directory still exists! -> " + dir);
        } else {
            System.out.println(testName + " PASSED: Directory was successfully deleted.");
        }
    }
}
