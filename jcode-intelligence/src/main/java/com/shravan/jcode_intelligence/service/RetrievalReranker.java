package com.shravan.jcode_intelligence.service;

import com.shravan.jcode_intelligence.config.IntentRetrievalConfig;
import com.shravan.jcode_intelligence.model.QueryIntent;
import com.shravan.jcode_intelligence.service.reranking.RerankingStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Reranks retrieved documents using intent-aware heuristic scoring with candidate score normalization.
 *
 * <p>Scoring factors (combined additively):
 * <ol>
 *   <li><b>Chunk-type weight</b> — from {@link IntentRetrievalConfig} weight table, scaled ×10</li>
 *   <li><b>Retrieval term</b> — min-max normalized candidate score (preferring rrfScore, then denseScore),
 *       weighted by {@code w_retrieval}. Uses neutral default for unscored candidates; skipped if entirely unscored.</li>
 *   <li><b>Exact symbol match</b> — +100 for elementName/methodName, +80 for className, +90 for fragment,
 *       weighted by {@code w_symbol}.</li>
 *   <li><b>Metadata completeness tie-breaker</b> — bounded strictly below {@code w_retrieval}, weighted by {@code w_completeness}.</li>
 *   <li><b>FIELD demotion</b> — −30 for FIELD chunks in ARCHITECTURE/DESIGN/PACKAGE intents (and zero FIELD in ARCHITECTURE).</li>
 * </ol>
 *
 * <p>Maintains stable ties and deterministic truncation to {@code finalTopK}.
 */
@Component("heuristicRerankingStrategy")
public class RetrievalReranker implements RerankingStrategy {

    private static final Logger log = LoggerFactory.getLogger(RetrievalReranker.class);

    private final IntentRetrievalConfig config;

    public RetrievalReranker(IntentRetrievalConfig config) {
        this.config = config;
    }

    @Override
    public String name() {
        return "heuristic";
    }

    @Override
    public List<Document> rerank(List<Document> documents, String query, QueryIntent intent,
                                  String targetSymbol, int finalTopK) {
        return rerank(documents, intent, targetSymbol, finalTopK);
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
    @Override
    public List<Document> rerank(List<Document> documents, QueryIntent intent,
                                  String targetSymbol, int finalTopK) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }

        Map<String, Double> weights = config.getChunkTypeWeights(intent);

        // Compute min and max retrieval scores for per-set min-max normalization
        Double minScore = null;
        Double maxScore = null;
        boolean hasAnyScoredCandidate = false;

        for (Document doc : documents) {
            Double s = extractRetrievalScore(doc);
            if (s != null) {
                hasAnyScoredCandidate = true;
                if (minScore == null || s < minScore) {
                    minScore = s;
                }
                if (maxScore == null || s > maxScore) {
                    maxScore = s;
                }
            }
        }

        List<ScoredDocument> scored = new ArrayList<>(documents.size());
        for (int i = 0; i < documents.size(); i++) {
            Document doc = documents.get(i);
            if (doc == null) {
                continue;
            }
            Map<String, Object> meta = doc.getMetadata();
            String type = (meta != null) ? String.valueOf(meta.getOrDefault("type", "")) : "";
            if (intent == QueryIntent.ARCHITECTURE && "FIELD".equalsIgnoreCase(type)) {
                continue; // Zero FIELD chunks for ARCHITECTURE!
            }
            double score = calculateScore(doc, intent, targetSymbol, weights, hasAnyScoredCandidate, minScore, maxScore);
            scored.add(new ScoredDocument(doc, score, i));
        }

        // Stable ties: preserve encounter order when scores are equal
        scored.sort((a, b) -> {
            int cmp = Double.compare(b.score, a.score);
            if (cmp != 0) {
                return cmp;
            }
            return Integer.compare(a.originalIndex, b.originalIndex);
        });

