package com.shravan.jcode_intelligence.service.classification;

/**
 * Classification of repository size and memory requirements.
 */
public enum RepositoryClass {
    /** Fits within available JVM heap budget; uses existing whole-repository indexing pipeline. */
    NORMAL,

    /** Exceeds available JVM heap budget; requires streaming indexing pipeline. */
    LARGE
}
