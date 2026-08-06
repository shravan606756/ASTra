package com.shravan.jcode_intelligence;

import com.shravan.jcode_intelligence.config.ChunkingConfig;
import com.shravan.jcode_intelligence.converter.DocumentConverter;
import com.shravan.jcode_intelligence.parser.*;
import com.shravan.jcode_intelligence.service.impl.IndexingServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Behavioral tests for STEP 6's adaptive parallel batch vector store insertion:
 * batches must genuinely run concurrently, concurrency must respect the
 * configured worker limit, and the worker pool must adapt down when there
 * are fewer batches than the configured limit.
 */
public class ParallelBatchIndexingServiceTest {

    @Test
    public void testAdaptiveParallelBatchExecutionRespectsWorkerLimit() throws IOException {
        System.out.println("=== VERIFYING ADAPTIVE PARALLEL BATCH EXECUTION RESPECTS WORKER LIMIT ===");

        final int configuredMaxWorkers = 3;
        final int configuredBatchSize = 20;

        ConcurrencyTrackingVectorStore trackingVectorStore = new ConcurrencyTrackingVectorStore();
        MockJdbcTemplate mockJdbcTemplate = new MockJdbcTemplate();

        ChunkingConfig chunkingConfig = new ChunkingConfig() {
            @Override
            public int getBatchSize() {
                return configuredBatchSize;
            }

            @Override
            public int getMaxParallelWorkers() {
                return configuredMaxWorkers;
            }
        };

        IndexingServiceImpl indexingService = buildIndexingService(trackingVectorStore, mockJdbcTemplate, chunkingConfig);

        int totalIndexed = indexingService.indexProject("src/main/java", "test-parallel-batching");

        int totalBatches = trackingVectorStore.batchCallCount.get();
        int observedMaxConcurrency = trackingVectorStore.maxObservedConcurrency.get();
        Set<String> distinctThreads = trackingVectorStore.threadNames;

        System.out.println("PARALLEL BATCH METRICS:");
        System.out.println("  Total Documents Indexed:      " + totalIndexed);
        System.out.println("  Total Batches Sent:           " + totalBatches);
        System.out.println("  Configured Worker Limit:      " + configuredMaxWorkers);
        System.out.println("  Observed Max Concurrency:     " + observedMaxConcurrency);
        System.out.println("  Distinct Worker Threads Used: " + distinctThreads.size());

        assertTrue(totalIndexed > 0, "Total indexed documents must be > 0");
        assertTrue(totalBatches > 1, "Must execute in multiple batches to exercise parallel execution");

        // Parallelism must actually engage when multiple batches are available.
        assertTrue(observedMaxConcurrency > 1,
                "Adaptive parallel execution must use more than one worker when multiple batches are available");

        // The adaptive worker pool must never exceed the configured limit.
        assertTrue(observedMaxConcurrency <= configuredMaxWorkers,
                "Observed concurrency (" + observedMaxConcurrency + ") must not exceed configured max workers (" + configuredMaxWorkers + ")");
        assertTrue(distinctThreads.size() <= configuredMaxWorkers,
                "Distinct worker threads used (" + distinctThreads.size() + ") must not exceed configured max workers (" + configuredMaxWorkers + ")");
    }

    @Test
    public void testAdaptiveWorkerCountDoesNotExceedBatchCount() throws IOException {
        System.out.println("=== VERIFYING ADAPTIVE WORKER COUNT DOES NOT EXCEED BATCH COUNT ===");

        ConcurrencyTrackingVectorStore trackingVectorStore = new ConcurrencyTrackingVectorStore();
        MockJdbcTemplate mockJdbcTemplate = new MockJdbcTemplate();

        // Batch size large enough that all documents land in a single batch,
        // despite a generous configured worker limit.
        ChunkingConfig chunkingConfig = new ChunkingConfig() {
            @Override
            public int getBatchSize() {
                return 100_000;
            }

            @Override
            public int getMaxParallelWorkers() {
                return 8;
            }
        };

        IndexingServiceImpl indexingService = buildIndexingService(trackingVectorStore, mockJdbcTemplate, chunkingConfig);

        int totalIndexed = indexingService.indexProject("src/main/java", "test-adaptive-single-batch");

        System.out.println("  Total Documents Indexed: " + totalIndexed);
        System.out.println("  Total Batches Sent:      " + trackingVectorStore.batchCallCount.get());
        System.out.println("  Worker Threads Used:     " + trackingVectorStore.threadNames.size());

        assertTrue(totalIndexed > 0, "Total indexed documents must be > 0");
        assertEquals(1, trackingVectorStore.batchCallCount.get(),
                "Expected exactly one batch when batch size exceeds total documents");
        assertEquals(1, trackingVectorStore.threadNames.size(),
                "Only one worker thread should be used when there is only a single batch to process");
    }

    private IndexingServiceImpl buildIndexingService(VectorStore vectorStore, JdbcTemplate jdbcTemplate, ChunkingConfig chunkingConfig) {
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

        return new IndexingServiceImpl(
                parser, converter, vectorStore, null, jdbcTemplate,
                packageSummaryGenerator, statisticsCalculator, validator, chunkingConfig, null
        );
    }

    private static class ConcurrencyTrackingVectorStore implements VectorStore {
        final AtomicInteger batchCallCount = new AtomicInteger(0);
        final AtomicInteger currentConcurrency = new AtomicInteger(0);
        final AtomicInteger maxObservedConcurrency = new AtomicInteger(0);
        final Set<String> threadNames = ConcurrentHashMap.newKeySet();

        @Override
        public void add(List<Document> documents) {
            threadNames.add(Thread.currentThread().getName());
            batchCallCount.incrementAndGet();

            int concurrent = currentConcurrency.incrementAndGet();
            maxObservedConcurrency.updateAndGet(prev -> Math.max(prev, concurrent));
            try {
                // Hold the simulated "embedding call" briefly so overlapping
                // batches have a chance to be observed concurrently.
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                currentConcurrency.decrementAndGet();
            }
        }

        @Override
        public void delete(List<String> idList) {
        }

        @Override
        public void delete(Filter.Expression filterExpression) {
        }

        @Override
        public List<Document> similaritySearch(String query) {
            return List.of();
        }

        @Override
        public List<Document> similaritySearch(SearchRequest request) {
            return List.of();
        }
    }

    private static class MockJdbcTemplate extends JdbcTemplate {
        @Override
        public int update(String sql, Object... args) {
            return 0;
        }
    }
}