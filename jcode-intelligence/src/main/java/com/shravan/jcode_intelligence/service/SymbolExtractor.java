package com.shravan.jcode_intelligence.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts Java symbol names (class names, method names) from user queries.
 * Used by the retrieval layer to perform deterministic metadata lookups
 * when the query references an explicit Java identifier.
 */
@Component
public class SymbolExtractor {

    private static final Logger log = LoggerFactory.getLogger(SymbolExtractor.class);

    // Matches PascalCase identifiers: PromptRouter, ChatServiceImpl, CodeChunk, etc.
    private static final Pattern CLASS_NAME_PATTERN =
            Pattern.compile("\\b([A-Z][a-zA-Z0-9]{2,})\\b");

    // Matches method-style identifiers: buildPrompt(), route(), getQuery()
    private static final Pattern METHOD_NAME_PATTERN =
            Pattern.compile("\\b([a-z][a-zA-Z0-9]+)\\s*\\(\\)");

    /**
     * Extracts the most prominent Java symbol from a user query.
     * Priority: PascalCase class name > method name with parentheses.
     *
     * @param query the user's natural-language query
     * @return the extracted symbol name, or empty if no symbol is detected
     */
    public Optional<String> extract(String query) {
        if (query == null || query.isBlank()) {
            return Optional.empty();
        }

        // Try to extract a PascalCase class/interface/enum name first
        Matcher classMatcher = CLASS_NAME_PATTERN.matcher(query);
        while (classMatcher.find()) {
            String candidate = classMatcher.group(1);
            // Filter out common English words that happen to be PascalCase
            if (!isCommonWord(candidate)) {
                log.debug("Extracted class symbol '{}' from query: '{}'", candidate, query);
                return Optional.of(candidate);
            }
        }

        // Try to extract a method name (e.g., "buildPrompt()")
        Matcher methodMatcher = METHOD_NAME_PATTERN.matcher(query);
        if (methodMatcher.find()) {
            String method = methodMatcher.group(1);
            log.debug("Extracted method symbol '{}' from query: '{}'", method, query);
            return Optional.of(method);
        }

        return Optional.empty();
    }

    /**
     * Returns true if the candidate is a common English word that should not
     * be treated as a Java symbol, even though it starts with uppercase.
     */
    private boolean isCommonWord(String candidate) {
        return switch (candidate) {
            case "How", "What", "Where", "When", "Why", "Which",
                 "Does", "Can", "Could", "Would", "Should",
                 "The", "This", "That", "Are", "Was", "Were",
                 "Has", "Have", "Had", "Will", "Did",
                 "Explain", "Describe", "Show", "List", "Find", "Give",
                 "Java", "Spring", "Class", "Method", "Type",
                 "File", "Line", "Code", "Project", "Repository" -> true;
            default -> false;
        };
    }
}
