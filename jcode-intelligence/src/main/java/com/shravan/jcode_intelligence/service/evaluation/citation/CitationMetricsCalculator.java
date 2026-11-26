package com.shravan.jcode_intelligence.service.evaluation.citation;

import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Calculates citation precision and expected-source recall metrics for benchmark evaluation.
 *
 * <h3>Metric Denominators and Null Handling</h3>
 * <ul>
 *   <li><b>Precision</b>: {@code structurallyValidCitations / (double) totalCitations}. If {@code totalCitations == 0},
 *       returns {@code null} to prevent misleading 0.0 scores when no citations were attempted.</li>
 *   <li><b>Recall</b>: {@code matchedExpectedSources / (double) totalExpectedSources}. If {@code totalExpectedSources == 0},
 *       returns {@code null} indicating ground truth was not specified for the query.</li>
 * </ul>
 *
 * <h3>Recall as an Expected-Source Proxy &amp; Limitations</h3>
 * <p>Recall measures whether the response cited the ground-truth source files specified in
 * the benchmark suite. It acts as an expected-source coverage proxy rather than an exhaustive,
 * fine-grained ground-truth citation validator:</p>
 * <ul>
 *   <li><b>Granularity Limit</b>: Benchmark ground-truth datasets typically specify expected source
 *       files or components rather than exact line spans for every supporting claim. Matching an expected
 *       file confirms the model consulted the correct component, but does not verify that every
 *       cited line within that file is necessary or sufficient.</li>
 *   <li><b>Multiple Valid Sources</b>: In large codebases, equivalent implementations or alternative
 *       delegates may answer a question accurately without citing the specific file in ground truth.</li>
 *   <li><b>Denominator Sensitivity</b>: For queries with few expected sources, missing a single citation
 *       drastically reduces recall (e.g., from 1.0 to 0.5).</li>
 * </ul>
 */
@Component
public class CitationMetricsCalculator {

    /**
     * Calculates citation metrics from validation results and expected benchmark sources.
     *
     * @param validationResults list of validation results for all parsed citations (including malformed)
     * @param expectedSources   expected benchmark sources (e.g. file names or relative paths)
     * @return citation metrics with safe null denominators
     */
    public CitationMetrics calculateMetrics(List<CitationValidationResult> validationResults, List<String> expectedSources) {
        int totalCitations = (validationResults != null) ? validationResults.size() : 0;
        int validSyntaxCount = 0;
        int structurallyValidCount = 0;

        Set<String> citedFiles = new HashSet<>();

        if (validationResults != null) {
            for (CitationValidationResult result : validationResults) {
                Citation citation = result.citation();
                if (citation != null && citation.validSyntax()) {
                    validSyntaxCount++;
                }
                if (result.structurallyValid()) {
                    structurallyValidCount++;
                    if (citation != null && citation.filePath() != null) {
                        citedFiles.add(normalizePath(citation.filePath()));
                    }
                }
            }
        }

        Double precision = (totalCitations > 0)
                ? (double) structurallyValidCount / totalCitations
                : null;

        int totalExpected = (expectedSources != null) ? expectedSources.size() : 0;
        int matchedExpected = 0;

        if (totalExpected > 0) {
            for (String expected : expectedSources) {
                if (expected == null || expected.isBlank()) {
                    continue;
                }
                String normalizedExpected = normalizePath(expected);
                boolean matched = citedFiles.stream().anyMatch(cited ->
                        cited.equals(normalizedExpected)
                                || cited.endsWith("/" + normalizedExpected)
                                || normalizedExpected.endsWith("/" + cited)
                );
                if (matched) {
                    matchedExpected++;
                }
            }
        }

        Double recall = (totalExpected > 0)
                ? (double) matchedExpected / totalExpected
                : null;

        return new CitationMetrics(
                totalCitations,
                validSyntaxCount,
                structurallyValidCount,
                precision,
                recall,
                totalExpected,
                matchedExpected
        );
    }

    private String normalizePath(String path) {
        String p = path.replace('\\', '/').trim();
        while (p.startsWith("./")) {
            p = p.substring(2);
        }
        return p.toLowerCase(Locale.ROOT);
    }
}
