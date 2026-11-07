package com.shravan.jcode_intelligence.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shravan.jcode_intelligence.dto.request.BenchmarkRequest;
import com.shravan.jcode_intelligence.dto.request.ChatMode;
import com.shravan.jcode_intelligence.dto.response.BenchmarkResponse;
import com.shravan.jcode_intelligence.dto.response.QueryBenchmarkResultDTO;
import com.shravan.jcode_intelligence.exception.BenchmarkConflictException;
import com.shravan.jcode_intelligence.model.QueryIntent;
import com.shravan.jcode_intelligence.model.benchmark.BenchmarkQueryDefinition;
import com.shravan.jcode_intelligence.model.benchmark.BenchmarkSuite;
import com.shravan.jcode_intelligence.model.benchmark.RelevantChunkDefinition;
import com.shravan.jcode_intelligence.service.RetrievalBenchmark.BenchmarkResult;
import com.shravan.jcode_intelligence.service.impl.RetrievalServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RetrievalBenchmarkTest {

    private BenchmarkDatasetProvider datasetProvider;
    private RetrievalEvaluator evaluator;
    private QueryIntentClassifier intentClassifier;

    @BeforeEach
    void setUp() {
        datasetProvider = new BenchmarkDatasetProvider(new ObjectMapper());
        datasetProvider.init();
        evaluator = new RetrievalEvaluator();
        intentClassifier = new QueryIntentClassifier();
    }

    @Test
    @DisplayName("Executes full benchmark suite from external dataset and returns typed response")
    void testRunBenchmarkWithExternalDataset() {
        Document matchDoc = new Document("stub LexicalPreservingPrinter content", Map.of(
                "filePath", "com/github/javaparser/printer/lexicalpreservation/LexicalPreservingPrinter.java",
                "className", "LexicalPreservingPrinter",
                "elementName", "LexicalPreservingPrinter",
                "type", "CLASS"
        ));

        RetrievalBenchmark benchmark = new RetrievalBenchmark(
                new FixedDocumentsRetrievalService(List.of(matchDoc)),
                intentClassifier,
                datasetProvider,
                evaluator
        );

        BenchmarkRequest request = new BenchmarkRequest("javaparser", "default", 5, "DENSE_ONLY", false);
        BenchmarkResponse response = benchmark.runBenchmark(request);

        assertNotNull(response);
        assertEquals("javaparser", response.getRepositoryId());
        assertEquals("default", response.getSuiteName());
        assertEquals(5, response.getTopK());
        assertEquals(50, response.getResults().size(), "Should evaluate all 50 queries in default suite");

        assertNotNull(response.getSummary());
        assertEquals(50, response.getSummary().getTotalQueries());
        assertEquals(50, response.getSummary().getSuccessfulQueries());
        assertEquals(0, response.getSummary().getFailedQueries());
        assertTrue(response.getSummary().getHitRateAt5() >= 0.0);
        assertTrue(response.getSummary().getAvgTotalLatencyMs() >= 0.0);
    }

    @Test
    @DisplayName("Query failure is isolated: failing query does not crash benchmark suite")
    void testSimulatedQueryFailureDoesNotAbortSuite() {
        // Create a custom 3-query suite
        BenchmarkSuite testSuite = new BenchmarkSuite(
                "fault-tolerance-suite", "Testing resilience", "1.0", "test-repo",
                List.of(
                        new BenchmarkQueryDefinition("Q1", "Query 1", "Class identification", "test-repo", "CLASS", List.of(), "a1", List.of()),
                        new BenchmarkQueryDefinition("Q2", "Query 2 FAILS", "Method implementation", "test-repo", "METHOD", List.of(), "a2", List.of()),
                        new BenchmarkQueryDefinition("Q3", "Query 3", "Exception handling", "test-repo", "CLASS", List.of(), "a3", List.of())
                )
        );
        datasetProvider.registerSuite(testSuite);

        // Retrieval service that throws on query containing "FAILS"
        RetrievalServiceImpl faultInjectingService = new RetrievalServiceImpl(null, null, null, null, null) {
            @Override
            public List<Document> retrieve(String query, int topK, String repositoryId, ChatMode mode) {
                if (query.contains("FAILS")) {
                    throw new RuntimeException("Simulated Vector DB Connection Timeout");
                }
                return List.of(new Document("ok", Map.of("type", "CLASS")));
            }
        };

        RetrievalBenchmark benchmark = new RetrievalBenchmark(
                faultInjectingService,
                intentClassifier,
                datasetProvider,
                evaluator
        );

        BenchmarkRequest request = new BenchmarkRequest("test-repo", "fault-tolerance-suite", 5, "DENSE_ONLY", false);
        BenchmarkResponse response = benchmark.runBenchmark(request);

        assertNotNull(response);
        assertEquals(3, response.getResults().size());

        // Query 1: SUCCESS
        assertEquals("SUCCESS", response.getResults().get(0).getStatus());
        assertNull(response.getResults().get(0).getErrorMessage());

        // Query 2: FAILED with error message preserved
        assertEquals("FAILED", response.getResults().get(1).getStatus());
        assertNotNull(response.getResults().get(1).getErrorMessage());
        assertTrue(response.getResults().get(1).getErrorMessage().contains("Simulated Vector DB Connection Timeout"));

        // Query 3: SUCCESS (suite continued execution!)
        assertEquals("SUCCESS", response.getResults().get(2).getStatus());

        // Summary counts
        assertEquals(2, response.getSummary().getSuccessfulQueries());
        assertEquals(1, response.getSummary().getFailedQueries());
        assertEquals(1, response.getSummary().getFailureCount());
    }

    @Test
    @DisplayName("Concurrency guard rejects simultaneous benchmark runs with BenchmarkConflictException")
    void testConcurrencyGuardRejectsOverlappingRuns() throws Exception {
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch blockLatch = new CountDownLatch(1);

        BenchmarkSuite singleQuerySuite = new BenchmarkSuite(
                "concurrency-suite", "Testing concurrency", "1.0", "test-repo",
                List.of(new BenchmarkQueryDefinition("Q1", "Slow query", "Class identification", "test-repo", "CLASS", List.of(), "ans", List.of()))
        );
        datasetProvider.registerSuite(singleQuerySuite);

        RetrievalServiceImpl slowService = new RetrievalServiceImpl(null, null, null, null, null) {
            @Override
            public List<Document> retrieve(String query, int topK, String repositoryId, ChatMode mode) {
                try {
                    startLatch.countDown();
                    blockLatch.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {}
                return List.of();
            }
        };

        RetrievalBenchmark benchmark = new RetrievalBenchmark(slowService, intentClassifier, datasetProvider, evaluator);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> firstRun = executor.submit(() -> {
                benchmark.runBenchmark(new BenchmarkRequest("test-repo", "concurrency-suite", 5, "DENSE_ONLY", false));
            });

            // Wait until first run is inside execution
            assertTrue(startLatch.await(3, TimeUnit.SECONDS));

            // Second run while first is active should throw BenchmarkConflictException
            assertThrows(BenchmarkConflictException.class, () -> {
                benchmark.runBenchmark(new BenchmarkRequest("test-repo", "concurrency-suite", 5, "DENSE_ONLY", false));
            });

            blockLatch.countDown(); // unblock first run
            firstRun.get(3, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("Validates repository existence when RepositoryManagementService is present")
    void testRepositoryValidationRejectsUnindexedRepo() {
        RepositoryManagementService mockRepoService = new RepositoryManagementService() {
            @Override public List<String> listRepositories() { return List.of("repo-alpha", "repo-beta"); }
            @Override public boolean removeRepository(String repositoryId) { return false; }
            @Override public void saveRepositoryStats(String repositoryId, com.shravan.jcode_intelligence.model.IndexingStatistics stats) {}
            @Override public com.shravan.jcode_intelligence.dto.response.RepositoryStatsDTO getRepositoryStats(String repositoryId) { return null; }
        };

        RetrievalBenchmark benchmark = new RetrievalBenchmark(
                new FixedDocumentsRetrievalService(List.of()),
                intentClassifier,
                datasetProvider,
                evaluator,
                mockRepoService,
                new ObjectMapper(),
                "target/benchmark-runs/"
        );

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                benchmark.runBenchmark(new BenchmarkRequest("unindexed-repo-999")));

        assertTrue(ex.getMessage().contains("Repository 'unindexed-repo-999' is not indexed"));
    }

    @Test
    @DisplayName("Legacy runBenchmark(repositoryId) adapter functions cleanly")
    void testLegacyAdapterMethod() {
        RetrievalBenchmark benchmark = new RetrievalBenchmark(
                new FixedDocumentsRetrievalService(List.of(new Document("test", Map.of("type", "CLASS", "elementName", "TestElement")))),
                intentClassifier,
                datasetProvider,
                evaluator
        );

        List<BenchmarkResult> results = benchmark.runBenchmark("javaparser");
        assertNotNull(results);
        assertEquals(50, results.size());
        for (BenchmarkResult res : results) {
            assertNotNull(res.query());
            assertNotNull(res.classifiedIntent());
            assertTrue(res.latencyMs() >= 0);
        }
    }

    @Test
    @DisplayName("Custom questions without ground truth report operational metrics and omit IR quality metrics")
    void testCustomQuestionsWithoutGroundTruth() {
        Document doc1 = new Document("chunk 1", Map.of("filePath", "src/Parser.java", "elementName", "Parser", "type", "CLASS", "chunkId", "c1"));
        Document doc2 = new Document("chunk 2", Map.of("filePath", "src/Lexer.java", "elementName", "Lexer", "type", "CLASS", "chunkId", "c2"));

        RetrievalBenchmark benchmark = new RetrievalBenchmark(
                new FixedDocumentsRetrievalService(List.of(doc1, doc2)),
                intentClassifier,
                datasetProvider,
                evaluator
        );

        BenchmarkRequest request = new BenchmarkRequest("javaparser", List.of(
                "Where is the main parser entry point?",
                "How does the application handle syntax errors?"
        ));

        BenchmarkResponse response = benchmark.runBenchmark(request);

        assertNotNull(response);
        assertEquals("CUSTOM_QUESTIONS", response.getMode());
        assertEquals("javaparser", response.getRepositoryName());
        assertEquals(2, response.getResults().size());

        // Per-query verification
        for (QueryBenchmarkResultDTO qr : response.getResults()) {
            assertEquals("SUCCESS", qr.getStatus());
            assertFalse(qr.isHasGroundTruth(), "Custom question without GT must have hasGroundTruth = false");
            assertNull(qr.getQualityMetrics(), "Quality metrics must be null when GT is absent");
            assertNotNull(qr.getOperationalMetrics());
            assertEquals(2, qr.getRetrievedChunks().size());
            assertNull(qr.getRetrievedChunks().get(0).getRelevant(), "Chunk relevance must be null when GT absent");
            assertNull(qr.getRetrievedChunks().get(0).getRelevanceGrade(), "Relevance grade must be null when GT absent");
        }

        // Summary verification
        assertNotNull(response.getSummary());
        assertFalse(response.getSummary().isHasGroundTruth());
        assertNull(response.getSummary().getHitRateAt5());
        assertNull(response.getSummary().getRecallAt5());
        assertNull(response.getSummary().getPrecisionAt5());
        assertNull(response.getSummary().getMrr());
        assertNull(response.getSummary().getNdcgAt5());
        assertEquals(2, response.getSummary().getTotalQueries());
        assertEquals(2, response.getSummary().getSuccessfulQueries());
        assertEquals(0, response.getSummary().getFailedQueries());
        assertEquals(2.0, response.getSummary().getAvgRetrievedChunks());
    }

    @Test
    @DisplayName("Custom questions with ground truth calculate IR quality metrics")
    void testCustomQuestionsWithGroundTruth() {
        Document doc = new Document("chunk 1", Map.of("filePath", "src/Parser.java", "elementName", "parse", "type", "METHOD", "chunkId", "c1"));

        RetrievalBenchmark benchmark = new RetrievalBenchmark(
                new FixedDocumentsRetrievalService(List.of(doc)),
                intentClassifier,
                datasetProvider,
                evaluator
        );

        BenchmarkRequest request = new BenchmarkRequest("javaparser");
        request.setCustomQuestions(List.of(
                new com.shravan.jcode_intelligence.dto.request.CustomBenchmarkQuestionDTO(
                        "custom-1",
                        "Where is the parser method?",
                        "Method",
                        "METHOD",
                        List.of(new RelevantChunkDefinition("src/Parser.java", "parse", 3)),
                        "Reference answer"
                )
        ));

        BenchmarkResponse response = benchmark.runBenchmark(request);

        assertNotNull(response);
        assertEquals("CUSTOM_QUESTIONS", response.getMode());
        assertEquals(1, response.getResults().size());

        QueryBenchmarkResultDTO qr = response.getResults().get(0);
        assertTrue(qr.isHasGroundTruth());
        assertNotNull(qr.getQualityMetrics());
        assertEquals(1.0, qr.getQualityMetrics().getHitRateAt5());
        assertEquals(1.0, qr.getQualityMetrics().getRecallAt5());

        assertNotNull(response.getSummary());
        assertTrue(response.getSummary().isHasGroundTruth());
        assertEquals(1.0, response.getSummary().getHitRateAt5());
        assertEquals(1.0, response.getSummary().getMrr());
    }

    @Test
    @DisplayName("Repository resolution handles case-insensitivity and ambiguous names")
    void testRepositoryResolutionCaseInsensitiveAndAmbiguous() {
        RepositoryManagementService mockRepoService = new RepositoryManagementService() {
            @Override public List<String> listRepositories() { return List.of("javaparser", "spring-boot", "spring-framework"); }
            @Override public boolean removeRepository(String repositoryId) { return false; }
            @Override public void saveRepositoryStats(String repositoryId, com.shravan.jcode_intelligence.model.IndexingStatistics stats) {}
            @Override public com.shravan.jcode_intelligence.dto.response.RepositoryStatsDTO getRepositoryStats(String repositoryId) { return null; }
        };

        AtomicInteger retrieveCalledWith = new AtomicInteger(0);
        RetrievalServiceImpl recordingService = new RetrievalServiceImpl(null, null, null, null, null) {
            @Override
            public List<Document> retrieve(String query, int topK, String repositoryId, ChatMode mode) {
                if ("javaparser".equals(repositoryId)) {
                    retrieveCalledWith.incrementAndGet();
                }
                return List.of();
            }
        };

        RetrievalBenchmark benchmark = new RetrievalBenchmark(
                recordingService, intentClassifier, datasetProvider, evaluator,
                mockRepoService, new ObjectMapper(), "target/benchmark-runs/"
        );

        // 1. Case-insensitive match ("JavaParser" -> "javaparser")
        BenchmarkRequest requestCase = new BenchmarkRequest("JavaParser", List.of("Where is parser?"));
        BenchmarkResponse response = benchmark.runBenchmark(requestCase);
        assertEquals("javaparser", response.getRepositoryName());
        assertEquals(1, retrieveCalledWith.get());

        // 2. Ambiguous match ("spring" matches spring-boot and spring-framework)
        BenchmarkRequest requestAmbiguous = new BenchmarkRequest("spring", List.of("Where is Spring?"));
        IllegalArgumentException exAmbiguous = assertThrows(IllegalArgumentException.class, () ->
                benchmark.runBenchmark(requestAmbiguous));
        assertTrue(exAmbiguous.getMessage().contains("Ambiguous repository name 'spring'"));

        // 3. Unindexed repo
        BenchmarkRequest requestMissing = new BenchmarkRequest("unknown-repo", List.of("Where is unknown?"));
        IllegalArgumentException exMissing = assertThrows(IllegalArgumentException.class, () ->
                benchmark.runBenchmark(requestMissing));
        assertTrue(exMissing.getMessage().contains("Repository 'unknown-repo' is not indexed"));
    }

    @Test
    @DisplayName("Empty repository list rejects requested repository as unindexed")
    void testEmptyRepositoryListRejectsRepo() {
        RepositoryManagementService emptyRepoService = new RepositoryManagementService() {
            @Override public List<String> listRepositories() { return Collections.emptyList(); }
            @Override public boolean removeRepository(String repositoryId) { return false; }
            @Override public void saveRepositoryStats(String repositoryId, com.shravan.jcode_intelligence.model.IndexingStatistics stats) {}
            @Override public com.shravan.jcode_intelligence.dto.response.RepositoryStatsDTO getRepositoryStats(String repositoryId) { return null; }
        };

        RetrievalBenchmark benchmark = new RetrievalBenchmark(
                new FixedDocumentsRetrievalService(List.of()),
                intentClassifier,
                datasetProvider,
                evaluator,
                emptyRepoService
        );

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                benchmark.runBenchmark(new BenchmarkRequest("any-repo")));
        assertTrue(ex.getMessage().contains("Repository 'any-repo' is not indexed"));
    }

    @Test
    @DisplayName("Asynchronous execution flag is rejected until non-blocking mode is implemented")
    void testAsyncExecutionRejected() {
        RetrievalBenchmark benchmark = new RetrievalBenchmark(
                new FixedDocumentsRetrievalService(List.of()),
                intentClassifier,
                datasetProvider,
                evaluator
        );

        BenchmarkRequest request = new BenchmarkRequest("javaparser");
        request.setAsync(true);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                benchmark.runBenchmark(request));
        assertTrue(ex.getMessage().contains("Asynchronous benchmark execution is not yet supported"));
    }

    @Test
    @DisplayName("Unsupported retrievalConfiguration is rejected with clear error")
    void testUnsupportedRetrievalConfigurationRejected() {
        RetrievalBenchmark benchmark = new RetrievalBenchmark(
                new FixedDocumentsRetrievalService(List.of()),
                intentClassifier,
                datasetProvider,
                evaluator
        );

        BenchmarkRequest request = new BenchmarkRequest("javaparser");
        request.setRetrievalConfiguration("NON_EXISTENT_MODE");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                benchmark.runBenchmark(request));
        assertTrue(ex.getMessage().contains("Unsupported retrievalConfiguration"));
    }

    // ── Stub Retrieval Service ────────────────────────────────

    private static class FixedDocumentsRetrievalService extends RetrievalServiceImpl {
        private final List<Document> documents;

        FixedDocumentsRetrievalService(List<Document> documents) {
            super(null, null, null, null, null);
            this.documents = documents;
        }

        @Override
        public List<Document> retrieve(String query, int topK, String repositoryId, ChatMode mode) {
            return documents;
        }
    }
}
