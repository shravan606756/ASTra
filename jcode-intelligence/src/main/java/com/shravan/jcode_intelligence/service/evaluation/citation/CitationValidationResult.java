package com.shravan.jcode_intelligence.service.evaluation.citation;

/**
 * Comprehensive verification result for an individual citation against source checkout and AST.
 *
 * @param citation          the citation being evaluated
 * @param structurallyValid true if file exists, bounds are valid, and entity (if Java with symbol) matches
 * @param revisionStatus    JGit revision checkout status
 * @param fileStatus        filesystem coordinate verification status
 * @param entityStatus      AST entity verification status
 * @param message           diagnostic message
 */
public record CitationValidationResult(
        Citation citation,
        boolean structurallyValid,
        RevisionStatus revisionStatus,
        FileStatus fileStatus,
        EntityStatus entityStatus,
        String message
) {
    public boolean isSkipped() {
        return revisionStatus == RevisionStatus.MISMATCHED || revisionStatus == RevisionStatus.UNAVAILABLE;
    }

    public static CitationValidationResult skipped(Citation citation, RevisionStatus revisionStatus, String reason) {
        return new CitationValidationResult(
                citation,
                false,
                revisionStatus,
                FileStatus.SKIPPED,
                EntityStatus.SKIPPED,
                reason
        );
    }

    public static CitationValidationResult malformed(Citation citation, String reason) {
        return new CitationValidationResult(
                citation,
                false,
                RevisionStatus.UNAVAILABLE,
                FileStatus.INVALID_SYNTAX,
                EntityStatus.NOT_APPLICABLE,
                reason
        );
    }
}
