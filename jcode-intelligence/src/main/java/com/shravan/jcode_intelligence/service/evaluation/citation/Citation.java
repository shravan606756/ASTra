package com.shravan.jcode_intelligence.service.evaluation.citation;

/**
 * Represents an inline citation extracted from generated text.
 * Holds parsed coordinate components and tracks syntactic validity for accuracy tracking.
 *
 * @param rawText     the full matched citation text (e.g. {@code "[cite: path/File.java:1-10#foo]"})
 * @param filePath    normalized relative file path (nullable if malformed)
 * @param startLine   one-based start line (nullable if malformed)
 * @param endLine     one-based end line (nullable if malformed)
 * @param symbol      optional declaration symbol name or fragment (nullable)
 * @param validSyntax whether the citation conforms to valid marker syntax
 */
public record Citation(
        String rawText,
        String filePath,
        Integer startLine,
        Integer endLine,
        String symbol,
        boolean validSyntax
) {
    public Citation {
        if (rawText == null) {
            rawText = "";
        }
    }

    public static Citation malformed(String rawText) {
        return new Citation(rawText, null, null, null, null, false);
    }

    public static Citation valid(String rawText, String filePath, int startLine, int endLine, String symbol) {
        return new Citation(rawText, filePath, startLine, endLine, symbol, true);
    }

    public static Citation valid(String rawText, String filePath, int startLine, int endLine) {
        return new Citation(rawText, filePath, startLine, endLine, null, true);
    }

    public boolean hasSymbol() {
        return symbol != null && !symbol.isBlank();
    }
}
