package com.shravan.jcode_intelligence.service.evaluation.citation;

/**
 * Filesystem verification status of a cited source file coordinate.
 */
public enum FileStatus {
    /** File exists on disk, is a regular file within repository root, and line bounds are valid. */
    VALID,
    /** File does not exist on disk or is not a regular file. */
    NOT_FOUND,
    /** File path escapes repository root (e.g. directory traversal or symlink escape). */
    TRAVERSAL_REJECTED,
    /** Line numbers are non-positive, inverted, or exceed file length. */
    OUT_OF_BOUNDS,
    /** Coordinate syntax is invalid (malformed citation marker). */
    INVALID_SYNTAX,
    /** File verification was skipped (e.g. revision mismatch or unavailable repository). */
    SKIPPED
}
