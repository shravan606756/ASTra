package com.shravan.jcode_intelligence.service.classification;

/**
 * Pipeline strategy for repository indexing.
 */
public enum IndexingPipeline {
    /** Existing whole-repository single-pass AST indexing pipeline. */
    EXISTING,

    /** Large-repository streaming AST indexing pipeline (Issue #31). */
    STREAMING
}
