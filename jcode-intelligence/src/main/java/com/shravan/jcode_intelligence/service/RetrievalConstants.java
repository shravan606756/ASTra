package com.shravan.jcode_intelligence.service;

/**
 * Constants for retrieval metadata keys, candidate routes, and scoring attributes.
 */
public final class RetrievalConstants {

    private RetrievalConstants() {}

    /**
     * Metadata key for dense vector similarity scores.
     */
    public static final String METADATA_DENSE_SCORE = "denseScore";

    /**
     * Metadata key for Reciprocal Rank Fusion (RRF) fused scores.
     */
    public static final String METADATA_RRF_SCORE = "rrfScore";

    /**
     * Metadata key for the collection or set of retrieval routes that produced a candidate.
     */
    public static final String METADATA_RETRIEVAL_ROUTES = "retrievalRoutes";

    /**
     * Route identifier for vector similarity search candidates.
     */
    public static final String ROUTE_VECTOR = "vector";

    /**
     * Route identifier for exact SQL metadata lookup candidates.
     */
    public static final String ROUTE_SQL_METADATA = "metadata";
    public static final String ROUTE_METADATA = "metadata";

    /**
     * Route identifier for parent class summary context enrichment candidates.
     */
    public static final String ROUTE_PARENT = "parent";
}
