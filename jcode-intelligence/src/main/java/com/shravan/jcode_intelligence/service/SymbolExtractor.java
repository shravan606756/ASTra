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

    // Matches PascalCase identifiers: PromptRouter, ChatServiceImpl, CodeChunk, MethodFragmenter, etc.
    private static final Pattern CLASS_NAME_PATTERN =
            Pattern.compile("\\b([A-Z][a-zA-Z0-9]{2,})\\b");

    // Matches method-style identifiers with parentheses: buildPrompt(), route(), getQuery()
    private static final Pattern METHOD_WITH_PARENS_PATTERN =
            Pattern.compile("\\b([a-z][a-zA-Z0-9]+)\\s*\\(\\)");

    // Matches standalone camelCase method identifiers: doFragment, indexProject, buildSummary
    private static final Pattern CAMEL_CASE_METHOD_PATTERN =
            Pattern.compile("\\b([a-z]+[A-Z][a-zA-Z0-9]*)\\b");

    /**
     * Extracts the most prominent Java symbol from a user query.
     * Priority: PascalCase class name > method with parens > camelCase method name.
     *
     * @param query the user's natural-language query
     * @return the extracted symbol name, or empty if no symbol is detected
     */
    public Optional<String> extract(String query) {
        if (query == null || query.isBlank()) {
            return Optional.empty();
        }

        // 1. Try to extract a PascalCase class/interface/enum name first
        Matcher classMatcher = CLASS_NAME_PATTERN.matcher(query);
        while (classMatcher.find()) {
            String candidate = classMatcher.group(1);
            if (!isCommonWord(candidate)) {
                log.debug("Extracted class symbol '{}' from query: '{}'", candidate, query);
                return Optional.of(candidate);
            }
        }

        // 2. Try to extract a method with parens (e.g., "doFragment()")
        Matcher methodParensMatcher = METHOD_WITH_PARENS_PATTERN.matcher(query);
        if (methodParensMatcher.find()) {
            String method = methodParensMatcher.group(1);
            log.debug("Extracted method symbol with parens '{}' from query: '{}'", method, query);
            return Optional.of(method);
        }

        // 3. Try to extract a standalone camelCase method name (e.g., "doFragment", "indexProject")
        Matcher camelCaseMatcher = CAMEL_CASE_METHOD_PATTERN.matcher(query);
        while (camelCaseMatcher.find()) {
            String method = camelCaseMatcher.group(1);
            if (!isCommonWord(method)) {
                log.debug("Extracted camelCase method symbol '{}' from query: '{}'", method, query);
                return Optional.of(method);
            }
        }

        return Optional.empty();
    }

    /**
     * Returns true if the candidate is a common English word that should not
     * be treated as a Java symbol.
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
