package com.shravan.jcode_intelligence.service.evaluation.citation;

/**
 * Summary metrics of citation correctness and expected-source coverage.
 *
 * @param totalCitations             total citation markers found in generated text (including malformed)
 * @param validSyntaxCitations        citations with syntactically valid coordinates
 * @param structurallyValidCitations  citations passing file existence, bounds, and entity checks
 * @param precision                   citation correctness: structurallyValid / totalCitations (null if 0 citations)
 * @param recall                      expected-source recall: matchedExpectedSources / totalExpectedSources (null if 0 expected)
 * @param totalExpectedSources        number of ground-truth sources expected
 * @param matchedExpectedSources      number of expected sources covered by at least one valid citation
 */
public record CitationMetrics(
        int totalCitations,
        int validSyntaxCitations,
        int structurallyValidCitations,
        Double precision,
        Double recall,
        int totalExpectedSources,
        int matchedExpectedSources
) {
    public boolean hasCitations() {
        return totalCitations > 0;
    }

    public boolean hasExpectedSources() {
        return totalExpectedSources > 0;
    }
}
