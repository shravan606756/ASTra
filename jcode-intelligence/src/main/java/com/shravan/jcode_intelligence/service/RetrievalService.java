package com.shravan.jcode_intelligence.service;

import com.shravan.jcode_intelligence.dto.request.ChatMode;
import com.shravan.jcode_intelligence.model.QueryIntent;
import com.shravan.jcode_intelligence.model.RetrievalStrategy;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

/**
 * Intent-aware retrieval service interface supporting strategy selection and stage diagnostics.
 */
public interface RetrievalService {

    /**
     * Result record encapsulating retrieved documents and stage diagnostics.
     *
     * @param documents                   final reranked and truncated documents
     * @param rawDocuments               raw candidate documents assembled before reranking
     * @param candidateCountsPerRoute    breakdown of raw candidate counts by retrieval route (vector, metadata, parent)
     * @param intent                      classified query intent
     * @param retrievalStrategy           resolved candidate retrieval strategy (DENSE, SPARSE, HYBRID)
     * @param rerankingStrategy           resolved reranking strategy name (e.g. "heuristic", "cross-encoder")
     * @param durationMs                  total retrieval latency in milliseconds
     * @param candidateRetrievalLatencyMs latency in milliseconds spent retrieving raw candidates
     * @param rerankingLatencyMs          latency in milliseconds spent reranking candidates
     */
    record RetrievalDiagnostics(
            List<Document> documents,
            List<Document> rawDocuments,
            Map<String, Integer> candidateCountsPerRoute,
            QueryIntent intent,
            RetrievalStrategy retrievalStrategy,
            String rerankingStrategy,
            long durationMs,
            long candidateRetrievalLatencyMs,
            long rerankingLatencyMs
    ) {
        public RetrievalDiagnostics(
                List<Document> documents,
                List<Document> rawDocuments,
                Map<String, Integer> candidateCountsPerRoute,
                QueryIntent intent,
                RetrievalStrategy retrievalStrategy,
                String rerankingStrategy,
                long durationMs
        ) {
            this(documents, rawDocuments, candidateCountsPerRoute, intent, retrievalStrategy, rerankingStrategy, durationMs, durationMs, 0L);
        }

        public List<Document> getDocuments() { return documents; }
        public List<Document> getRawDocuments() { return rawDocuments; }
        public Map<String, Integer> getCandidateCountsPerRoute() { return candidateCountsPerRoute; }
        public QueryIntent getIntent() { return intent; }
        public RetrievalStrategy getRetrievalStrategy() { return retrievalStrategy; }
        public String getRerankingStrategy() { return rerankingStrategy; }
        public long getDurationMs() { return durationMs; }
        public long getCandidateRetrievalLatencyMs() { return candidateRetrievalLatencyMs; }
        public long getRerankingLatencyMs() { return rerankingLatencyMs; }
        public long latencyMs() { return durationMs; }
        public Map<String, Integer> routeCounts() { return candidateCountsPerRoute; }
        public List<Document> rerankedDocuments() { return documents; }
    }

    /**
     * Full diagnostic retrieval entry point.
     */
    RetrievalDiagnostics retrieveWithDiagnostics(String query, int topK, String repositoryId, ChatMode mode, RetrievalStrategy strategy, String rerankingStrategy);

    default RetrievalDiagnostics retrieveWithDiagnostics(String query, int topK, String repositoryId, ChatMode mode, RetrievalStrategy strategy) {
        return retrieveWithDiagnostics(query, topK, repositoryId, mode, strategy, null);
    }

    default RetrievalDiagnostics retrieveWithDiagnostics(String query, int topK, String repositoryId, ChatMode mode) {
        return retrieveWithDiagnostics(query, topK, repositoryId, mode, null, null);
    }

    default RetrievalDiagnostics retrieveWithDiagnostics(String query, int topK, String repositoryId) {
        return retrieveWithDiagnostics(query, topK, repositoryId, ChatMode.AUTO, null, null);
    }

    default RetrievalDiagnostics retrieveWithDiagnostics(String query, int topK) {
        return retrieveWithDiagnostics(query, topK, null, ChatMode.AUTO, null, null);
    }

    default RetrievalDiagnostics retrieveWithDiagnostics(String query) {
        return retrieveWithDiagnostics(query, 0, null, ChatMode.AUTO, null, null);
    }

    default List<Document> retrieve(String query, int topK, String repositoryId, ChatMode mode, RetrievalStrategy strategy) {
        return retrieveWithDiagnostics(query, topK, repositoryId, mode, strategy, null).documents();
    }

    default List<Document> retrieve(String query, int topK, String repositoryId, ChatMode mode) {
        return retrieve(query, topK, repositoryId, mode, null);
    }

    default List<Document> retrieve(String query, int topK, String repositoryId) {
        return retrieve(query, topK, repositoryId, ChatMode.AUTO);
    }

    default List<Document> retrieve(String query, int topK) {
        return retrieve(query, topK, null);
    }

    default List<Document> retrieve(String query) {
        return retrieve(query, 0, null);
    }
}
