package com.shravan.jcode_intelligence.service;

import com.shravan.jcode_intelligence.dto.request.ChatMode;
import com.shravan.jcode_intelligence.model.QueryIntent;
import com.shravan.jcode_intelligence.service.RetrievalBenchmark.BenchmarkResult;
import com.shravan.jcode_intelligence.service.impl.RetrievalServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrievalBenchmarkTest {

    private static final int BENCHMARK_QUERY_COUNT = 8;

    private static final List<String> EXPECTED_QUERIES = List.of(
            "Explain Node",
            "Explain findAll()",
            "Where is LexicalPreservingPrinter?",
            "Explain package structure",
            "Architecture overview",
            "How does JavaParser parse a Java source file?",
            "Where is BackupExecutionService?",
            "Which classes use NodeList?"
    );

    private static final List<String> EXPECTED_INTENTS = List.of(
            "CLASS", "METHOD", "SEARCH", "PACKAGE",
            "ARCHITECTURE", "WORKFLOW", "SEARCH", "RELATIONSHIP"
    );

    @Test
    void runBenchmarkDoesNotRetainResultsFromPriorExecutions() {
        RetrievalBenchmark benchmark = new RetrievalBenchmark(
                new RepositoryLabelledRetrievalService(),
                new QueryIntentClassifier()
        );

        List<BenchmarkResult> firstRun = benchmark.runBenchmark("repository-a");
        List<BenchmarkResult> secondRun = benchmark.runBenchmark("repository-a");
        List<BenchmarkResult> thirdRun = benchmark.runBenchmark("repository-b");

        assertRunContainsOnlyRepository(firstRun, "repository-a");
        assertRunContainsOnlyRepository(secondRun, "repository-a");
        assertRunContainsOnlyRepository(thirdRun, "repository-b");
    }

    @Test
    void runBenchmarkPreservesFixedQuerySetContentsAndOrder() {
        RetrievalBenchmark benchmark = new RetrievalBenchmark(
                new FixedDocumentsRetrievalService(List.of()),
                new QueryIntentClassifier()
        );

        List<BenchmarkResult> results = benchmark.runBenchmark("repository-a");

        assertEquals(EXPECTED_QUERIES, results.stream().map(BenchmarkResult::query).toList());
        assertEquals(EXPECTED_INTENTS, results.stream().map(BenchmarkResult::expectedIntent).toList());
    }

    @Test
    void runBenchmarkAchievesFullIntentAccuracyWithRealClassifier() {
        RetrievalBenchmark benchmark = new RetrievalBenchmark(
                new FixedDocumentsRetrievalService(List.of(document("CLASS", "AnyElement"))),
                new QueryIntentClassifier()
        );

        List<BenchmarkResult> results = benchmark.runBenchmark("repository-a");

        assertEquals(BENCHMARK_QUERY_COUNT, results.size());
        for (BenchmarkResult result : results) {
            assertEquals(result.expectedIntent(), result.classifiedIntent(),
                    "Expected intent should match classified intent for query: " + result.query());
            assertTrue(result.intentMatch());
        }
    }

    @Test
    void runBenchmarkFlagsIntentMismatchWhenClassifierDisagreesWithExpectedIntent() {
        RetrievalBenchmark benchmark = new RetrievalBenchmark(
                new FixedDocumentsRetrievalService(List.of(document("CLASS", "Node"))),
                new AlwaysDesignIntentClassifier()
        );

        List<BenchmarkResult> results = benchmark.runBenchmark("repository-a");

        assertEquals(BENCHMARK_QUERY_COUNT, results.size());
        for (BenchmarkResult result : results) {
            assertEquals("DESIGN", result.classifiedIntent());
            assertFalse(result.intentMatch());
        }
    }

    @Test
    void runBenchmarkComputesChunkTypeCountsAndTopChunkType() {
        List<Document> documents = List.of(
                document("METHOD", "findAll"),
                document("CLASS", "Node"),
                document("METHOD", "parse")
        );
        RetrievalBenchmark benchmark = new RetrievalBenchmark(
                new FixedDocumentsRetrievalService(documents),
                new QueryIntentClassifier()
        );

        List<BenchmarkResult> results = benchmark.runBenchmark("repository-a");

        for (BenchmarkResult result : results) {
            assertEquals(Map.of("METHOD", 2, "CLASS", 1), result.chunkTypeCounts());
            assertEquals("METHOD", result.topChunkType());
            assertEquals(3, result.totalDocuments());
        }
    }

    @Test
    void runBenchmarkDefaultsMissingTypeMetadataToUnknown() {
        Document documentWithoutType = new Document("no-type-content", new HashMap<>());
        RetrievalBenchmark benchmark = new RetrievalBenchmark(
                new FixedDocumentsRetrievalService(List.of(documentWithoutType)),
                new QueryIntentClassifier()
        );

        List<BenchmarkResult> results = benchmark.runBenchmark("repository-a");

        for (BenchmarkResult result : results) {
            assertEquals(Map.of("UNKNOWN", 1), result.chunkTypeCounts());
            assertEquals("UNKNOWN", result.topChunkType());
        }
    }

    @Test
    void runBenchmarkCapsTopElementNamesAtTenAndSkipsBlankNames() {
        List<Document> documents = new ArrayList<>();
        documents.add(document("CLASS", ""));
        for (int i = 0; i < 12; i++) {
            documents.add(document("CLASS", "element-" + i));
        }
        documents.add(document("CLASS", "   "));

        RetrievalBenchmark benchmark = new RetrievalBenchmark(
                new FixedDocumentsRetrievalService(documents),
                new QueryIntentClassifier()
        );

        List<BenchmarkResult> results = benchmark.runBenchmark("repository-a");

        List<String> expectedTopElements = List.of(
                "element-0", "element-1", "element-2", "element-3", "element-4",
                "element-5", "element-6", "element-7", "element-8", "element-9"
        );

        for (BenchmarkResult result : results) {
            assertEquals(10, result.topElementNames().size());
            assertEquals(expectedTopElements, result.topElementNames());
            assertEquals(14, result.totalDocuments());
        }
    }

    @Test
    void runBenchmarkHandlesEmptyRetrievalResults() {
        RetrievalBenchmark benchmark = new RetrievalBenchmark(
                new FixedDocumentsRetrievalService(List.of()),
                new QueryIntentClassifier()
        );

        List<BenchmarkResult> results = benchmark.runBenchmark("repository-a");

        assertEquals(BENCHMARK_QUERY_COUNT, results.size());
        for (BenchmarkResult result : results) {
            assertEquals(0, result.totalDocuments());
            assertEquals(Map.of(), result.chunkTypeCounts());
            assertEquals(List.of(), result.topElementNames());
            assertEquals("NONE", result.topChunkType());
        }
    }

    @Test
    void runBenchmarkRecordsNonNegativeLatencyForEveryQuery() {
        RetrievalBenchmark benchmark = new RetrievalBenchmark(
                new FixedDocumentsRetrievalService(List.of(document("CLASS", "Node"))),
                new QueryIntentClassifier()
        );

        List<BenchmarkResult> results = benchmark.runBenchmark("repository-a");

        for (BenchmarkResult result : results) {
            assertTrue(result.latencyMs() >= 0, "latency should never be negative");
        }
    }

    private void assertRunContainsOnlyRepository(
            List<BenchmarkResult> results,
            String repositoryId
    ) {
        assertEquals(BENCHMARK_QUERY_COUNT, results.size());

        for (BenchmarkResult result : results) {
            assertEquals(List.of(repositoryId), result.topElementNames());
            assertEquals(1, result.totalDocuments());
        }
    }

    private static Document document(String type, String elementName) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("type", type);
        metadata.put("elementName", elementName);
        return new Document("stub content", metadata);
    }

    private static class RepositoryLabelledRetrievalService extends RetrievalServiceImpl {

        RepositoryLabelledRetrievalService() {
            super(null, null, null, null, null);
        }

        @Override
        public List<Document> retrieve(
                String query,
                int topK,
                String repositoryId,
                ChatMode mode
        ) {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("type", "CLASS");
            metadata.put("elementName", repositoryId);

            return List.of(new Document("stub content for " + repositoryId, metadata));
        }
    }

    private static class FixedDocumentsRetrievalService extends RetrievalServiceImpl {

        private final List<Document> documentsToReturn;

        FixedDocumentsRetrievalService(List<Document> documentsToReturn) {
            super(null, null, null, null, null);
            this.documentsToReturn = documentsToReturn;
        }

        @Override
        public List<Document> retrieve(
                String query,
                int topK,
                String repositoryId,
                ChatMode mode
        ) {
            return documentsToReturn;
        }
    }

    private static class AlwaysDesignIntentClassifier extends QueryIntentClassifier {

        @Override
        public QueryIntent classify(String query) {
            return QueryIntent.DESIGN;
        }
    }
}