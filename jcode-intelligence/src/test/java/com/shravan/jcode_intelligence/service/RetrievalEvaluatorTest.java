package com.shravan.jcode_intelligence.service;

import com.shravan.jcode_intelligence.dto.response.QueryBenchmarkResultDTO;
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
    @DisplayName("aggregateSummary omits quality metrics when results contain no ground truth")
    void testAggregateSummaryWithoutGroundTruth() {
        QueryBenchmarkResultDTO qr1 = new QueryBenchmarkResultDTO();
        qr1.setStatus("SUCCESS");
        qr1.setHasGroundTruth(false);
        qr1.setQualityMetrics(null);
        qr1.setOperationalMetrics(new QueryBenchmarkResultDTO.QueryOperationalMetricsDTO(100L, 10L, 20L, 5L, 3));

        com.shravan.jcode_intelligence.dto.response.BenchmarkSummaryDTO summary =
                evaluator.aggregateSummary(List.of(qr1), 100L, 0);

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

