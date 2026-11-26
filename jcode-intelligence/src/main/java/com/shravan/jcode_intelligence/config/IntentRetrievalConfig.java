package com.shravan.jcode_intelligence.config;

import com.shravan.jcode_intelligence.model.QueryIntent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.util.EnumMap;
import java.util.Map;

/**
 * Centralized configuration for intent-aware retrieval and heuristic reranking.
 *
 * <p>Provides per-intent:
 * <ul>
 *   <li>Chunk-type weights for reranking</li>
 *   <li>Adaptive Top-K values (raw fetch + final return)</li>
 *   <li>Configurable heuristic reranking weights (w_retrieval, w_symbol, w_completeness, neutral score)</li>
 *   <li>Default reranking strategy selection</li>
 * </ul>
 *
 * <p>All values are configurable via {@code application.properties}.
 */
@Configuration
public class IntentRetrievalConfig {

    // ── Heuristic Reranking & Strategy Configuration ─────────

    @Value("${astra.retrieval.rerank.strategy:heuristic}")
    private String rerankingStrategy = "heuristic";

    @Value("${astra.retrieval.rerank.w-retrieval:10.0}")
    private double wRetrieval = 10.0;

    @Value("${astra.retrieval.rerank.w-symbol:1.0}")
    private double wSymbol = 1.0;

    @Value("${astra.retrieval.rerank.w-completeness:1.0}")
    private double wCompleteness = 1.0;

    @Value("${astra.retrieval.rerank.neutral-retrieval-score:0.5}")
    private double neutralRetrievalScore = 0.5;

    // ── Adaptive Top-K per Intent ─────────────────────────────

    @Value("${astra.retrieval.topk.method:5}")
    private int methodTopK;

    @Value("${astra.retrieval.topk.class:8}")
    private int classTopK;

    @Value("${astra.retrieval.topk.package:15}")
    private int packageTopK;

    @Value("${astra.retrieval.topk.architecture:20}")
    private int architectureTopK;

    @Value("${astra.retrieval.topk.workflow:10}")
    private int workflowTopK;

    @Value("${astra.retrieval.topk.relationship:12}")
    private int relationshipTopK;

    @Value("${astra.retrieval.topk.search:5}")
    private int searchTopK;

    @Value("${astra.retrieval.topk.design:15}")
    private int designTopK;

    @Value("${astra.retrieval.topk.unknown:5}")
    private int unknownTopK;

    @Value("${astra.retrieval.raw-fetch-multiplier:2}")
    private int rawFetchMultiplier;

    @Value("${astra.retrieval.similarity-threshold:0.50}")
    private double similarityThreshold = 0.50;

    @Value("${astra.debug.intent:true}")
    private boolean debugIntentEnabled;

    /**
     * Returns the final Top-K for a given intent (how many results to return after reranking).
     */
    public int getFinalTopK(QueryIntent intent) {
        return switch (intent) {
            case METHOD -> methodTopK;
            case CLASS -> classTopK;
            case PACKAGE -> packageTopK;
            case ARCHITECTURE -> architectureTopK;
            case WORKFLOW -> workflowTopK;
            case RELATIONSHIP -> relationshipTopK;
            case SEARCH -> searchTopK;
            case DESIGN -> designTopK;
            case UNKNOWN -> unknownTopK;
        };
    }

    /**
     * Returns the raw Top-K for a given intent (how many candidates to fetch before reranking).
     */
    public int getRawTopK(QueryIntent intent) {
        return getFinalTopK(intent) * rawFetchMultiplier;
    }

    public double getSimilarityThreshold() {
        return similarityThreshold;
    }

    public boolean isDebugIntentEnabled() {
        return debugIntentEnabled;
    }

    public String getRerankingStrategy() {
        return rerankingStrategy;
    }

    public void setRerankingStrategy(String rerankingStrategy) {
        this.rerankingStrategy = rerankingStrategy;
    }

    public double getWRetrieval() {
        return wRetrieval;
    }

    public void setWRetrieval(double wRetrieval) {
        this.wRetrieval = wRetrieval;
    }

    public double getWSymbol() {
        return wSymbol;
    }

    public void setWSymbol(double wSymbol) {
        this.wSymbol = wSymbol;
    }

    public double getWCompleteness() {
        return wCompleteness;
    }

    public void setWCompleteness(double wCompleteness) {
        this.wCompleteness = wCompleteness;
    }

    public double getNeutralRetrievalScore() {
        return neutralRetrievalScore;
    }

    public void setNeutralRetrievalScore(double neutralRetrievalScore) {
        this.neutralRetrievalScore = neutralRetrievalScore;
    }

    // ── Chunk-Type Weights per Intent ─────────────────────────

    /**
     * Returns the chunk-type weight map for a given intent.
     * Keys are chunk type strings (METHOD, CLASS, PACKAGE, FIELD, etc.).
     * Values are weight multipliers used in reranking.
     */
    public Map<String, Double> getChunkTypeWeights(QueryIntent intent) {
        return switch (intent) {
            case METHOD -> buildWeightMap(2.0, 1.2, 0.3, 0.2, 0.8, 0.5, 1.8);
            case CLASS -> buildWeightMap(1.0, 2.0, 0.5, 0.3, 1.2, 0.5, 0.8);
            case PACKAGE -> buildWeightMap(0.3, 1.5, 3.0, 0.0, 1.0, 0.0, 0.0);
            case ARCHITECTURE -> buildWeightMap(0.5, 2.5, 3.0, 0.0, 2.2, 0.2, 0.0);
            case WORKFLOW -> buildWeightMap(1.5, 1.5, 1.2, 0.2, 1.0, 0.5, 1.3);
            case RELATIONSHIP -> buildWeightMap(1.2, 1.8, 0.5, 0.2, 1.5, 0.3, 0.8);
            case SEARCH -> buildWeightMap(1.5, 1.5, 0.5, 0.5, 1.0, 0.5, 1.0);
            case DESIGN -> buildWeightMap(0.5, 1.8, 1.5, 0.0, 1.5, 0.0, 0.0);
            case UNKNOWN -> buildWeightMap(1.0, 1.0, 0.5, 0.3, 0.8, 0.4, 0.8);
        };
    }

    /**
     * Builds a weight map from ordered values.
     * Order: METHOD, CLASS, PACKAGE, FIELD, INTERFACE, CONSTRUCTOR, METHOD_FRAGMENT
     */
    private Map<String, Double> buildWeightMap(double method, double clazz, double pkg,
                                                double field, double iface,
                                                double constructor, double fragment) {
        Map<String, Double> weights = new java.util.HashMap<>();
        weights.put("METHOD", method);
        weights.put("CLASS", clazz);
        weights.put("PACKAGE", pkg);
        weights.put("FIELD", field);
        weights.put("INTERFACE", iface);
        weights.put("CONSTRUCTOR", constructor);
        weights.put("METHOD_FRAGMENT", fragment);
        weights.put("ENUM", clazz);    // Treat ENUM same as CLASS
        weights.put("RECORD", clazz);  // Treat RECORD same as CLASS
        return weights;
    }

    @org.springframework.context.annotation.Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
    public com.fasterxml.jackson.databind.ObjectMapper objectMapper() {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        mapper.findAndRegisterModules();
        return mapper;
    }
}
