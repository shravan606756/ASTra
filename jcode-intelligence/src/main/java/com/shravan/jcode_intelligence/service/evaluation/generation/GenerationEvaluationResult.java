package com.shravan.jcode_intelligence.service.evaluation.generation;

import java.util.List;

/**
 * Comprehensive result of generation semantic evaluation.
 *
 * @param status                "SUCCESS" or "FAILED"
 * @param failureReason         diagnostic failure message if evaluation failed, else null
 * @param faithfulness          ratio of grounded claims to total claims [0.0, 1.0], null if failed
 * @param ungroundedClaims      count of ungrounded claims, null if failed
 * @param totalClaims           total claims extracted, null if failed
 * @param relevancy             normalized question relevancy rating [0.0, 1.0], null if failed
 * @param correctness           normalized reference correctness rating [0.0, 1.0], null if failed or no reference
 * @param correctnessApproximate always true (correctness is an approximate semantic comparison)
 * @param citationPrecision     ratio of supported citations to total citations [0.0, 1.0], null if 0 citations or failed
 * @param totalCitations        total citations parsed from answer (including malformed/unresolved)
 * @param supportedCitations    count of resolved and supported citations
 * @param claimVerdicts         detailed claim verdicts
 * @param citationVerdicts      detailed citation verdicts
 * @param judgeLatencyMs        latency of judge LLM call in ms
 */
public record GenerationEvaluationResult(
        String status,
        String failureReason,
        Double faithfulness,
        Integer ungroundedClaims,
        Integer totalClaims,
        Double relevancy,
        Double correctness,
        boolean correctnessApproximate,
        Double citationPrecision,
        int totalCitations,
        int supportedCitations,
        List<ClaimVerdict> claimVerdicts,
        List<CitationVerdict> citationVerdicts,
        Long judgeLatencyMs
) {
    public boolean isSuccess() {
        return "SUCCESS".equalsIgnoreCase(status);
    }

    public static GenerationEvaluationResult failed(String reason) {
        return new GenerationEvaluationResult(
                "FAILED",
                reason,
                null,
                null,
                null,
                null,
                null,
                true,
                null,
                0,
                0,
                List.of(),
                List.of(),
                null
        );
    }
}