        int limit = Math.min(scored.size(), finalTopK);
        List<Document> result = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
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
                                   String targetSymbol, Map<String, Double> weights,
                                   boolean hasAnyScoredCandidate, Double minScore, Double maxScore) {
        Map<String, Object> meta = doc.getMetadata();
        String type = (meta != null) ? String.valueOf(meta.getOrDefault("type", "")) : "";
        String elementName = (meta != null) ? String.valueOf(meta.getOrDefault("elementName", "")) : "";
        String methodName = (meta != null) ? String.valueOf(meta.getOrDefault("methodName", "")) : "";
        String className = (meta != null) ? String.valueOf(meta.getOrDefault("className", "")) : "";

        double score = 0.0;

        // Factor 1: Chunk-type weight (scaled ×10)
        Double typeWeight = weights.getOrDefault(type, 0.5);
        score += typeWeight * 10.0;

        // Factor 2: Retrieval score (min-max normalized, preferring rrfScore then denseScore)
        if (hasAnyScoredCandidate) {
            Double rawScore = extractRetrievalScore(doc);
            double norm;
            if (rawScore != null) {
                if (maxScore != null && minScore != null && maxScore > minScore) {
                    norm = (rawScore - minScore) / (maxScore - minScore);
                } else {
                    norm = 1.0;
                }
            } else {
                norm = config.getNeutralRetrievalScore();
            }
            score += config.getWRetrieval() * norm;
        }

        // Factor 3: Exact symbol match
        if (targetSymbol != null && !targetSymbol.isBlank()) {
            double symbolBonus = 0.0;
            if (targetSymbol.equalsIgnoreCase(elementName) || targetSymbol.equalsIgnoreCase(methodName)) {
                symbolBonus += 100.0;
            }
            if (targetSymbol.equalsIgnoreCase(className)) {
                symbolBonus += 80.0;
            }
            // Partial match for fragments (e.g., "doFragment#0" matches "doFragment")
            if (elementName.startsWith(targetSymbol + "#")) {
                symbolBonus += 90.0;
            }
            score += config.getWSymbol() * symbolBonus;
        }

        // Factor 4: Metadata completeness tie-breaker (kept strictly below w_retrieval)
        double rawCompleteness = 0.0;
        if (meta != null) {
            if (meta.get("javadoc") != null && !String.valueOf(meta.get("javadoc")).isBlank()) {
                rawCompleteness += 5.0;
            }
            if (meta.get("signature") != null && !String.valueOf(meta.get("signature")).isBlank()) {
                rawCompleteness += 5.0;
            }
            if (meta.get("relationships") instanceof Map<?, ?> relMap && !relMap.isEmpty()) {
                rawCompleteness += 3.0;
            }
        }
        double normalizedCompleteness = rawCompleteness / 13.0;
        double maxAllowedCompleteness = Math.max(0.0, config.getWRetrieval() - 0.001);
        double effectiveWCompleteness = Math.min(config.getWCompleteness(), maxAllowedCompleteness);
        score += effectiveWCompleteness * normalizedCompleteness;

        // Factor 5: FIELD demotion for architectural intents
        if ("FIELD".equals(type) &&
                (intent == QueryIntent.ARCHITECTURE || intent == QueryIntent.DESIGN || intent == QueryIntent.PACKAGE)) {
            score -= 30.0;
        }

        // Factor 6: Production preference / test demotion
        if (meta != null) {
            String filePath = String.valueOf(meta.getOrDefault("filePath", "")).toLowerCase(Locale.ROOT).replace('\\', '/');
            if (filePath.contains("/src/test/") || filePath.contains("test_sourcecode") || filePath.contains("-testing/")) {
                score -= 35.0;
            }
        }

        return score;
    }

    /**
     * Extracts retrieval score from a document, preferring rrfScore, then denseScore.
     */
    private Double extractRetrievalScore(Document doc) {
        if (doc == null) return null;
        Map<String, Object> meta = doc.getMetadata();
        if (meta != null) {
            // Prefer rrfScore
            Object rrf = meta.get(RetrievalConstants.METADATA_RRF_SCORE);
            if (rrf instanceof Number n) {
                return n.doubleValue();
            }
            Object rrf2 = meta.get("rrf_score");
            if (rrf2 instanceof Number n2) {
                return n2.doubleValue();
            }
            // Dense score
            Object dense = meta.get(RetrievalConstants.METADATA_DENSE_SCORE);
            if (dense instanceof Number n3) {
                return n3.doubleValue();
            }
            Object dense2 = meta.get("dense_score");
            if (dense2 instanceof Number n4) {
                return n4.doubleValue();
            }
        }
        if (doc.getScore() != null) {
            return doc.getScore();
        }
        if (meta != null) {
            Object s = meta.get("score");
            if (s instanceof Number n5) {
                return n5.doubleValue();
            }
            Object dist = meta.get("distance");
            if (dist instanceof Number n6) {
                return 1.0 - n6.doubleValue();
            }
        }
        return null;
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
                    meta != null ? meta.getOrDefault("type", "?") : "?",
                    meta != null ? meta.getOrDefault("elementName", "?") : "?",
                    meta != null ? meta.getOrDefault("className", "?") : "?"));
        }
        log.debug(sb.toString());
    }

    /**
     * Internal record pairing a document with its computed reranking score and encounter order index.
     */
    private record ScoredDocument(Document document, double score, int originalIndex) {}
}
