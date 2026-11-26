package com.shravan.jcode_intelligence.service.evaluation.citation;

/**
 * Code entity declaration verification status within cited line bounds.
 */
public enum EntityStatus {
    /** Exactly one matching declaration is contained within the cited range. */
    VALID,
    /** Declared entity matching the symbol was not found in file or cited range. */
    ENTITY_NOT_FOUND,
    /** Multiple entities matching the symbol were found in the cited range. */
    AMBIGUOUS_MATCH,
    /** JavaParser failed to parse the source file due to syntax errors. */
    PARSE_ERROR,
    /** Entity validation is not applicable (non-Java file or symbol-free citation). */
    NOT_APPLICABLE,
    /** Entity verification was skipped. */
    SKIPPED
}
