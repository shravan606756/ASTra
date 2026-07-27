package com.shravan.jcode_intelligence.model;

/**
 * Classifies the user's query intent to drive retrieval strategy selection.
 *
 * <p>Each intent maps to a distinct combination of:
 * <ul>
 *   <li>Retrieval strategy (which chunk types to prioritize/fetch)</li>
 *   <li>Chunk-type weights (for reranking)</li>
 *   <li>Adaptive Top-K (how many results to fetch and return)</li>
 *   <li>Prompt template (LLM instructions tuned for the intent)</li>
 * </ul>
 *
 * <pre>
 *   User Query
 *       │
 *       ▼
 *   QueryIntentClassifier  →  QueryIntent
 *       │
 *       ▼
 *   RetrievalStrategySelector  (intent-aware retrieval)
 *       │
 *       ▼
 *   RetrievalReranker  (chunk-type weighted reranking)
 *       │
 *       ▼
 *   PromptBuilder  (structured context + intent-specific template)
 *       │
 *       ▼
 *   LLM
 * </pre>
 */
public enum QueryIntent {

    /** Lookup or explain a specific method implementation. */
    METHOD,

    /** Lookup or explain a specific class, interface, enum, or record. */
    CLASS,

    /** Explain package structure, organization, or package-level overview. */
    PACKAGE,

    /** Explain system architecture, pipeline, component overview. */
    ARCHITECTURE,

    /** Explain end-to-end workflow, data flow, step-by-step execution. */
    WORKFLOW,

    /** Explain relationships: dependencies, inheritance, usage, call graph. */
    RELATIONSHIP,

    /** Locate a specific code element by name, signature, or identifier. */
    SEARCH,

    /** Explain design patterns, abstractions, extension points, SOLID principles. */
    DESIGN,

    /** Unable to determine intent; falls back to generic hybrid retrieval. */
    UNKNOWN;

    /**
     * Returns the prompt template filename associated with this intent.
     */
    public String templateName() {
        return switch (this) {
            case METHOD -> "explain-method.st";
            case CLASS -> "explain-class.st";
            case PACKAGE -> "summarize-project.st";
            case ARCHITECTURE -> "architecture.st";
            case WORKFLOW -> "workflow.st";
            case RELATIONSHIP -> "relationship.st";
            case SEARCH -> "search.st";
            case DESIGN -> "design.st";
            case UNKNOWN -> "answer-question.st";
        };
    }
}
