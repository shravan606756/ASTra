package com.shravan.jcode_intelligence.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for hierarchical AST-aware chunking and embedding budgets.
 *
 * <p>Calibrated for embedding models such as {@code nomic-embed-text} with an
 * active Ollama context window of 2,048 tokens (~4 chars/token → 8,000 chars max document limit ≈ 2,000 tokens).
 */
@Configuration
public class ChunkingConfig {

    /** Maximum character length for a single method before fragmentation. */
    @Value("${astra.chunking.max-method-chars:6000}")
    private int maxMethodChars = 6000;

    /** Target maximum character length for each METHOD_FRAGMENT chunk. */
    @Value("${astra.chunking.max-fragment-chars:3000}")
    private int maxFragmentChars = 3000;

    /** Maximum method signatures included in a single CLASS summary. */
    @Value("${astra.chunking.max-summary-methods:100}")
    private int maxSummaryMethods = 100;

    /** Maximum field signatures included in a single CLASS summary. */
    @Value("${astra.chunking.max-summary-fields:50}")
    private int maxSummaryFields = 50;

    /** Maximum character length for an individual FIELD chunk. */
    @Value("${astra.chunking.max-field-chars:3000}")
    private int maxFieldChars = 3000;

    /** Absolute maximum character length for any single Document before embedding (calibrated for 2048-token context). */
    @Value("${astra.chunking.max-document-chars:8000}")
    private int maxDocumentChars = 8000;

    /** Number of documents sent per vector store embedding batch. */
    @Value("${astra.embedding.batch-size:100}")
    private int batchSize = 100;

    public int getMaxMethodChars() {
        return maxMethodChars;
    }

    public int getMaxFragmentChars() {
        return maxFragmentChars;
    }

    public int getMaxSummaryMethods() {
        return maxSummaryMethods;
    }

    public int getMaxSummaryFields() {
        return maxSummaryFields;
    }

    public int getMaxFieldChars() {
        return maxFieldChars;
    }

    public int getMaxDocumentChars() {
        return maxDocumentChars;
    }

    public int getBatchSize() {
        return batchSize;
    }
}
