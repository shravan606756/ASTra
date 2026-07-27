package com.shravan.jcode_intelligence.service.impl;

import com.shravan.jcode_intelligence.config.IntentRetrievalConfig;
import com.shravan.jcode_intelligence.dto.request.ChatMode;
import com.shravan.jcode_intelligence.model.QueryIntent;
import com.shravan.jcode_intelligence.service.QueryIntentClassifier;
import com.shravan.jcode_intelligence.service.RetrievalReranker;
import com.shravan.jcode_intelligence.service.RetrievalService;
import com.shravan.jcode_intelligence.service.RetrievalStrategySelector;
import com.shravan.jcode_intelligence.service.SymbolExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Intent-aware retrieval service that orchestrates the retrieval pipeline:
 *
 * <pre>
 *   User Query
 *       │
 *       ▼
 *   QueryIntentClassifier  →  QueryIntent
 *       │
 *       ▼
 *   RetrievalStrategySelector  (intent-specific retrieval)
 *       │
 *       ▼
 *   RetrievalReranker  (chunk-type weighted reranking)
 *       │
 *       ▼
 *   Ranked Documents
 * </pre>
 *
 * <p>When {@code ChatMode.AUTO} is used, the service classifies the query intent
 * automatically. Explicit modes (EXPLAIN_CLASS, EXPLAIN_METHOD, etc.) map
 * directly to their corresponding intents, bypassing classification.
 */
@Service
public class RetrievalServiceImpl implements RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalServiceImpl.class);

    private final QueryIntentClassifier intentClassifier;
    private final RetrievalStrategySelector strategySelector;
    private final RetrievalReranker reranker;
    private final SymbolExtractor symbolExtractor;
    private final IntentRetrievalConfig config;

    public RetrievalServiceImpl(QueryIntentClassifier intentClassifier,
                                RetrievalStrategySelector strategySelector,
                                RetrievalReranker reranker,
                                SymbolExtractor symbolExtractor,
                                IntentRetrievalConfig config) {
        this.intentClassifier = intentClassifier;
        this.strategySelector = strategySelector;
        this.reranker = reranker;
        this.symbolExtractor = symbolExtractor;
        this.config = config;
    }

    // ── Primary Entry Point ───────────────────────────────────

    @Override
    public List<Document> retrieve(String query, int topK, String repositoryId, ChatMode mode) {
        long startTime = System.currentTimeMillis();

        // Step 1: Resolve intent
        ChatMode effectiveMode = (mode == null) ? ChatMode.AUTO : mode;
        QueryIntent intent = resolveIntent(query, effectiveMode);

        // Step 2: Execute intent-specific retrieval strategy
        List<Document> rawDocuments = strategySelector.retrieve(query, intent, repositoryId);
        log.info("Strategy '{}' retrieved {} raw document(s) for query: '{}'",
                intent, rawDocuments.size(), query);

        // Step 3: Rerank with intent-aware weights
        String targetSymbol = symbolExtractor.extract(query).orElse(null);
        int finalTopK = resolveFinalTopK(intent, topK);
        List<Document> ranked = reranker.rerank(rawDocuments, intent, targetSymbol, finalTopK);

        long duration = System.currentTimeMillis() - startTime;

        // Step 4: Log retrieval analytics (Phase 10)
        logRetrievalAnalytics(query, intent, rawDocuments, ranked, duration, repositoryId);

        return ranked;
    }

    /**
     * Returns the classified {@link QueryIntent} for a given query and mode.
     * Exposed for use by {@code ChatServiceImpl} to populate debug fields.
     */
    public QueryIntent classifyIntent(String query, ChatMode mode) {
        ChatMode effectiveMode = (mode == null) ? ChatMode.AUTO : mode;
        return resolveIntent(query, effectiveMode);
    }

    // ── Intent Resolution ─────────────────────────────────────

    /**
     * Maps explicit ChatMode to QueryIntent, or classifies automatically for AUTO.
     */
    private QueryIntent resolveIntent(String query, ChatMode mode) {
        return switch (mode) {
            case AUTO -> intentClassifier.classify(query);
            case EXPLAIN_CLASS -> QueryIntent.CLASS;
            case EXPLAIN_METHOD -> QueryIntent.METHOD;
            case ARCHITECTURE -> QueryIntent.ARCHITECTURE;
            case PROJECT_SUMMARY -> QueryIntent.ARCHITECTURE;
            case QUESTION_ANSWER -> QueryIntent.UNKNOWN;
            case WORKFLOW -> QueryIntent.WORKFLOW;
            case RELATIONSHIP -> QueryIntent.RELATIONSHIP;
            case SEARCH -> QueryIntent.SEARCH;
            case DESIGN -> QueryIntent.DESIGN;
        };
    }

    /**
     * Resolves the final Top-K: user-specified value takes precedence,
     * otherwise uses the intent-configured default.
     */
    private int resolveFinalTopK(QueryIntent intent, int userTopK) {
        if (userTopK > 0) {
            return userTopK;
        }
        return config.getFinalTopK(intent);
    }

    // ── Retrieval Analytics (Phase 10) ────────────────────────

    private void logRetrievalAnalytics(String query, QueryIntent intent,
                                        List<Document> rawDocs, List<Document> rankedDocs,
                                        long latencyMs, String repositoryId) {
        Map<String, Integer> chunkTypeCounts = new LinkedHashMap<>();
        for (Document doc : rankedDocs) {
            String type = String.valueOf(doc.getMetadata().getOrDefault("type", "UNKNOWN"));
            chunkTypeCounts.merge(type, 1, Integer::sum);
        }

        log.info("RETRIEVAL_ANALYTICS | query='{}' | intent={} | repository={} " +
                 "| vectorCandidates={} | reranked={} | returned={} " +
                 "| chunkTypes={} | latencyMs={}",
                query, intent, repositoryId,
                rawDocs.size(), rawDocs.size(), rankedDocs.size(),
                chunkTypeCounts, latencyMs);
    }

    /**
     * Returns the chunk type breakdown for the given documents.
     * Used by ChatServiceImpl to populate debug fields.
     */
    public Map<String, Integer> computeChunkTypeCounts(List<Document> documents) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (documents == null) return counts;
        for (Document doc : documents) {
            String type = String.valueOf(doc.getMetadata().getOrDefault("type", "UNKNOWN"));
            counts.merge(type, 1, Integer::sum);
        }
        return counts;
    }

    // ── Convenience Overloads ─────────────────────────────────

    @Override
    public List<Document> retrieve(String query, int topK, String repositoryId) {
        return retrieve(query, topK, repositoryId, ChatMode.AUTO);
    }

    @Override
    public List<Document> retrieve(String query, int topK) {
        return retrieve(query, topK, null);
    }

    @Override
    public List<Document> retrieve(String query) {
        return retrieve(query, 0, null);
    }
}
