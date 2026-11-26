package com.shravan.jcode_intelligence.service.evaluation.citation;

/**
 * Git revision checkout and dirty-state status for source verification.
 */
public enum RevisionStatus {
    /** Current HEAD matches expected revision and working tree is clean. */
    MATCHED,
    /** Current HEAD matches expected revision, but working tree has uncommitted modifications. */
    DIRTY,
    /** Current HEAD does not match expected revision. */
    MISMATCHED,
    /** Repository cannot be opened, .git directory missing, or path inaccessible. */
    UNAVAILABLE
}
