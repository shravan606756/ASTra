package com.shravan.jcode_intelligence.service.evaluation.citation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts inline citation annotations from LLM generated response text.
 *
 * Supported formats:
 * - Full citation: {@code [cite: path/File.java:10-25#Symbol]}
 * - Symbol-free range: {@code [cite: path/File.java:10-25]}
 *
 * Malformed markers such as {@code [cite: invalid]} or {@code [cite: ]} are retained
 * as invalid citations with {@code validSyntax = false} so they can be accounted for
 * in evaluation denominators.
 */
@Component
public class CitationParser {

    private static final Logger log = LoggerFactory.getLogger(CitationParser.class);

    // Matches any [cite: ...] bracket block
    private static final Pattern CITE_BLOCK_PATTERN = Pattern.compile("\\[cite:\\s*([^\\]]*)\\]");

    // Matches path:startLine-endLine(#symbol)? within the bracket content
    // Group 1: path, Group 2: startLine, Group 3: endLine, Group 4: optional #symbol (without #)
    private static final Pattern VALID_CITATION_CONTENT_PATTERN = Pattern.compile(
            "^([^:\\s#]+):(\\d+)-(\\d+)(?:#([^\\s#]+))?$"
    );

    /**
     * Parses all citation markers from the given response text in document order.
     *
     * @param text input response text (can be null or empty)
     * @return list of parsed citations
     */
    public List<Citation> parseCitations(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        List<Citation> citations = new ArrayList<>();
        Matcher blockMatcher = CITE_BLOCK_PATTERN.matcher(text);

        while (blockMatcher.find()) {
            String fullMatch = blockMatcher.group(0);
            String innerContent = blockMatcher.group(1).trim();

            if (innerContent.isEmpty()) {
                citations.add(Citation.malformed(fullMatch));
                continue;
            }

            Matcher contentMatcher = VALID_CITATION_CONTENT_PATTERN.matcher(innerContent);
            if (contentMatcher.matches()) {
                String rawPath = contentMatcher.group(1).trim().replace('\\', '/');
                try {
                    int startLine = Integer.parseInt(contentMatcher.group(2));
                    int endLine = Integer.parseInt(contentMatcher.group(3));
                    String symbol = contentMatcher.group(4);
                    if (symbol != null) {
                        symbol = symbol.trim();
                        if (symbol.isEmpty()) {
                            symbol = null;
                        }
                    }

                    if (startLine > 0 && endLine >= startLine) {
                        citations.add(Citation.valid(fullMatch, rawPath, startLine, endLine, symbol));
                    } else {
                        // Inverted or non-positive line bounds
                        citations.add(Citation.malformed(fullMatch));
                    }
                } catch (NumberFormatException e) {
                    citations.add(Citation.malformed(fullMatch));
                }
            } else {
                citations.add(Citation.malformed(fullMatch));
            }
        }

        return citations;
    }
}
