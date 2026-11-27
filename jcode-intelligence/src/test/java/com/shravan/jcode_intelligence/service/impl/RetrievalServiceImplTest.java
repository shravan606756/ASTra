package com.shravan.jcode_intelligence.service.impl;

import com.shravan.jcode_intelligence.config.IntentRetrievalConfig;
import com.shravan.jcode_intelligence.config.RetrievalConfig;
import com.shravan.jcode_intelligence.dto.request.ChatMode;
import com.shravan.jcode_intelligence.model.QueryIntent;
import com.shravan.jcode_intelligence.model.RetrievalStrategy;
import com.shravan.jcode_intelligence.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RetrievalServiceImplTest {

    private QueryIntentClassifier intentClassifier;
    private RetrievalStrategySelector strategySelector;
    private RetrievalReranker reranker;
    private SymbolExtractor symbolExtractor;
    private IntentRetrievalConfig intentConfig;
    private RetrievalConfig retrievalConfig;
    private RetrievalServiceImpl retrievalService;

    @BeforeEach
    void setUp() {
        intentClassifier = mock(QueryIntentClassifier.class);
        strategySelector = mock(RetrievalStrategySelector.class);
        reranker = mock(RetrievalReranker.class);
        when(reranker.name()).thenReturn("heuristic");
        symbolExtractor = new SymbolExtractor();
        intentConfig = new IntentRetrievalConfig() {
            @Override public int getFinalTopK(QueryIntent intent) { return 5; }
            @Override public int getRawTopK(QueryIntent intent) { return 10; }
            @Override public double getSimilarityThreshold() { return 0.65; }
        };
        retrievalConfig = new RetrievalConfig();
        retrievalConfig.setStrategy(RetrievalStrategy.HYBRID);

        retrievalService = new RetrievalServiceImpl(
                intentClassifier,
                strategySelector,
                reranker,
                symbolExtractor,
                intentConfig,
                retrievalConfig
        );
    }

    @Test
    @DisplayName("Strategy overload forwards explicit strategy to selector")
    void testStrategyOverloadForwardsExplicitStrategy() {
        Document doc = new Document("d1", "content", Map.of("type", "CLASS"));
        when(strategySelector.retrieve(eq("find service"), eq(QueryIntent.CLASS), eq("repo-1"), eq(RetrievalStrategy.SPARSE)))
                .thenReturn(List.of(doc));
        when(reranker.rerank(anyList(), eq(QueryIntent.CLASS), any(), anyInt()))
                .thenReturn(List.of(doc));

        List<Document> result = retrievalService.retrieve("find service", 5, "repo-1", ChatMode.EXPLAIN_CLASS, RetrievalStrategy.SPARSE);

        assertNotNull(result);
        assertEquals(1, result.size());
        verify(strategySelector).retrieve("find service", QueryIntent.CLASS, "repo-1", RetrievalStrategy.SPARSE);
    }

    @Test
    @DisplayName("Overloads without strategy resolve default configured strategy")
    void testDefaultStrategyResolution() {
        Document doc = new Document("d1", "content", Map.of("type", "CLASS"));
        when(strategySelector.retrieve(eq("find service"), eq(QueryIntent.CLASS), eq("repo-1"), eq(RetrievalStrategy.HYBRID)))
                .thenReturn(List.of(doc));
        when(reranker.rerank(anyList(), eq(QueryIntent.CLASS), any(), anyInt()))
                .thenReturn(List.of(doc));

        // 4-arg overload without strategy
        List<Document> result = retrievalService.retrieve("find service", 5, "repo-1", ChatMode.EXPLAIN_CLASS);

        assertNotNull(result);
        assertEquals(1, result.size());
        verify(strategySelector).retrieve("find service", QueryIntent.CLASS, "repo-1", RetrievalStrategy.HYBRID);
    }

    @Test
    @DisplayName("retrieveWithDiagnostics returns stage diagnostics record with route counts and latency")
    void testRetrieveWithDiagnosticsReturnsStageDiagnostics() {
        Map<String, Object> meta1 = new HashMap<>();
        meta1.put("type", "CLASS");
        meta1.put(RetrievalConstants.METADATA_RETRIEVAL_ROUTES, Set.of(RetrievalConstants.ROUTE_VECTOR));
        Document doc1 = new Document("d1", "class content", meta1);

        Map<String, Object> meta2 = new HashMap<>();
        meta2.put("type", "METHOD");
        meta2.put(RetrievalConstants.METADATA_RETRIEVAL_ROUTES, Set.of(RetrievalConstants.ROUTE_METADATA, RetrievalConstants.ROUTE_PARENT));
        Document doc2 = new Document("d2", "method content", meta2);

        when(strategySelector.retrieve(eq("search query"), eq(QueryIntent.CLASS), eq("repo-1"), eq(RetrievalStrategy.HYBRID)))
                .thenReturn(List.of(doc1, doc2));
        when(reranker.rerank(anyList(), eq(QueryIntent.CLASS), any(), anyInt()))
                .thenReturn(List.of(doc1));

        RetrievalService.RetrievalDiagnostics diag = retrievalService.retrieveWithDiagnostics(
                "search query", 5, "repo-1", ChatMode.EXPLAIN_CLASS, RetrievalStrategy.HYBRID, "heuristic");

        assertNotNull(diag);
        assertEquals(1, diag.documents().size());
        assertEquals("d1", diag.documents().get(0).getId());
        assertEquals(2, diag.rawDocuments().size());
        assertEquals(QueryIntent.CLASS, diag.intent());
        assertEquals(RetrievalStrategy.HYBRID, diag.retrievalStrategy());
        assertEquals("heuristic", diag.rerankingStrategy());
        assertTrue(diag.durationMs() >= 0);

        // Route counts: vector=1, metadata=1, parent=1
        Map<String, Integer> routeCounts = diag.candidateCountsPerRoute();
        assertNotNull(routeCounts);
        assertEquals(1, routeCounts.get(RetrievalConstants.ROUTE_VECTOR));
        assertEquals(1, routeCounts.get(RetrievalConstants.ROUTE_METADATA));
        assertEquals(1, routeCounts.get(RetrievalConstants.ROUTE_PARENT));
    }

    @Test
    @DisplayName("retrieve delegates directly to retrieveWithDiagnostics")
    void testRetrieveDelegatesToRetrieveWithDiagnostics() {
        Document doc = new Document("d1", "content", Map.of("type", "CLASS"));
        when(strategySelector.retrieve(eq("find service"), eq(QueryIntent.CLASS), eq("repo-1"), eq(RetrievalStrategy.HYBRID)))
                .thenReturn(List.of(doc));
        when(reranker.rerank(anyList(), eq(QueryIntent.CLASS), any(), anyInt()))
                .thenReturn(List.of(doc));

        List<Document> docs = retrievalService.retrieve("find service", 5, "repo-1", ChatMode.EXPLAIN_CLASS);
        assertNotNull(docs);
        assertEquals(1, docs.size());
        assertEquals("d1", docs.get(0).getId());
    }

    @Test
    @DisplayName("Throws IllegalArgumentException when unknown reranking strategy name is supplied")
    void testRejectUnknownRerankingStrategy() {
        when(strategySelector.retrieve(any(), any(), any(), any()))
                .thenReturn(List.of(new Document("d1", "content", Map.of())));

        assertThrows(IllegalArgumentException.class, () ->
                retrievalService.retrieveWithDiagnostics("query", 5, "repo-1", ChatMode.AUTO, RetrievalStrategy.HYBRID, "nonexistent_strategy")
        );
    }

    @Test
    @DisplayName("Supports injecting custom RerankingStrategy and executing it by name")
    void testCustomRerankingStrategyInjection() {
        com.shravan.jcode_intelligence.service.reranking.RerankingStrategy customStrategy =
                new com.shravan.jcode_intelligence.service.reranking.RerankingStrategy() {
                    @Override
                    public String name() {
                        return "custom";
                    }

                    @Override
                    public List<Document> rerank(List<Document> documents, QueryIntent intent, String targetSymbol, int finalTopK) {
                        return List.of(new Document("custom-d1", "custom content", Map.of()));
                    }
                };

        RetrievalServiceImpl serviceWithCustom = new RetrievalServiceImpl(
                intentClassifier,
                strategySelector,
                reranker,
                symbolExtractor,
                intentConfig,
                retrievalConfig,
                List.of(customStrategy)
        );

        when(strategySelector.retrieve(any(), any(), any(), any()))
                .thenReturn(List.of(new Document("d1", "content", Map.of())));

        RetrievalService.RetrievalDiagnostics diag = serviceWithCustom.retrieveWithDiagnostics(
                "query", 5, "repo-1", ChatMode.AUTO, RetrievalStrategy.HYBRID, "custom");

        assertNotNull(diag);
        assertEquals("custom", diag.rerankingStrategy());
        assertEquals(1, diag.documents().size());
        assertEquals("custom-d1", diag.documents().get(0).getId());
    }
}
