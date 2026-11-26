package com.shravan.jcode_intelligence.service.evaluation.generation;

/**
 * Verification outcome of an inline citation against supplied chunk text.
 *
 * @param citationIndex    0-based citation position in the response
 * @param citationMarker   raw citation marker (e.g. {@code [cite: Foo.java:10-20#bar]})
 * @param sentence         surrounding sentence extracted from generated answer
 * @param resolved         whether coordinate resolved to a supplied document by path and range overlap
 * @param resolvedFilePath path of resolved document (nullable)
 * @param verdict          "SUPPORTED" or "UNSUPPORTED"
 * @param rationale        explanation of verification verdict
 */
public record CitationVerdict(
        int citationIndex,
        String citationMarker,
        String sentence,
        boolean resolved,
        String resolvedFilePath,
        String verdict,
        String rationale
) {
    public boolean isSupported() {
        return "SUPPORTED".equalsIgnoreCase(verdict);
    }
}
