package com.shravan.jcode_intelligence.service;

import com.shravan.jcode_intelligence.config.IntentRetrievalConfig;
import com.shravan.jcode_intelligence.model.QueryIntent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class RetrievalRerankerTest {

    private IntentRetrievalConfig config;
    private RetrievalReranker reranker;

    @BeforeEach
    void setUp() {
        config = new IntentRetrievalConfig();
        config.setWRetrieval(10.0);
        config.setWSymbol(1.0);
        config.setWCompleteness(1.0);
        config.setNeutralRetrievalScore(0.5);
        reranker = new RetrievalReranker(config);
    }

    @Test
    @DisplayName("Strategy name is heuristic")
    void testStrategyName() {
        assertEquals("heuristic", reranker.name());
    }

    @Test
    @DisplayName("Handles null and empty document lists gracefully")
    void testNullAndEmptyHandling() {
        assertTrue(reranker.rerank(null, QueryIntent.METHOD, null, 5).isEmpty());
        assertTrue(reranker.rerank(List.of(), QueryIntent.METHOD, null, 5).isEmpty());
    }

    @Test
    @DisplayName("Applies per-set min-max normalization preferring rrfScore over denseScore")
    void testMinMaxNormalizationAndRrfPreference() {
        // Doc 1 has rrfScore=0.08 and denseScore=0.9
        Map<String, Object> m1 = new HashMap<>();
        m1.put("type", "METHOD");
        m1.put("elementName", "foo");
        m1.put(RetrievalConstants.METADATA_RRF_SCORE, 0.08);
        m1.put(RetrievalConstants.METADATA_DENSE_SCORE, 0.9);
        Document d1 = new Document("d1", "content1", m1);

        // Doc 2 has rrfScore=0.04 and denseScore=0.95
        Map<String, Object> m2 = new HashMap<>();
        m2.put("type", "METHOD");
        m2.put("elementName", "bar");
        m2.put(RetrievalConstants.METADATA_RRF_SCORE, 0.04);
        m2.put(RetrievalConstants.METADATA_DENSE_SCORE, 0.95);
        Document d2 = new Document("d2", "content2", m2);

        // Doc 3 has rrfScore=0.02
        Map<String, Object> m3 = new HashMap<>();
        m3.put("type", "METHOD");
        m3.put("elementName", "baz");
        m3.put(RetrievalConstants.METADATA_RRF_SCORE, 0.02);
        Document d3 = new Document("d3", "content3", m3);

        // For METHOD intent: chunk weight is identical for all three.
        // rrfScore: min=0.02, max=0.08.
        // d1 norm = (0.08 - 0.02) / (0.08 - 0.02) = 1.0 -> retrieval bonus = 10.0 * 1.0 = 10.0
        // d2 norm = (0.04 - 0.02) / (0.08 - 0.02) = 0.3333 -> retrieval bonus = 10.0 * 0.3333 = 3.333
        // d3 norm = (0.02 - 0.02) / (0.08 - 0.02) = 0.0 -> retrieval bonus = 10.0 * 0.0 = 0.0
        // Because rrfScore is preferred, d1 ranks above d2 despite d2 having a higher denseScore!
        List<Document> reranked = reranker.rerank(List.of(d3, d2, d1), QueryIntent.METHOD, null, 5);

        assertEquals(3, reranked.size());
        assertEquals("d1", reranked.get(0).getId());
        assertEquals("d2", reranked.get(1).getId());
        assertEquals("d3", reranked.get(2).getId());
    }

    @Test
    @DisplayName("Unscored candidates receive configurable neutral score when other candidates are scored")
    void testNeutralScoreForUnscoredCandidate() {
        // d1: scored 1.0 (max)
        Map<String, Object> m1 = new HashMap<>();
        m1.put("type", "CLASS");
        m1.put(RetrievalConstants.METADATA_DENSE_SCORE, 1.0);
        Document d1 = new Document("d1", "class 1", m1);

        // d2: unscored -> should get neutral score = 0.5
        Map<String, Object> m2 = new HashMap<>();
        m2.put("type", "CLASS");
        Document d2 = new Document("d2", "class 2", m2);

        // d3: scored 0.0 (min)
        Map<String, Object> m3 = new HashMap<>();
        m3.put("type", "CLASS");
        m3.put(RetrievalConstants.METADATA_DENSE_SCORE, 0.0);
        Document d3 = new Document("d3", "class 3", m3);

        List<Document> reranked = reranker.rerank(List.of(d3, d2, d1), QueryIntent.CLASS, null, 3);

        assertEquals(3, reranked.size());
        assertEquals("d1", reranked.get(0).getId(), "d1 with max score (1.0) must rank first");
        assertEquals("d2", reranked.get(1).getId(), "d2 with neutral score (0.5) must rank second");
        assertEquals("d3", reranked.get(2).getId(), "d3 with min score (0.0) must rank third");
    }

    @Test
    @DisplayName("Entirely unscored candidate set skips the retrieval term")
    void testEntirelyUnscoredSetSkipsRetrievalTerm() {
        Map<String, Object> m1 = new HashMap<>();
        m1.put("type", "METHOD");
        m1.put("elementName", "searchUser");
        Document d1 = new Document("d1", "content1", m1);

        Map<String, Object> m2 = new HashMap<>();
        m2.put("type", "CLASS");
        m2.put("className", "UserService");
        Document d2 = new Document("d2", "content2", m2);

        // Neither document has denseScore or rrfScore.
        // For CLASS intent, CLASS chunk weight is 2.0 (score: 20.0), METHOD chunk weight is 1.0 (score: 10.0).
        // Since retrieval term is skipped (0.0), CLASS must rank first solely based on type and symbol weights.
        List<Document> reranked = reranker.rerank(List.of(d1, d2), QueryIntent.CLASS, null, 2);

        assertEquals(2, reranked.size());
        assertEquals("d2", reranked.get(0).getId(), "CLASS chunk must win under CLASS intent when unscored");
    }

    @Test
    @DisplayName("Preserves exact symbol matches and partial fragment matches")
    void testExactSymbolMatches() {
        Map<String, Object> m1 = new HashMap<>();
        m1.put("type", "METHOD");
        m1.put("elementName", "executeJob");
        Document d1 = new Document("d1", "content1", m1);

        Map<String, Object> m2 = new HashMap<>();
        m2.put("type", "METHOD");
        m2.put("elementName", "executeJob#1"); // fragment match (+90)
        Document d2 = new Document("d2", "content2", m2);

        Map<String, Object> m3 = new HashMap<>();
        m3.put("type", "CLASS");
        m3.put("className", "executeJob"); // class match (+80)
        Document d3 = new Document("d3", "content3", m3);

        Map<String, Object> m4 = new HashMap<>();
        m4.put("type", "METHOD");
        m4.put("elementName", "otherMethod"); // no match (0)
        Document d4 = new Document("d4", "content4", m4);

        List<Document> reranked = reranker.rerank(List.of(d4, d3, d2, d1), QueryIntent.METHOD, "executeJob", 4);

        assertEquals(4, reranked.size());
        assertEquals("d1", reranked.get(0).getId(), "Exact element match (+100) must rank first");
        assertEquals("d2", reranked.get(1).getId(), "Fragment match (+90) must rank second");
        assertEquals("d3", reranked.get(2).getId(), "Class match (+80) must rank third");
        assertEquals("d4", reranked.get(3).getId(), "Non-matching must rank last");
    }

    @Test
    @DisplayName("Preserves intent-specific FIELD behavior: filtered in ARCHITECTURE, demoted in DESIGN and PACKAGE")
    void testFieldChunkBehavior() {
        Map<String, Object> fieldMeta = new HashMap<>();
        fieldMeta.put("type", "FIELD");
        Document fieldDoc = new Document("f1", "private int count;", fieldMeta);

        Map<String, Object> classMeta = new HashMap<>();
        classMeta.put("type", "CLASS");
        Document classDoc = new Document("c1", "public class Engine {}", classMeta);

        // 1. In ARCHITECTURE intent, FIELD chunks are strictly excluded (0 returned)
        List<Document> archResult = reranker.rerank(List.of(fieldDoc, classDoc), QueryIntent.ARCHITECTURE, null, 5);
        assertEquals(1, archResult.size());
        assertEquals("c1", archResult.get(0).getId());

        // 2. In DESIGN intent, FIELD chunks receive -30 demotion
        List<Document> designResult = reranker.rerank(List.of(fieldDoc, classDoc), QueryIntent.DESIGN, null, 5);
        assertEquals(2, designResult.size());
        assertEquals("c1", designResult.get(0).getId());
        assertEquals("f1", designResult.get(1).getId());
    }

    @Test
    @DisplayName("Maintains stable ties preserving encounter order for equal-scoring documents")
    void testStableTies() {
        Map<String, Object> m1 = Map.of("type", "METHOD", "elementName", "test");
        Map<String, Object> m2 = Map.of("type", "METHOD", "elementName", "test");
        Map<String, Object> m3 = Map.of("type", "METHOD", "elementName", "test");

        Document d1 = new Document("d1", "m1", m1);
        Document d2 = new Document("d2", "m2", m2);
        Document d3 = new Document("d3", "m3", m3);

        List<Document> reranked = reranker.rerank(List.of(d1, d2, d3), QueryIntent.METHOD, null, 3);
        assertEquals(List.of("d1", "d2", "d3"), reranked.stream().map(Document::getId).toList());

        List<Document> reversed = reranker.rerank(List.of(d3, d2, d1), QueryIntent.METHOD, null, 3);
        assertEquals(List.of("d3", "d2", "d1"), reversed.stream().map(Document::getId).toList());
    }

    @Test
    @DisplayName("Completeness tie-breaker remains strictly below w_retrieval even if misconfigured")
    void testCompletenessTieBreakerBelowWRetrieval() {
        // Set wCompleteness higher than wRetrieval to test bounding
        config.setWRetrieval(5.0);
        config.setWCompleteness(50.0); // should be clamped below 5.0

        Map<String, Object> completeMeta = new HashMap<>();
        completeMeta.put("type", "METHOD");
        completeMeta.put("javadoc", "Some doc");
        completeMeta.put("signature", "public void run()");
        completeMeta.put("relationships", Map.of("calls", List.of("m2")));
        completeMeta.put(RetrievalConstants.METADATA_DENSE_SCORE, 0.1); // lower retrieval score

        Map<String, Object> incompleteMeta = new HashMap<>();
        incompleteMeta.put("type", "METHOD");
        incompleteMeta.put(RetrievalConstants.METADATA_DENSE_SCORE, 0.9); // higher retrieval score

        Document completeDoc = new Document("complete", "code", completeMeta);
        Document highRetrievalDoc = new Document("retrievalWinner", "code", incompleteMeta);

        // High retrieval doc has normalized retrieval score = 1.0 (5.0 points)
        // Complete doc has normalized retrieval score = 0.0 (0.0 points) + completeness bonus (< 5.0 points)
        // High retrieval doc MUST still win because completeness bonus cannot exceed wRetrieval!
        List<Document> result = reranker.rerank(List.of(completeDoc, highRetrievalDoc), QueryIntent.METHOD, null, 2);

        assertEquals("retrievalWinner", result.get(0).getId(),
                "Higher retrieval score must dominate completeness bonus because completeness is bounded below w_retrieval");
    }

    @Test
    @DisplayName("Truncates results to finalTopK")
    void testTruncation() {
        List<Document> docs = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            docs.add(new Document("d" + i, "code", Map.of("type", "METHOD")));
        }

        List<Document> top5 = reranker.rerank(docs, QueryIntent.METHOD, null, 5);
        assertEquals(5, top5.size());

        List<Document> top10 = reranker.rerank(docs, QueryIntent.METHOD, null, 10);
        assertEquals(10, top10.size());
    }
}
