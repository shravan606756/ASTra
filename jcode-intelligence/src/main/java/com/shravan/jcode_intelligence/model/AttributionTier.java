package com.shravan.jcode_intelligence.model;

/**
 * Formally distinguishes the four hierarchical tiers in ASTra's resource attribution lifecycle:
 *
 * <ol>
 *   <li><b>RETRIEVED_CHUNKS</b>: All candidate documents retrieved from vector and metadata storage
 *       matching the query before context budgeting.</li>
 *   <li><b>SUPPLIED_CONTEXT</b>: The subset of retrieved chunks actually included within the character
 *       and document count budget and formatted into the prompt context for the LLM.</li>
 *   <li><b>REFERENCED_RESOURCES</b>: Deduplicated source files, classes, and code elements associated
 *       with the supplied context, returned in client responses ({@code ChatResponse.sources}).</li>
 *   <li><b>SUPPORTING_CITATIONS</b>: Granular claim-level citations that specifically justify individual
 *       statements in the generated answer (evaluation / LLM-judge).</li>
 * </ol>
 */
public enum AttributionTier {

    /**
     * Tier 1: All candidate documents retrieved from storage.
     */
    RETRIEVED_CHUNKS(1, "Candidate chunks retrieved from vector and metadata storage before prompt budgeting."),

    /**
     * Tier 2: Chunks actually included in the LLM prompt context string.
     */
    SUPPLIED_CONTEXT(2, "Budgeted chunks actually supplied in the LLM prompt context."),

    /**
     * Tier 3: Deduplicated source files and code elements returned in the API response.
     */
    REFERENCED_RESOURCES(3, "Deduplicated source files and coordinates associated with supplied context."),

    /**
     * Tier 4: Fine-grained citations specifically justifying individual claims in the answer.
     */
    SUPPORTING_CITATIONS(4, "Claim-level citations justifying specific statements in the generated response.");

    private final int level;
    private final String description;

    /**
     * Constructs an attribution tier enum with its hierarchy level and description.
     *
     * @param level       hierarchy level (1 to 4)
     * @param description human-readable explanation of this attribution stage
     */
    AttributionTier(int level, String description) {
        this.level = level;
        this.description = description;
    }

    /**
     * Returns the numeric tier hierarchy level (1 through 4).
     *
     * @return tier level
     */
    public int getLevel() {
        return level;
    }

    /**
     * Returns the description of this attribution tier.
     *
     * @return tier description
     */
    public String getDescription() {
        return description;
    }
}

