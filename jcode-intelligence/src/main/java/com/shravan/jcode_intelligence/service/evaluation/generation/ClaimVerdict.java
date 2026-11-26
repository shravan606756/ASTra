package com.shravan.jcode_intelligence.service.evaluation.generation;

/**
 * Verification outcome of an individual factual claim against the prompt context.
 *
 * @param claim   extracted factual claim statement
 * @param verdict "SUPPORTED" or "UNGROUNDED"
 */
public record ClaimVerdict(
        String claim,
        String verdict
) {
    public boolean isGrounded() {
        return "SUPPORTED".equalsIgnoreCase(verdict);
    }
}
