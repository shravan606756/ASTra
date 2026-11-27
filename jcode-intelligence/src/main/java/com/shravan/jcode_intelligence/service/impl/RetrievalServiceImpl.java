package com.shravan.jcode_intelligence.service.impl;

import com.shravan.jcode_intelligence.config.IntentRetrievalConfig;
import com.shravan.jcode_intelligence.config.RetrievalConfig;
import com.shravan.jcode_intelligence.dto.request.ChatMode;
import com.shravan.jcode_intelligence.model.QueryIntent;
import com.shravan.jcode_intelligence.model.RetrievalStrategy;
import com.shravan.jcode_intelligence.service.QueryIntentClassifier;
import com.shravan.jcode_intelligence.service.RetrievalConstants;
import com.shravan.jcode_intelligence.service.RetrievalReranker;
import com.shravan.jcode_intelligence.service.RetrievalService;
import com.shravan.jcode_intelligence.service.RetrievalStrategySelector;
import com.shravan.jcode_intelligence.service.SymbolExtractor;
import com.shravan.jcode_intelligence.service.reranking.CrossEncoderReranker;
import com.shravan.jcode_intelligence.service.reranking.RerankingStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
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
 *   RetrievalStrategySelector  (intent-specific candidate retrieval & route tagging)
 *       │
 *       ▼
 *   RerankingStrategy (heuristic scoring with normalized retrieval & completeness terms)
 *       │
 *       ▼
 *   Ranked Documents & Stage Diagnostics
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
    private final RetrievalConfig retrievalConfig;
    private final Map<String, RerankingStrategy> rerankingStrategies;

    public RetrievalServiceImpl(QueryIntentClassifier intentClassifier,
                                RetrievalStrategySelector strategySelector,
                                RetrievalReranker reranker,
                                SymbolExtractor symbolExtractor,
                                IntentRetrievalConfig config) {
        this(intentClassifier, strategySelector, reranker, symbolExtractor, config, null, null);
    }

    public RetrievalServiceImpl(QueryIntentClassifier intentClassifier,
                                RetrievalStrategySelector strategySelector,
                                RetrievalReranker reranker,
                                SymbolExtractor symbolExtractor,
                                IntentRetrievalConfig config,
                                RetrievalConfig retrievalConfig) {
        this(intentClassifier, strategySelector, reranker, symbolExtractor, config, retrievalConfig, null);
    }

    @Autowired
    public RetrievalServiceImpl(QueryIntentClassifier intentClassifier,
                                RetrievalStrategySelector strategySelector,
                                RetrievalReranker reranker,
                                SymbolExtractor symbolExtractor,
                                IntentRetrievalConfig config,
                                @Autowired(required = false) RetrievalConfig retrievalConfig,
                                @Autowired(required = false) List<RerankingStrategy> strategies) {
        this.intentClassifier = intentClassifier;
        this.strategySelector = strategySelector;
        this.reranker = reranker;
        this.symbolExtractor = symbolExtractor;
        this.config = config;
        this.retrievalConfig = (retrievalConfig != null) ? retrievalConfig : new RetrievalConfig();
        this.rerankingStrategies = new LinkedHashMap<>();

        if (strategies != null) {
            for (RerankingStrategy s : strategies) {
                if (s != null && s.name() != null) {
                    this.rerankingStrategies.put(s.name().toLowerCase(Locale.ROOT), s);
                    this.rerankingStrategies.put(s.name().toLowerCase(Locale.ROOT).replace('_', '-'), s);
                }
            }
        }
        if (reranker != null) {
            String name = null;
            try {
                name = reranker.name();
            } catch (Exception ignored) {}
            if (name == null || name.isBlank()) {
                name = "heuristic";
            }
            this.rerankingStrategies.putIfAbsent(name.toLowerCase(Locale.ROOT), reranker);
            this.rerankingStrategies.putIfAbsent(name.toLowerCase(Locale.ROOT).replace('_', '-'), reranker);
        }
    }

    // ── Primary Entry Points ──────────────────────────────────

    @Override
    public RetrievalDiagnostics retrieveWithDiagnostics(String query, int topK, String repositoryId,
                                                        ChatMode mode, RetrievalStrategy strategy,
                                                        String rerankingStrategyName) {
        long startTime = System.currentTimeMillis();

        // Step 1: Resolve intent
        ChatMode effectiveMode = (mode == null) ? ChatMode.AUTO : mode;
        QueryIntent intent = resolveIntent(query, effectiveMode);

        // Step 1.5: Resolve effective retrieval strategy
        RetrievalStrategy effectiveStrategy = (strategy != null) ? strategy
                : (retrievalConfig != null ? retrievalConfig.getStrategy() : RetrievalStrategy.DENSE);

        // Step 1.6: Resolve reranking strategy (rejecting unknown names)
        RerankingStrategy selectedReranker = (config != null || (rerankingStrategies != null && !rerankingStrategies.isEmpty()))
                ? resolveReranker(rerankingStrategyName)
                : reranker;

        // Step 2: Execute intent-specific retrieval strategy
        long candidateStartTime = System.currentTimeMillis();
        List<Document> rawDocuments;
        if (strategySelector != null) {
            rawDocuments = strategySelector.retrieve(query, intent, repositoryId, effectiveStrategy);
            log.info("Strategy '{}' with engine strategy '{}' retrieved {} raw document(s) for query: '{}'",
                    intent, effectiveStrategy, rawDocuments.size(), query);
        } else {
            rawDocuments = retrieve(query, topK, repositoryId, mode, strategy);
        }
        long candidateDuration = System.currentTimeMillis() - candidateStartTime;

        // Compute candidate counts per retrieval route
        Map<String, Integer> routeCounts = computeRouteCounts(rawDocuments);

        // Step 3: Rerank with intent-aware weights using selected reranking strategy
        String targetSymbol = (symbolExtractor != null) ? symbolExtractor.extract(query).orElse(null) : null;
        int finalTopK = resolveFinalTopK(intent, topK);
        long rerankStartTime = System.currentTimeMillis();
        List<Document> ranked;
        if (selectedReranker instanceof CrossEncoderReranker ce) {
            ranked = ce.rerank(rawDocuments, query, intent, targetSymbol, finalTopK);
        } else if (selectedReranker != null) {
            ranked = selectedReranker.rerank(rawDocuments, intent, targetSymbol, finalTopK);
        } else {
            ranked = rawDocuments;
        }
        long rerankDuration = System.currentTimeMillis() - rerankStartTime;

        long duration = System.currentTimeMillis() - startTime;

        String strategyResultName = (selectedReranker != null) ? selectedReranker.name() : "heuristic";
        if (strategyResultName == null || strategyResultName.isBlank()) {
            strategyResultName = (rerankingStrategyName != null && !rerankingStrategyName.isBlank())
                    ? rerankingStrategyName : "heuristic";
        }

        // Step 4: Log retrieval analytics (Phase 10)
        logRetrievalAnalytics(query, intent, rawDocuments, ranked, duration, repositoryId, routeCounts, strategyResultName);

        return new RetrievalDiagnostics(ranked, rawDocuments, routeCounts, intent, effectiveStrategy, strategyResultName, duration, candidateDuration, rerankDuration);
    }

    @Override
    public List<Document> retrieve(String query, int topK, String repositoryId, ChatMode mode, RetrievalStrategy strategy) {
        return retrieveWithDiagnostics(query, topK, repositoryId, mode, strategy, null).documents();
    }

    @Override
    public List<Document> retrieve(String query, int topK, String repositoryId, ChatMode mode) {
        return retrieve(query, topK, repositoryId, mode, null);
    }

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

    /**
     * Resolves the reranking strategy instance by name, falling back to configured default.
     * Throws {@link IllegalArgumentException} if an unrecognized strategy name is supplied.
     */
    private RerankingStrategy resolveReranker(String strategyName) {
        String name = (strategyName != null && !strategyName.isBlank())
                ? strategyName.trim()
                : (config != null ? config.getRerankingStrategy() : "heuristic");

        if (name == null || name.isBlank()) {
            name = "heuristic";
        }

        if (rerankingStrategies == null || rerankingStrategies.isEmpty()) {
            return reranker;
        }

        RerankingStrategy strategy = rerankingStrategies.get(name.toLowerCase(Locale.ROOT));
        if (strategy == null) {
            strategy = rerankingStrategies.get(name.toLowerCase(Locale.ROOT).replace('_', '-'));
        }
        if (strategy == null) {
            throw new IllegalArgumentException("Unknown reranking strategy: " + name);
        }
        return strategy;
    }

    /**
     * Computes candidate counts partitioned by retrieval route from document metadata.
     */
    private Map<String, Integer> computeRouteCounts(List<Document> documents) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (documents == null || documents.isEmpty()) {
            return counts;
        }

        for (Document doc : documents) {
            if (doc == null || doc.getMetadata() == null) {
                counts.merge("unknown", 1, Integer::sum);
                continue;
            }
            Object routesObj = doc.getMetadata().get(RetrievalConstants.METADATA_RETRIEVAL_ROUTES);
            if (routesObj instanceof Collection<?> coll && !coll.isEmpty()) {
                for (Object item : coll) {
                    if (item != null) {
                        counts.merge(item.toString(), 1, Integer::sum);
                    }
                }
            } else if (routesObj instanceof String str && !str.isBlank()) {
                counts.merge(str, 1, Integer::sum);
            } else {
                counts.merge("unknown", 1, Integer::sum);
            }
        }
        return counts;
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
            case AUTO -> (intentClassifier != null) ? intentClassifier.classify(query) : QueryIntent.UNKNOWN;
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
        return config != null ? config.getFinalTopK(intent) : 5;
    }

    // ── Retrieval Analytics (Phase 10) ────────────────────────

    private void logRetrievalAnalytics(String query, QueryIntent intent,
                                        List<Document> rawDocs, List<Document> rankedDocs,
                                        long latencyMs, String repositoryId,
                                        Map<String, Integer> routeCounts, String rerankingStrategy) {
        Map<String, Integer> chunkTypeCounts = new LinkedHashMap<>();
        for (Document doc : rankedDocs) {
            if (doc != null && doc.getMetadata() != null) {
                String type = String.valueOf(doc.getMetadata().getOrDefault("type", "UNKNOWN"));
                chunkTypeCounts.merge(type, 1, Integer::sum);
            }
        }

        log.info("RETRIEVAL_ANALYTICS | query='{}' | intent={} | repository={} " +
                 "| rawCandidates={} | routes={} | rerankingStrategy={} | returned={} " +
                 "| chunkTypes={} | latencyMs={}",
                query, intent, repositoryId,
                rawDocs.size(), routeCounts, rerankingStrategy, rankedDocs.size(),
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
            if (doc != null && doc.getMetadata() != null) {
                String type = String.valueOf(doc.getMetadata().getOrDefault("type", "UNKNOWN"));
                counts.merge(type, 1, Integer::sum);
            }
        }
        return counts;
    }
}
