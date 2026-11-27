package com.shravan.jcode_intelligence.service;

import com.shravan.jcode_intelligence.dto.response.BenchmarkSummaryDTO;
import com.shravan.jcode_intelligence.dto.response.QueryBenchmarkResultDTO;
import com.shravan.jcode_intelligence.dto.response.QueryBenchmarkResultDTO.QueryOperationalMetricsDTO;
import com.shravan.jcode_intelligence.dto.response.QueryBenchmarkResultDTO.QueryQualityMetricsDTO;
import com.shravan.jcode_intelligence.dto.response.QueryBenchmarkResultDTO.RetrievedChunkDTO;
import com.shravan.jcode_intelligence.model.benchmark.RelevantChunkDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class RetrievalEvaluatorTest {

    private RetrievalEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new RetrievalEvaluator();
    }

    @Test
    @DisplayName("HitRate@K correctly identifies presence of relevant chunks within cutoff")
    void testHitRateAtK() {
        List<Integer> grades = List.of(0, 0, 0, 2, 0);

        assertEquals(0.0, evaluator.computeHitRate(grades, 3), 0.0001);
        assertEquals(1.0, evaluator.computeHitRate(grades, 5), 0.0001);
        assertEquals(1.0, evaluator.computeHitRate(grades, 25), 0.0001);

        List<Integer> allIrrelevant = List.of(0, 0, 0, 0);
        assertEquals(0.0, evaluator.computeHitRate(allIrrelevant, 5), 0.0001);

        List<Integer> empty = List.of();
        assertEquals(0.0, evaluator.computeHitRate(empty, 5), 0.0001);
    }

    @Test
    @DisplayName("Precision@K calculates proportion of retrieved relevant chunks over K")
    void testPrecisionAtK() {
        List<Integer> grades = List.of(2, 0, 3, 0, 1);

        // At K=3: 2 relevant chunks out of 3 = 2/3
        assertEquals(2.0 / 3.0, evaluator.computePrecision(grades, 3), 0.0001);

        // At K=5: 3 relevant chunks out of 5 = 3/5
        assertEquals(3.0 / 5.0, evaluator.computePrecision(grades, 5), 0.0001);

        // At K=25: 3 relevant chunks out of 25 = 3/25
        assertEquals(3.0 / 25.0, evaluator.computePrecision(grades, 25), 0.0001);

        // Zero cutoff
        assertEquals(0.0, evaluator.computePrecision(grades, 0), 0.0001);
    }

    @Test
    @DisplayName("ReciprocalRank computes 1 / rank of first relevant chunk")
    void testReciprocalRank() {
        // First relevant at rank 1
        assertEquals(1.0, evaluator.computeReciprocalRank(List.of(3, 0, 1)), 0.0001);

        // First relevant at rank 2
        assertEquals(0.5, evaluator.computeReciprocalRank(List.of(0, 2, 1)), 0.0001);

        // First relevant at rank 3
        assertEquals(1.0 / 3.0, evaluator.computeReciprocalRank(List.of(0, 0, 2, 1)), 0.0001);

        // No relevant chunks
        assertEquals(0.0, evaluator.computeReciprocalRank(List.of(0, 0, 0)), 0.0001);

        // Empty list
        assertEquals(0.0, evaluator.computeReciprocalRank(List.of()), 0.0001);
    }

    @Test
    @DisplayName("NDCG@K calculates graded ranking quality against ideal DCG")
    void testNdcgAtK() {
        List<RelevantChunkDefinition> groundTruth = List.of(
                new RelevantChunkDefinition("Foo.java", "bar", 3),
                new RelevantChunkDefinition("Foo.java", "baz", 2),
                new RelevantChunkDefinition("Foo.java", "qux", 1)
        );

        // Perfect ranking [3, 2, 1]
        List<Integer> perfectGrades = List.of(3, 2, 1);
        assertEquals(1.0, evaluator.computeNdcg(perfectGrades, 3, groundTruth), 0.0001);

        // Inverted ranking [1, 2, 3] should be less than 1.0 but greater than 0
        List<Integer> invertedGrades = List.of(1, 2, 3);
        double invertedNdcg = evaluator.computeNdcg(invertedGrades, 3, groundTruth);
        assertTrue(invertedNdcg > 0.0 && invertedNdcg < 1.0, "Inverted ranking should have NDCG between 0 and 1");

        // Suboptimal ranking [3, 2, 0, 1]
        List<Integer> subOptimal = List.of(3, 2, 0, 1);
        double ndcg3 = evaluator.computeNdcg(subOptimal, 3, groundTruth);
        assertTrue(ndcg3 > 0.9, "NDCG@3 for [3, 2, 0] should be high: " + ndcg3);

        // All irrelevant
        List<Integer> zeroGrades = List.of(0, 0, 0);
        assertEquals(0.0, evaluator.computeNdcg(zeroGrades, 3, groundTruth), 0.0001);

        // Empty ground truth
        assertEquals(0.0, evaluator.computeNdcg(perfectGrades, 3, List.of()), 0.0001);
    }

    @Test
    @DisplayName("ContextPrecision measures relevance density toward top of ranking")
    void testContextPrecision() {
        // [1, 0, 1]: relevant at 1 (prec=1/1), irrelevant at 2, relevant at 3 (prec=2/3)
        // sum = 1.0 + 0.6667 = 1.6667; total relevant = 2; context precision = 0.8333
        List<Integer> grades = List.of(1, 0, 1);
        assertEquals(0.8333, evaluator.computeContextPrecision(grades), 0.001);

        // Perfect [1, 1, 1]: context precision = 1.0
        assertEquals(1.0, evaluator.computeContextPrecision(List.of(2, 2, 2)), 0.0001);

        // None relevant
        assertEquals(0.0, evaluator.computeContextPrecision(List.of(0, 0, 0)), 0.0001);
        assertEquals(0.0, evaluator.computeContextPrecision(List.of()), 0.0001);
    }

    @Test
    @DisplayName("End-to-end evaluateQuery accurately scores documents and populates chunk DTOs")
    void testEvaluateQueryEndToEnd() {
        Document doc1 = createDocument("com/example/UserService.java", "UserService", "findUser");
        Document doc2 = createDocument("com/example/OrderService.java", "OrderService", "createOrder");
        Document doc3 = createDocument("com/example/UserHelper.java", "UserHelper", "formatName");

        List<Document> retrieved = List.of(doc1, doc2, doc3);

        List<RelevantChunkDefinition> groundTruth = List.of(
                new RelevantChunkDefinition("com/example/UserService.java", "findUser", 3),
                new RelevantChunkDefinition("com/example/UserHelper.java", "formatName", 2)
        );

        List<RetrievedChunkDTO> chunkDTOs = new ArrayList<>();
        QueryQualityMetricsDTO metrics = evaluator.evaluateQuery(retrieved, groundTruth, chunkDTOs);

        // Check metrics
        assertEquals(1.0, metrics.getHitRateAt3(), 0.0001);
        assertEquals(1.0, metrics.getReciprocalRank(), 0.0001); // doc1 is relevant at rank 1
        assertEquals(1.0, metrics.getRecallAt3(), 0.0001); // 2 of 2 ground truths retrieved
        assertEquals(2.0 / 3.0, metrics.getPrecisionAt3(), 0.0001);
        assertTrue(metrics.getNdcgAt3() > 0.8);
        assertEquals(1.0, metrics.getContextRecall(), 0.0001);

        // Check chunk DTOs
        assertEquals(3, chunkDTOs.size());
        assertTrue(chunkDTOs.get(0).isRelevant());
        assertEquals(3, chunkDTOs.get(0).getRelevanceGrade());
        assertFalse(chunkDTOs.get(1).isRelevant());
        assertEquals(0, chunkDTOs.get(1).getRelevanceGrade());
        assertTrue(chunkDTOs.get(2).isRelevant());
        assertEquals(2, chunkDTOs.get(2).getRelevanceGrade());
    }

    @Test
    @DisplayName("matches handles symbol fragment matching and normalized slashes")
    void testMatchesNormalization() {
        Document doc = createDocument("src\\main\\java\\com\\example\\App.java", "App", "main#String[]");
        RelevantChunkDefinition gt = new RelevantChunkDefinition("com/example/App.java", "main", 3);

        assertTrue(evaluator.matches(doc, gt), "Windows backslash path and method fragment should match");

        RelevantChunkDefinition nonMatch = new RelevantChunkDefinition("com/example/Other.java", "main", 3);
        assertFalse(evaluator.matches(doc, nonMatch));
    }

    @Test
    @DisplayName("evaluateQuery with empty ground truth populates chunks without relevance and returns null metrics")
    void testEvaluateQueryWithoutGroundTruth() {
        Document doc1 = createDocument("src/A.java", "A", "methodA");
        List<RetrievedChunkDTO> chunks = new ArrayList<>();

        QueryQualityMetricsDTO metrics = evaluator.evaluateQuery(List.of(doc1), List.of(), chunks);
        assertNull(metrics, "Quality metrics must be null when ground truth is empty");
        assertEquals(1, chunks.size());
        assertNull(chunks.get(0).getRelevant());
        assertNull(chunks.get(0).getRelevanceGrade());
        assertEquals("src/A.java", chunks.get(0).getFilePath());
    }

    @Test
    @DisplayName("aggregateSummary computes p50, p95, and max latency accurately across results")
    void testAggregateSummaryLatencyPercentiles() {
        List<QueryBenchmarkResultDTO> results = new ArrayList<>();
        // 10 queries with latencies: 10, 20, 30, 40, 50, 60, 70, 80, 90, 100
        for (long lat = 10; lat <= 100; lat += 10) {
            QueryBenchmarkResultDTO r = new QueryBenchmarkResultDTO();
            r.setStatus("SUCCESS");
            r.setHasGroundTruth(false);
            r.setOperationalMetrics(new QueryOperationalMetricsDTO(lat, 0L, lat, 0L, 5));
            results.add(r);
        }

        BenchmarkSummaryDTO summary = evaluator.aggregateSummary(results, 1000L, 0);
        assertNotNull(summary);
        assertEquals(55.0, summary.getAvgTotalLatencyMs(), 0.0001);
        assertEquals(50.0, summary.getP50LatencyMs(), 0.0001);
        assertEquals(100.0, summary.getP95LatencyMs(), 0.0001);
        assertEquals(100.0, summary.getMaxLatencyMs(), 0.0001);
    }

    @Test
    @DisplayName("computePercentile correctly resolves nearest rank across various list sizes")
    void testComputePercentile() {
        assertEquals(0.0, evaluator.computePercentile(List.of(), 50.0));
        assertEquals(42.0, evaluator.computePercentile(List.of(42L), 50.0));
        assertEquals(42.0, evaluator.computePercentile(List.of(42L), 95.0));

        List<Long> values = List.of(10L, 20L, 30L, 40L, 50L);
        assertEquals(30.0, evaluator.computePercentile(values, 50.0)); // ceil(2.5)-1 = 2 -> 30
        assertEquals(50.0, evaluator.computePercentile(values, 95.0)); // ceil(4.75)-1 = 4 -> 50
    }

    @Test
    @DisplayName("aggregateSummary omits quality metrics when results contain no ground truth")
    void testAggregateSummaryWithoutGroundTruth() {
        QueryBenchmarkResultDTO qr1 = new QueryBenchmarkResultDTO();
        qr1.setStatus("SUCCESS");
        qr1.setHasGroundTruth(false);
        qr1.setQualityMetrics(null);
        qr1.setOperationalMetrics(new QueryOperationalMetricsDTO(100L, 10L, 20L, 5L, 3));

        BenchmarkSummaryDTO summary = evaluator.aggregateSummary(List.of(qr1), 100L, 0);

        assertNotNull(summary);
        assertFalse(summary.isHasGroundTruth());
        assertNull(summary.getHitRateAt5());
        assertNull(summary.getRecallAt5());
        assertNull(summary.getPrecisionAt5());
        assertNull(summary.getMrr());
        assertNull(summary.getNdcgAt5());
        assertEquals(100.0, summary.getAvgTotalLatencyMs());
        assertEquals(3.0, summary.getAvgRetrievedChunks());
    }

    @Test
    @DisplayName("Duplicate chunks matching same ground truth do not inflate relevance count or precision")
    void testDuplicateGroundTruthDoesNotInflatePrecision() {
        // Two retrieved chunks from same file & symbol
        Document doc1 = createDocument("src/Parser.java", "Parser", "parse");
        Document doc2 = createDocument("src/Parser.java", "Parser", "parse");

        // Only one ground truth expectation
        List<RelevantChunkDefinition> groundTruth = List.of(
                new RelevantChunkDefinition("src/Parser.java", "parse", 3)
        );

        List<RetrievedChunkDTO> chunks = new ArrayList<>();
        QueryQualityMetricsDTO metrics = evaluator.evaluateQuery(List.of(doc1, doc2), groundTruth, chunks);

        assertNotNull(metrics);
        // Both chunks match ground truth, with second chunk noted as duplicate
        assertTrue(chunks.get(0).isRelevant());
        assertEquals(3, chunks.get(0).getRelevanceGrade());
        assertTrue(chunks.get(1).isRelevant(), "Both chunks match ground truth evidence");
        assertEquals(3, chunks.get(1).getRelevanceGrade());
        assertTrue(chunks.get(1).getMatchReason().contains("DUPLICATE_OF_PREVIOUS_CHUNK"));

        // Recall@3 should be 1.0 (1/1 retrieved)
        assertEquals(1.0, metrics.getRecallAt3(), 0.0001);
        // Precision@3 is 1 relevant / 3 cutoff = 1/3 (not 2/3)
        assertEquals(1.0 / 3.0, metrics.getPrecisionAt3(), 0.0001);
    }

    @Test
    @DisplayName("Graded relevance changes NDCG@K while binary metrics remain constant")
    void testGradedRelevanceChangesNdcgWhileBinaryMetricsRemainConstant() {
        Document doc1 = createDocument("src/Service.java", "Service", "exec");

        // Case A: High graded relevance (grade 3)
        List<RelevantChunkDefinition> gtGrade3 = List.of(
                new RelevantChunkDefinition("src/Service.java", "exec", 3)
        );
        QueryQualityMetricsDTO metricsA = evaluator.evaluateQuery(List.of(doc1), gtGrade3, new ArrayList<>());

        // Case B: Low graded relevance (grade 1)
        List<RelevantChunkDefinition> gtGrade1 = List.of(
                new RelevantChunkDefinition("src/Service.java", "exec", 1)
        );
        QueryQualityMetricsDTO metricsB = evaluator.evaluateQuery(List.of(doc1), gtGrade1, new ArrayList<>());

        // Binary metrics remain identical at default threshold 1
        assertEquals(metricsA.getHitRateAt3(), metricsB.getHitRateAt3(), 0.0001);
        assertEquals(metricsA.getRecallAt3(), metricsB.getRecallAt3(), 0.0001);
        assertEquals(metricsA.getPrecisionAt3(), metricsB.getPrecisionAt3(), 0.0001);
        assertEquals(metricsA.getReciprocalRank(), metricsB.getReciprocalRank(), 0.0001);

        // When ground truth has multiple chunks, changing the grade ordering strictly alters NDCG
        Document doc2 = createDocument("src/Service.java", "Service", "helper");
        List<Document> retrieved = List.of(doc1, doc2);

        // Ranking matches ideal [3, 1] -> NDCG = 1.0
        List<RelevantChunkDefinition> gtIdeal = List.of(
                new RelevantChunkDefinition("src/Service.java", "exec", 3),
                new RelevantChunkDefinition("src/Service.java", "helper", 1)
        );
        QueryQualityMetricsDTO metricsIdeal = evaluator.evaluateQuery(retrieved, gtIdeal, new ArrayList<>());
        assertEquals(1.0, metricsIdeal.getNdcgAt3(), 0.0001);

        // Ground truth expects [1, 3] but retrieval produced [3, 1] (or vice versa)
        List<RelevantChunkDefinition> gtInverted = List.of(
                new RelevantChunkDefinition("src/Service.java", "exec", 1),
                new RelevantChunkDefinition("src/Service.java", "helper", 3)
        );
        QueryQualityMetricsDTO metricsInverted = evaluator.evaluateQuery(retrieved, gtInverted, new ArrayList<>());
        assertTrue(metricsInverted.getNdcgAt3() < 1.0, "Suboptimal grade ordering must produce NDCG < 1.0");
    }

    @Test
    @DisplayName("Configurable relevanceThreshold correctly filters binary metrics")
    void testConfigurableRelevanceThreshold() {
        Document docGrade1 = createDocument("src/A.java", "A", "m1");
        Document docGrade2 = createDocument("src/B.java", "B", "m2");

        List<RelevantChunkDefinition> groundTruth = List.of(
                new RelevantChunkDefinition("src/A.java", "m1", 1),
                new RelevantChunkDefinition("src/B.java", "m2", 2)
        );

        // Evaluator with threshold = 1 (default)
        RetrievalEvaluator eval1 = new RetrievalEvaluator(1);
        List<RetrievedChunkDTO> chunks1 = new ArrayList<>();
        QueryQualityMetricsDTO metrics1 = eval1.evaluateQuery(List.of(docGrade1, docGrade2), groundTruth, chunks1);

        assertTrue(chunks1.get(0).isRelevant(), "Grade 1 is relevant when threshold is 1");
        assertTrue(chunks1.get(1).isRelevant(), "Grade 2 is relevant when threshold is 1");
        assertEquals(2.0 / 3.0, metrics1.getPrecisionAt3(), 0.0001);
        assertEquals(1.0, metrics1.getRecallAt3(), 0.0001);

        // Evaluator with threshold = 2 (strict)
        RetrievalEvaluator eval2 = new RetrievalEvaluator(2);
        List<RetrievedChunkDTO> chunks2 = new ArrayList<>();
        QueryQualityMetricsDTO metrics2 = eval2.evaluateQuery(List.of(docGrade1, docGrade2), groundTruth, chunks2);

        assertFalse(chunks2.get(0).isRelevant(), "Grade 1 is NOT relevant when threshold is 2");
        assertTrue(chunks2.get(1).isRelevant(), "Grade 2 is relevant when threshold is 2");
        assertEquals(1.0 / 3.0, metrics2.getPrecisionAt3(), 0.0001);
        assertEquals(1.0, metrics2.getRecallAt3(), 0.0001); // 1 relevant chunk retrieved out of 1 thresholded GT
    }

    @Test
    @DisplayName("Datasets without line ranges match dynamically on normalized paths and symbols")
    void testDatasetsWithoutLineRangesMatchDynamically() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("filePath", "javaparser-core/src/main/java/com/github/javaparser/ast/Node.java");
        meta.put("className", "Node");
        meta.put("elementName", "findAll");
        meta.put("type", "METHOD");
        meta.put("startLine", 980);
        meta.put("endLine", 995);
        Document doc = new Document("findAll code", meta);

        // Ground truth with null startLine and null endLine
        RelevantChunkDefinition gtNoLines = new RelevantChunkDefinition("com/github/javaparser/ast/Node.java", "findAll", 3);
        assertNull(gtNoLines.getStartLine());
        assertNull(gtNoLines.getEndLine());

        assertTrue(evaluator.matches(doc, gtNoLines), "Should match dynamically without line ranges");

        List<RetrievedChunkDTO> chunks = new ArrayList<>();
        QueryQualityMetricsDTO metrics = evaluator.evaluateQuery(List.of(doc), List.of(gtNoLines), chunks);
        assertNotNull(metrics);
        assertEquals(1.0, metrics.getHitRateAt3(), 0.0001);
        assertTrue(chunks.get(0).isRelevant());
        assertEquals("PATH_AND_SYMBOL_MATCH (EXACT_ELEMENT_MATCH)", chunks.get(0).getMatchReason());
    }

    @Test
    @DisplayName("Overloaded method signatures match base symbol correctly")
    void testOverloadedMethodSignatures() {
        Map<String, Object> meta1 = new HashMap<>();
        meta1.put("filePath", "src/Node.java");
        meta1.put("elementName", "findFirst(Class<T>)");
        meta1.put("type", "METHOD");
        Document doc1 = new Document("code", meta1);

        Map<String, Object> meta2 = new HashMap<>();
        meta2.put("filePath", "src/Node.java");
        meta2.put("elementName", "findFirst(Predicate<Node>)");
        meta2.put("type", "METHOD");
        Document doc2 = new Document("code", meta2);

        Map<String, Object> meta3 = new HashMap<>();
        meta3.put("filePath", "src/Node.java");
        meta3.put("elementName", "findFirst#0");
        meta3.put("type", "METHOD_FRAGMENT");
        Document doc3 = new Document("code", meta3);

        RelevantChunkDefinition gt = new RelevantChunkDefinition("src/Node.java", "findFirst", 3);

        assertTrue(evaluator.matches(doc1, gt), "findFirst(Class<T>) must match findFirst");
        assertTrue(evaluator.matches(doc2, gt), "findFirst(Predicate<Node>) must match findFirst");
        assertTrue(evaluator.matches(doc3, gt), "findFirst#0 fragment must match findFirst");
    }

    @Test
    @DisplayName("Synthetic multi-repository fixture proves evaluation is generic across codebases")
    void testSyntheticMultiRepositoryFixture() {
        // Multi-repo: OrderService in order-service repo
        Document orderDoc = createDocument("services/order-service/src/main/java/com/shop/order/OrderService.java",
                "OrderService", "processPayment");
        // Multi-repo: PaymentProcessor in payment-service repo
        Document paymentDoc = createDocument("services/payment-service/src/main/java/com/shop/payment/PaymentProcessor.java",
                "PaymentProcessor", "chargeCreditCard");

        List<RelevantChunkDefinition> syntheticGt = List.of(
                new RelevantChunkDefinition("com/shop/order/OrderService.java", "processPayment", 3),
                new RelevantChunkDefinition("com/shop/payment/PaymentProcessor.java", "chargeCreditCard", 2)
        );

        List<RetrievedChunkDTO> chunks = new ArrayList<>();
        QueryQualityMetricsDTO metrics = evaluator.evaluateQuery(List.of(orderDoc, paymentDoc), syntheticGt, chunks);

        assertNotNull(metrics);
        assertEquals(1.0, metrics.getHitRateAt3(), 0.0001);
        assertEquals(1.0, metrics.getRecallAt3(), 0.0001);
        assertEquals(2.0 / 3.0, metrics.getPrecisionAt3(), 0.0001);
        assertEquals(1.0, metrics.getReciprocalRank(), 0.0001);
        assertTrue(chunks.get(0).isRelevant());
        assertTrue(chunks.get(1).isRelevant());
    }

    @Test
    @DisplayName("findUnmatchedGroundTruths reports only unsatisfied ground-truth chunks with grades")
    void testFindUnmatchedGroundTruths() {
        Document doc1 = createDocument("src/A.java", "A", "m1");

        RelevantChunkDefinition gt1 = new RelevantChunkDefinition("src/A.java", "m1", 3);
        RelevantChunkDefinition gt2 = new RelevantChunkDefinition("src/B.java", "m2", 2);
        RelevantChunkDefinition gt3 = new RelevantChunkDefinition("src/C.java", "m3", 1);

        List<RelevantChunkDefinition> unmatched = evaluator.findUnmatchedGroundTruths(List.of(doc1), List.of(gt1, gt2, gt3));

        assertEquals(2, unmatched.size(), "Should return exactly 2 unmatched ground truths");
        assertEquals("src/B.java", unmatched.get(0).getFilePath());
        assertEquals(2, unmatched.get(0).getRelevance());
        assertEquals("src/C.java", unmatched.get(1).getFilePath());
        assertEquals(1, unmatched.get(1).getRelevance());
    }

    @Test
    @DisplayName("Method chunk inside class does not match class-level ground truth symbol")
    void testMethodChunkDoesNotMatchClassSymbol() {
        // A method named "visit" inside class "LexicalPreservingPrinter"
        Document methodDoc = createDocument("src/LexicalPreservingPrinter.java", "LexicalPreservingPrinter", "visit");
        // A class chunk for "LexicalPreservingPrinter"
        Map<String, Object> classMeta = new HashMap<>();
        classMeta.put("filePath", "src/LexicalPreservingPrinter.java");
        classMeta.put("className", "LexicalPreservingPrinter");
        classMeta.put("elementName", "LexicalPreservingPrinter");
        classMeta.put("type", "CLASS");
        Document classDoc = new Document("class content", classMeta);

        RelevantChunkDefinition gtClass = new RelevantChunkDefinition("src/LexicalPreservingPrinter.java", "LexicalPreservingPrinter", 3);

        assertFalse(evaluator.matches(methodDoc, gtClass), "Method chunk inside class must not falsely match class symbol");
        assertTrue(evaluator.matches(classDoc, gtClass), "Class chunk must match class symbol");
    }

    @Test
    @DisplayName("maxDisplayChunks limits populated retrieved chunks while scoring all candidates")
    void testMaxDisplayChunksLimitsOutput() {
        Document doc1 = createDocument("src/A.java", "A", "m1");
        Document doc2 = createDocument("src/B.java", "B", "m2");
        Document doc3 = createDocument("src/C.java", "C", "m3");

        List<RelevantChunkDefinition> groundTruth = List.of(
                new RelevantChunkDefinition("src/C.java", "m3", 3)
        );

        List<RetrievedChunkDTO> chunks = new ArrayList<>();
        // 3 documents retrieved, but maxDisplayChunks is 2
        QueryQualityMetricsDTO metrics = evaluator.evaluateQuery(List.of(doc1, doc2, doc3), groundTruth, chunks, 2);

        // Display list must be capped at 2
        assertEquals(2, chunks.size());
        // But quality metrics should have evaluated doc3 at rank 3!
        assertEquals(1.0, metrics.getHitRateAt3(), 0.0001);
        assertEquals(1.0 / 3.0, metrics.getReciprocalRank(), 0.0001);
    }

    @Test
    @DisplayName("matches rejects path when additional extension characters follow gtPath")
    void testMatchesRejectsFilenameExtensionSubstring() {
        Document backupDoc = createDocument("src/main/java/Foo.java.backup", "Foo", "bar");
        RelevantChunkDefinition gt = new RelevantChunkDefinition("src/main/java/Foo.java", "bar", 3);

        assertFalse(evaluator.matches(backupDoc, gt), "Foo.java.backup must not match Foo.java");
    }

    @Test
    @DisplayName("matches validates line bounds when specified in ground truth")
    void testMatchesLineBounds() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("filePath", "src/Foo.java");
        meta.put("elementName", "bar");
        meta.put("startLine", 10);
        meta.put("endLine", 25);
        Document doc = new Document("code", meta);

        RelevantChunkDefinition overlappingGt = new RelevantChunkDefinition("src/Foo.java", "bar", 20, 35, 3);
        assertTrue(evaluator.matches(doc, overlappingGt), "Overlapping line ranges should match");

        RelevantChunkDefinition nonOverlappingGt = new RelevantChunkDefinition("src/Foo.java", "bar", 50, 80, 3);
        assertFalse(evaluator.matches(doc, nonOverlappingGt), "Non-overlapping line ranges should not match");
    }

    @Test
    @DisplayName("Context recall does not exceed 1.0 when relevanceThreshold > 1 and lower-grade matches exist")
    void testContextRecallWithRelevanceThresholdAboveOne() {
        evaluator.setRelevanceThreshold(2);

        // Ground truths: one with grade 1 (below threshold), one with grade 2 (at threshold)
        List<RelevantChunkDefinition> groundTruths = List.of(
                new RelevantChunkDefinition("src/A.java", "methodLow", 1),
                new RelevantChunkDefinition("src/B.java", "methodHigh", 2)
        );

        // Retrieved documents matching both ground truths
        Document docLow = createDocument("src/A.java", "A", "methodLow");
        Document docHigh = createDocument("src/B.java", "B", "methodHigh");

        QueryQualityMetricsDTO metrics = evaluator.evaluateQuery(List.of(docLow, docHigh), groundTruths, new ArrayList<>());

        assertNotNull(metrics);
        // totalRelevantGt is 1 (only methodHigh has grade >= 2)
        // satisfiedRelevantGts should only count methodHigh (grade 2 >= 2)
        // Context recall should be 1 / 1 = 1.0, NOT 2 / 1 = 2.0
        assertEquals(1.0, metrics.getContextRecall(), 0.0001,
                "Context recall must not exceed 1.0 when matched ground truths have grades below threshold");

        // If only docLow is retrieved, context recall should be 0.0 because no chunk met the relevance threshold
        QueryQualityMetricsDTO metricsLowOnly = evaluator.evaluateQuery(List.of(docLow), groundTruths, new ArrayList<>());
        assertNotNull(metricsLowOnly);
        assertEquals(0.0, metricsLowOnly.getContextRecall(), 0.0001,
                "Context recall should be 0.0 when only sub-threshold ground truths match");
    }

    private Document createDocument(String filePath, String className, String elementName) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("filePath", filePath);
        meta.put("className", className);
        meta.put("elementName", elementName);
        meta.put("methodName", elementName);
        meta.put("type", "METHOD");
        meta.put("chunkId", filePath + ":" + elementName);
        return new Document("stub content for " + elementName, meta);
    }
}

