package com.shravan.jcode_intelligence.sparse.fusion;

import com.shravan.jcode_intelligence.config.RetrievalConfig;
import com.shravan.jcode_intelligence.model.QueryIntent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ReciprocalRankFusionTest {

    @Test
    @DisplayName("One-based ranks compute scores according to reciprocal formula")
    void testOneBasedRanksAndScoringFormula() {
        RetrievalConfig config = new RetrievalConfig();
        config.setRrfK(60);
        config.setDefaultDenseWeight(1.0);
        config.setDefaultSparseWeight(1.0);

        ReciprocalRankFusion rrf = new ReciprocalRankFusion(config);

        Document doc1 = new Document("d1", "content 1", Map.of("type", "CLASS"));
        Document doc2 = new Document("d2", "content 2", Map.of("type", "METHOD"));

        List<Document> fused = rrf.fuse(List.of(doc1, doc2), Collections.emptyList(), 1.0, 1.0, 10);

        assertEquals(2, fused.size());
        assertEquals("d1", fused.get(0).getId());
        assertEquals("d2", fused.get(1).getId());

        // 1-based rank: rank 1 => 1.0 / (60 + 1) = 1 / 61
        // rank 2 => 1.0 / (60 + 2) = 1 / 62
        double expectedScore1 = 1.0 / 61.0;
        double expectedScore2 = 1.0 / 62.0;

        assertEquals(expectedScore1, (Double) fused.get(0).getMetadata().get("rrf_score"), 1e-6);
        assertEquals(expectedScore2, (Double) fused.get(1).getMetadata().get("rrf_score"), 1e-6);
    }

    @Test
    @DisplayName("Configured k parameter changes score computation appropriately")
    void testConfiguredK() {
        RetrievalConfig config = new RetrievalConfig();
        config.setRrfK(10);
        config.setDefaultDenseWeight(1.0);
        config.setDefaultSparseWeight(1.0);

        ReciprocalRankFusion rrf = new ReciprocalRankFusion(config);

        Document doc = new Document("d1", "content", Map.of());
        List<Document> fused = rrf.fuse(List.of(doc), Collections.emptyList(), 10);

        // With k=10 and defaultDenseWeight=1.0, rank 1 score = 1.0 / (10 + 1) = 1/11
        double expected = 1.0 / 11.0;
        assertEquals(expected, (Double) fused.get(0).getMetadata().get("rrf_score"), 1e-6);
    }

    @Test
    @DisplayName("Per-intent weights correctly scale dense and sparse components")
    void testPerIntentWeights() {
        RetrievalConfig config = new RetrievalConfig();
        config.setRrfK(60);
        config.setDenseWeight(QueryIntent.SEARCH, 0.3);
        config.setSparseWeight(QueryIntent.SEARCH, 0.7);

        ReciprocalRankFusion rrf = new ReciprocalRankFusion(config);

        // Document in dense only (rank 1)
        Document denseOnly = new Document("dense-only", "dense", Map.of());
        // Document in sparse only (rank 1)
        Document sparseOnly = new Document("sparse-only", "sparse", Map.of());

        List<Document> fused = rrf.fuse(List.of(denseOnly), List.of(sparseOnly), QueryIntent.SEARCH, 10);

        assertEquals(2, fused.size());
        // sparse weight 0.7 / 61 > dense weight 0.3 / 61 -> sparse-only should be first!
        assertEquals("sparse-only", fused.get(0).getId());
        assertEquals("dense-only", fused.get(1).getId());

        double expectedSparseScore = 0.7 / 61.0;
        double expectedDenseScore = 0.3 / 61.0;

        assertEquals(expectedSparseScore, (Double) fused.get(0).getMetadata().get("rrf_score"), 1e-6);
        assertEquals(expectedDenseScore, (Double) fused.get(1).getMetadata().get("rrf_score"), 1e-6);
    }

    @Test
    @DisplayName("Canonical dense document data is preserved when document appears in both lists")
    void testCanonicalDenseDataPreserved() {
        RetrievalConfig config = new RetrievalConfig();
        ReciprocalRankFusion rrf = new ReciprocalRankFusion(config);

        Map<String, Object> denseMeta = new HashMap<>();
        denseMeta.put("origin", "dense-engine");
        denseMeta.put("className", "UserService");
        Document denseDoc = new Document("doc-1", "Canonical dense content", denseMeta);

        Map<String, Object> sparseMeta = new HashMap<>();
        sparseMeta.put("origin", "sparse-engine");
        sparseMeta.put("className", "SparseUserService");
        Document sparseDoc = new Document("doc-1", "Sparse text content", sparseMeta);

        List<Document> fused = rrf.fuse(List.of(denseDoc), List.of(sparseDoc), 10);

        assertEquals(1, fused.size());
        Document resultDoc = fused.get(0);
        assertEquals("doc-1", resultDoc.getId());
        assertEquals("Canonical dense content", resultDoc.getText(),
                "Dense content must be preserved as canonical");
        assertEquals("dense-engine", resultDoc.getMetadata().get("origin"),
                "Dense metadata must be preserved as canonical");
        assertEquals("UserService", resultDoc.getMetadata().get("className"));

        assertNotNull(resultDoc.getMetadata().get("rrf_score"));
        assertEquals(1, resultDoc.getMetadata().get("dense_rank"));
        assertEquals(1, resultDoc.getMetadata().get("sparse_rank"));
    }

    @Test
    @DisplayName("Deterministic tie-breaking sorts identical scores by ID ascending")
    void testDeterministicTieBreaking() {
        RetrievalConfig config = new RetrievalConfig();
        config.setRrfK(60);
        ReciprocalRankFusion rrf = new ReciprocalRankFusion(config);

        // doc-B and doc-A both appear only in dense list at rank 1, but we pass them as separate identical-scoring items
        // or dense has doc-B at rank 1, sparse has doc-A at rank 1 (equal weights 0.5)
        Document docB = new Document("doc-B", "content B", Map.of());
        Document docA = new Document("doc-A", "content A", Map.of());

        // Dense has docB at rank 1 (score = 0.5 / 61)
        // Sparse has docA at rank 1 (score = 0.5 / 61)
        List<Document> fused = rrf.fuse(List.of(docB), List.of(docA), 0.5, 0.5, 10);

        assertEquals(2, fused.size());
        assertEquals((Double) fused.get(0).getMetadata().get("rrf_score"),
                (Double) fused.get(1).getMetadata().get("rrf_score"), 1e-9, "Scores must be identical");

        // doc-A should come before doc-B alphabetically
        assertEquals("doc-A", fused.get(0).getId(), "Tie must break by ID ascending");
        assertEquals("doc-B", fused.get(1).getId());
    }

    @Test
    @DisplayName("Raw Top-K limits truncate result list to specified limit")
    void testRawTopKLimits() {
        RetrievalConfig config = new RetrievalConfig();
        ReciprocalRankFusion rrf = new ReciprocalRankFusion(config);

        List<Document> denseDocs = List.of(
                new Document("d1", "c1", Map.of()),
                new Document("d2", "c2", Map.of()),
                new Document("d3", "c3", Map.of()),
                new Document("d4", "c4", Map.of()),
                new Document("d5", "c5", Map.of())
        );

        List<Document> fused = rrf.fuse(denseDocs, Collections.emptyList(), 2);
        assertEquals(2, fused.size());
        assertEquals("d1", fused.get(0).getId());
        assertEquals("d2", fused.get(1).getId());
    }

    @Test
    @DisplayName("Equal-weight list-depth condition guarantees intersection documents outrank single-list documents")
    void testEqualWeightListDepthCondition() {
        // Mathematical theorem: With equal weights w and k = 60:
        // Any document present in both lists at rank r <= 61 outscores ANY single-list document at rank 1!
        RetrievalConfig config = new RetrievalConfig();
        config.setRrfK(60);
        config.setDefaultDenseWeight(1.0);
        config.setDefaultSparseWeight(1.0);
        ReciprocalRankFusion rrf = new ReciprocalRankFusion(config);

        // Doc Both is at rank 60 in dense and rank 60 in sparse
        // Score = 1.0 / (60 + 60) + 1.0 / (60 + 60) = 2.0 / 120 = 1 / 60 ≈ 0.016666...
        // Doc Single-Top is at rank 1 in dense only
        // Score = 1.0 / (60 + 1) = 1 / 61 ≈ 0.016393...

        List<Document> denseList = new java.util.ArrayList<>();
        for (int i = 1; i < 60; i++) {
            denseList.add(new Document("other-dense-" + i, "c", Map.of()));
        }
        Document docBoth = new Document("doc-both", "in both lists", Map.of());
        denseList.add(docBoth); // rank 60 in dense

        List<Document> sparseList = new java.util.ArrayList<>();
        for (int i = 1; i < 60; i++) {
            sparseList.add(new Document("other-sparse-" + i, "c", Map.of()));
        }
        sparseList.add(docBoth); // rank 60 in sparse

        Document singleTop = denseList.get(0); // rank 1 in dense only

        List<Document> fused = rrf.fuse(denseList, sparseList, 1.0, 1.0, 150);

        double bothScore = (Double) fused.stream()
                .filter(d -> d.getId().equals("doc-both")).findFirst().get().getMetadata().get("rrf_score");
        double singleTopScore = (Double) fused.stream()
                .filter(d -> d.getId().equals(singleTop.getId())).findFirst().get().getMetadata().get("rrf_score");

        assertTrue(bothScore > singleTopScore,
                String.format("Intersection document at (60, 60) [score=%.6f] must strictly beat exclusive top-1 document [score=%.6f]",
                        bothScore, singleTopScore));
    }

    @Test
    @DisplayName("Empty candidate lists produce empty result")
    void testEmptyLists() {
        RetrievalConfig config = new RetrievalConfig();
        ReciprocalRankFusion rrf = new ReciprocalRankFusion(config);

        List<Document> fused1 = rrf.fuse(null, null, 10);
        assertTrue(fused1.isEmpty());

        List<Document> fused2 = rrf.fuse(Collections.emptyList(), Collections.emptyList(), 10);
        assertTrue(fused2.isEmpty());
    }
}
