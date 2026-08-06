package com.shravan.jcode_intelligence;

import com.shravan.jcode_intelligence.config.ChunkingConfig;
import com.shravan.jcode_intelligence.converter.DocumentConverter;
import com.shravan.jcode_intelligence.parser.*;
import com.shravan.jcode_intelligence.service.GitService;
import com.shravan.jcode_intelligence.service.impl.IndexingServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

public class BatchingIndexingServiceTest {

    @Test
    public void testBatchVectorStoreInsertionAdvancement() throws IOException {
        System.out.println("=== VERIFYING BATCH VECTOR STORE INSERTION ADVANCEMENT ===");

        // Mock Vector Store tracking batches
        MockBatchVectorStore mockVectorStore = new MockBatchVectorStore();
        MockJdbcTemplate mockJdbcTemplate = new MockJdbcTemplate();

        ChunkingConfig chunkingConfig = new ChunkingConfig();
        CharBasedBudgetEstimator budgetEstimator = new CharBasedBudgetEstimator(chunkingConfig);
        EmbeddingBudgetValidator validator = new EmbeddingBudgetValidator(budgetEstimator);

        JavaProjectParser parser = new JavaProjectParser(
                new com.shravan.jcode_intelligence.config.JavaParserConfig().javaParser(),
                new AstVisitor(new ChunkGenerator(
                        new MetadataExtractor(),
                        new ClassSummaryBuilder(budgetEstimator),
                        new MethodFragmenter(new MetadataExtractor(), budgetEstimator),
                        budgetEstimator
                ))
        );

        DocumentConverter converter = new DocumentConverter();
        PackageSummaryGenerator packageSummaryGenerator = new PackageSummaryGenerator();
        IndexingStatisticsCalculator statisticsCalculator = new IndexingStatisticsCalculator();

        IndexingServiceImpl indexingService = new IndexingServiceImpl(
                parser, converter, mockVectorStore, null, mockJdbcTemplate,
                packageSummaryGenerator, statisticsCalculator, validator, chunkingConfig, null
        );

        int totalIndexed = indexingService.indexProject("src/main/java", "test-batching");

        System.out.println("TEST BATCH METRICS:");
        System.out.println("  Total Documents Indexed: " + totalIndexed);
        System.out.println("  Total Batches Sent:      " + mockVectorStore.batchCalls.size());
        System.out.println("  Configured Batch Size:   " + chunkingConfig.getBatchSize());

        assertTrue(totalIndexed > 0, "Total indexed documents must be > 0");
        assertTrue(mockVectorStore.batchCalls.size() > 1, "Must execute in multiple batches rather than one giant call");
        
        for (int i = 0; i < mockVectorStore.batchCalls.size(); i++) {
            List<Document> b = mockVectorStore.batchCalls.get(i);
            assertTrue(b.size() <= chunkingConfig.getBatchSize(),
                    "Batch " + (i + 1) + " size (" + b.size() + ") must not exceed batchSize (" + chunkingConfig.getBatchSize() + ")");
        }
    }

    private static class MockBatchVectorStore implements VectorStore {
        // Batches are now embedded concurrently by STEP 6's adaptive worker pool,
        // so this collection must be safe for concurrent writes.
        final List<List<Document>> batchCalls = new CopyOnWriteArrayList<>();

        @Override
        public void add(List<Document> documents) {
            batchCalls.add(List.copyOf(documents));
        }

        @Override
        public void delete(List<String> idList) {}

        @Override
        public void delete(Filter.Expression filterExpression) {}

        @Override
        public List<Document> similaritySearch(String query) { return List.of(); }

        @Override
        public List<Document> similaritySearch(SearchRequest request) { return List.of(); }
    }

    private static class MockJdbcTemplate extends JdbcTemplate {
        @Override
        public int update(String sql, Object... args) {
            return 0;
        }
    }
}
