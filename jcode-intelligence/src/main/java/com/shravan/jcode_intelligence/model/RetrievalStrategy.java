package com.shravan.jcode_intelligence.model;

/**
 * Strategy for code retrieval:
 * <ul>
 *   <li>{@link #DENSE}: Vector-only retrieval using dense embeddings (e.g., pgvector).</li>
 *   <li>{@link #SPARSE}: BM25 lexical retrieval using Lucene.</li>
 *   <li>{@link #HYBRID}: Combined dense and sparse retrieval with rank fusion or score blending.</li>
 * </ul>
 */
public enum RetrievalStrategy {
    DENSE,
    SPARSE,
    HYBRID
}
