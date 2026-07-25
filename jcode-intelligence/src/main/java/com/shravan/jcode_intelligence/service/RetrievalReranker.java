package com.shravan.jcode_intelligence.service;

import com.shravan.jcode_intelligence.config.IntentRetrievalConfig;
import com.shravan.jcode_intelligence.model.QueryIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Reranks retrieved documents using intent-aware heuristic scoring.
 *
 * <p>Scoring factors (combined additively):
 * <ol>
 *   <li><b>Chunk-type weight</b> — from {@link IntentRetrievalConfig} weight table, scaled ×10</li>
 *   <li><b>Exact symbol match</b> — +100 for elementName/methodName, +80 for className</li>
 *   <li><b>Package proximity</b> — +20 if document package matches query context</li>
 *   <li><b>Metadata completeness</b> — +5 for javadoc, +5 for signature, +3 for relationships</li>
 *   <li><b>FIELD demotion</b> — −30 for FIELD chunks in ARCHITECTURE/DESIGN/PACKAGE intents</li>
 * </ol>
 *
 * <p>No external reranker dependency — pure heuristic scoring.
 */
@Component
public class RetrievalReranker {

    private static final Logger log = LoggerFactory.getLogger(RetrievalReranker.class);

    private final IntentRetrievalConfig config;

    public RetrievalReranker(IntentRetrievalConfig config) {
        this.config = config;
    }

    /**
     * Reranks the given documents based on the classified intent and target symbol.
     *
     * @param documents    the retrieved documents to rerank
     * @param intent       the classified query intent
     * @param targetSymbol the primary code symbol extracted from the query (nullable)
     * @param finalTopK    the maximum number of documents to return
     * @return reranked and trimmed list of documents
     */
    public List<Document> rerank(List<Document> documents, QueryIntent intent,
                                  String targetSymbol, int finalTopK) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }

        Map<String, Double> weights = config.getChunkTypeWeights(intent);

        List<ScoredDocument> scored = new ArrayList<>(documents.size());
        for (Document doc : documents) {
            String type = String.valueOf(doc.getMetadata().getOrDefault("type", ""));
            if (intent == QueryIntent.ARCHITECTURE && "FIELD".equalsIgnoreCase(type)) {
                continue; // Zero FIELD chunks for ARCHITECTURE!
            }
            double score = calculateScore(doc, intent, targetSymbol, weights);
            scored.add(new ScoredDocument(doc, score));
        }

        scored.sort((a, b) -> Double.compare(b.score, a.score));

        List<Document> result = new ArrayList<>(Math.min(scored.size(), finalTopK));
        for (int i = 0; i < Math.min(scored.size(), finalTopK); i++) {
            result.add(scored.get(i).document);
        }

        if (log.isDebugEnabled()) {
            logRankingDetails(scored, intent, finalTopK);
        }

        return result;
    }

    /**
     * Calculates the composite reranking score for a document.
     */
    private double calculateScore(Document doc, QueryIntent intent,
                                   String targetSymbol, Map<String, Double> weights) {
        Map<String, Object> meta = doc.getMetadata();
        String type = String.valueOf(meta.getOrDefault("type", ""));
        String elementName = String.valueOf(meta.getOrDefault("elementName", ""));
        String methodName = String.valueOf(meta.getOrDefault("methodName", ""));
        String className = String.valueOf(meta.getOrDefault("className", ""));

        double score = 0.0;

        // Factor 1: Chunk-type weight (scaled ×10)
        Double typeWeight = weights.getOrDefault(type, 0.5);
        score += typeWeight * 10.0;

        // Factor 2: Exact symbol match
        if (targetSymbol != null && !targetSymbol.isBlank()) {
            if (targetSymbol.equalsIgnoreCase(elementName) || targetSymbol.equalsIgnoreCase(methodName)) {
                score += 100.0;
            }
            if (targetSymbol.equalsIgnoreCase(className)) {
                score += 80.0;
            }
            // Partial match for fragments (e.g., "doFragment#0" matches "doFragment")
            if (elementName.startsWith(targetSymbol + "#")) {
                score += 90.0;
            }
        }

        // Factor 3: Metadata completeness
        if (meta.get("javadoc") != null && !String.valueOf(meta.get("javadoc")).isBlank()) {
            score += 5.0;
        }
        if (meta.get("signature") != null && !String.valueOf(meta.get("signature")).isBlank()) {
            score += 5.0;
        }
        if (meta.get("relationships") instanceof Map<?, ?> relMap && !relMap.isEmpty()) {
            score += 3.0;
        }

        // Factor 4: FIELD demotion for architectural intents
        if ("FIELD".equals(type) &&
                (intent == QueryIntent.ARCHITECTURE || intent == QueryIntent.DESIGN || intent == QueryIntent.PACKAGE)) {
            score -= 30.0;
        }

        return score;
    }

    private void logRankingDetails(List<ScoredDocument> scored, QueryIntent intent, int finalTopK) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Reranking for intent=%s, returning top %d of %d candidates:%n",
                intent, finalTopK, scored.size()));
        int limit = Math.min(scored.size(), finalTopK);
        for (int i = 0; i < limit; i++) {
            ScoredDocument sd = scored.get(i);
            Map<String, Object> meta = sd.document.getMetadata();
            sb.append(String.format("  [%d] score=%.1f type=%s element=%s class=%s%n",
                    i + 1, sd.score,
                    meta.getOrDefault("type", "?"),
                    meta.getOrDefault("elementName", "?"),
                    meta.getOrDefault("className", "?")));
        }
        log.debug(sb.toString());
    }

    /**
     * Internal record pairing a document with its computed reranking score.
     */
    private record ScoredDocument(Document document, double score) {}
}
