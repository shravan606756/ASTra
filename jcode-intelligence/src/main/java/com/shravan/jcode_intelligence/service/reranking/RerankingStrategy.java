package com.shravan.jcode_intelligence.service.reranking;

import com.shravan.jcode_intelligence.model.QueryIntent;
import org.springframework.ai.document.Document;

import java.util.List;

/**
 * Strategy interface for reranking retrieved candidate documents.
 */
public interface RerankingStrategy {

    /**
     * Returns the unique identifier of this reranking strategy (e.g. "heuristic").
     */
    String name();

    /**
     * Reranks candidate documents using intent, extracted target symbol, and final Top-K limit.
     *
     * @param documents    retrieved candidate documents to rerank
     * @param intent       classified query intent
     * @param targetSymbol primary code symbol extracted from the query (nullable)
     * @param finalTopK    maximum number of documents to return after reranking
     * @return reranked and truncated list of documents
     */
    List<Document> rerank(List<Document> documents, QueryIntent intent, String targetSymbol, int finalTopK);

    /**
     * Default convenience overload accepting a query string instead of pre-extracted symbol.
     *
     * @param documents candidate documents
     * @param query     user query string
     * @param intent    classified query intent
     * @param finalTopK maximum number of documents to return
     * @return reranked documents
     */
    default List<Document> rerank(List<Document> documents, String query, QueryIntent intent, int finalTopK) {
        return rerank(documents, query, intent, null, finalTopK);
    }

    /**
     * Full overload accepting query, intent, target symbol, and final Top-K limit.
     * Default implementation delegates to {@link #rerank(List, QueryIntent, String, int)}.
     *
     * @param documents    retrieved candidate documents to rerank
     * @param query        raw user query string
     * @param intent       classified query intent
     * @param targetSymbol primary code symbol extracted from the query (nullable)
     * @param finalTopK    maximum number of documents to return after reranking
     * @return reranked and truncated list of documents
     */
    default List<Document> rerank(List<Document> documents, String query, QueryIntent intent, String targetSymbol, int finalTopK) {
        return rerank(documents, intent, targetSymbol, finalTopK);
    }
}
