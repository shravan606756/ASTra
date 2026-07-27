package com.shravan.jcode_intelligence.service;

import com.shravan.jcode_intelligence.model.QueryIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Rule-based classifier that determines the {@link QueryIntent} for a user query.
 *
 * <p>Classification priority (highest to lowest):
 * <ol>
 *   <li>Code identifier patterns (parentheses → METHOD, PascalCase → CLASS)</li>
 *   <li>Explicit verb/noun signals (architecture, workflow, design, etc.)</li>
 *   <li>Search/locate patterns (where is, find, locate)</li>
 *   <li>Relationship patterns (which classes use, who calls, dependencies)</li>
 *   <li>Fallback → UNKNOWN</li>
 * </ol>
 *
 * <p>No ML required — pure heuristic classification sufficient for
 * the domain-specific vocabulary of code intelligence queries.
 */
@Component
public class QueryIntentClassifier {

    private static final Logger log = LoggerFactory.getLogger(QueryIntentClassifier.class);

    // ── Code Identifier Patterns ──────────────────────────────

    /** Matches method-call syntax: findAll(), parse(), doFragment() */
    private static final Pattern METHOD_PARENS_PATTERN =
            Pattern.compile("\\b[a-z][a-zA-Z0-9]*\\s*\\(\\)");

    /** Matches PascalCase identifiers: Node, JavaParser, LexicalPreservingPrinter */
    private static final Pattern PASCAL_CASE_PATTERN =
            Pattern.compile("\\b[A-Z][a-zA-Z0-9]{2,}\\b");

    /** Matches camelCase identifiers without parens: doFragment, indexProject */
    private static final Pattern CAMEL_CASE_PATTERN =
            Pattern.compile("\\b[a-z]+[A-Z][a-zA-Z0-9]*\\b");

    // ── Verb/Noun Signal Keywords ─────────────────────────────

    private static final String[] ARCHITECTURE_SIGNALS = {
            "architecture", "architectural", "system design", "component overview",
            "high-level", "high level", "pipeline overview", "system overview"
    };

    private static final String[] WORKFLOW_SIGNALS = {
            "workflow", "how does", "how do", "step by step", "step-by-step",
            "end to end", "end-to-end", "data flow", "execution flow",
            "what happens when", "process of", "how is", "how are"
    };

    private static final String[] PACKAGE_SIGNALS = {
            "package structure", "package organization", "package overview",
            "packages", "package summary", "package layout"
    };

    private static final String[] RELATIONSHIP_SIGNALS = {
            "which classes use", "who calls", "who uses", "what uses",
            "what calls", "depends on", "dependency", "dependencies",
            "inheritance", "hierarchy", "call graph", "callers of",
            "implementations of", "subclasses of", "what implements",
            "which classes implement", "related to", "relationship"
    };

    private static final String[] SEARCH_SIGNALS = {
            "where is", "where are", "find ", "locate", "look up", "lookup",
            "which file", "what file", "show me", "is there a"
    };

    private static final String[] DESIGN_SIGNALS = {
            "design pattern", "pattern", "abstraction", "extension point",
            "solid principle", "single responsibility", "open closed",
            "liskov", "interface segregation", "dependency inversion",
            "strategy pattern", "factory", "builder pattern", "observer",
            "decorator", "template method"
    };

    private static final String[] METHOD_SIGNALS = {
            "method", "function", "implementation of", "algorithm",
            "return value", "parameters of", "signature of"
    };

    private static final String[] CLASS_SIGNALS = {
            "class", "interface", "enum", "record", "responsibilities of",
            "fields of", "members of", "api of", "public api"
    };

    // ── Common Words to Exclude from Symbol Detection ─────────

    private static final String[] COMMON_WORDS = {
            "How", "What", "Where", "When", "Why", "Which",
            "Does", "Can", "Could", "Would", "Should",
            "The", "This", "That", "Are", "Was", "Were",
            "Has", "Have", "Had", "Will", "Did",
            "Explain", "Describe", "Show", "List", "Find", "Give",
            "Java", "Spring", "Class", "Method", "Type",
            "File", "Line", "Code", "Project", "Repository",
            "All", "Not", "Use", "Get", "Set"
    };

    /**
     * Classifies the user query into a {@link QueryIntent}.
     *
     * @param query the user's natural-language query
     * @return the classified intent
     */
    public QueryIntent classify(String query) {
        if (query == null || query.isBlank()) {
            return QueryIntent.UNKNOWN;
        }

        String lower = query.toLowerCase(Locale.ROOT).trim();

        // Priority 1: Explicit method call syntax — "explain findAll()"
        if (METHOD_PARENS_PATTERN.matcher(query).find()) {
            log.info("Intent classified as METHOD (method-call syntax detected) for query: '{}'", query);
            return QueryIntent.METHOD;
        }

        // Priority 2: Multi-word phrase signals (checked before single-word to avoid conflicts)

        if (matchesAny(lower, WORKFLOW_SIGNALS)) {
            log.info("Intent classified as WORKFLOW for query: '{}'", query);
            return QueryIntent.WORKFLOW;
        }

        if (matchesAny(lower, ARCHITECTURE_SIGNALS)) {
            log.info("Intent classified as ARCHITECTURE for query: '{}'", query);
            return QueryIntent.ARCHITECTURE;
        }

        if (matchesAny(lower, PACKAGE_SIGNALS)) {
            log.info("Intent classified as PACKAGE for query: '{}'", query);
            return QueryIntent.PACKAGE;
        }

        if (matchesAny(lower, RELATIONSHIP_SIGNALS)) {
            log.info("Intent classified as RELATIONSHIP for query: '{}'", query);
            return QueryIntent.RELATIONSHIP;
        }

        if (matchesAny(lower, DESIGN_SIGNALS)) {
            log.info("Intent classified as DESIGN for query: '{}'", query);
            return QueryIntent.DESIGN;
        }

        // Priority 3: Search/locate patterns — "where is BackupJob?"
        if (matchesAny(lower, SEARCH_SIGNALS)) {
            log.info("Intent classified as SEARCH for query: '{}'", query);
            return QueryIntent.SEARCH;
        }

        // Priority 4: Explicit method/class keywords
        if (matchesAny(lower, METHOD_SIGNALS)) {
            log.info("Intent classified as METHOD (keyword match) for query: '{}'", query);
            return QueryIntent.METHOD;
        }

        if (matchesAny(lower, CLASS_SIGNALS)) {
            log.info("Intent classified as CLASS (keyword match) for query: '{}'", query);
            return QueryIntent.CLASS;
        }

        // Priority 5: Code identifier heuristics — "Explain Node" → CLASS
        Optional<QueryIntent> identifierIntent = classifyByIdentifier(query);
        if (identifierIntent.isPresent()) {
            log.info("Intent classified as {} (identifier heuristic) for query: '{}'",
                    identifierIntent.get(), query);
            return identifierIntent.get();
        }

        // Priority 6: Broad architecture/overview signals
        if (lower.contains("overview") || lower.contains("summary") || lower.contains("summarize")) {
            log.info("Intent classified as ARCHITECTURE (overview keyword) for query: '{}'", query);
            return QueryIntent.ARCHITECTURE;
        }

        log.info("Intent classified as UNKNOWN for query: '{}'", query);
        return QueryIntent.UNKNOWN;
    }

    /**
     * Attempts to classify intent based on code identifiers present in the query.
     * PascalCase → CLASS, camelCase → METHOD.
     */
    private Optional<QueryIntent> classifyByIdentifier(String query) {
        // Check for PascalCase class names (e.g., "Explain Node", "Explain LexicalPreservingPrinter")
        var pascalMatcher = PASCAL_CASE_PATTERN.matcher(query);
        while (pascalMatcher.find()) {
            String candidate = pascalMatcher.group();
            if (!isCommonWord(candidate)) {
                return Optional.of(QueryIntent.CLASS);
            }
        }

        // Check for camelCase method names (e.g., "explain doFragment")
        var camelMatcher = CAMEL_CASE_PATTERN.matcher(query);
        while (camelMatcher.find()) {
            String candidate = camelMatcher.group();
            if (!isCommonWord(candidate)) {
                return Optional.of(QueryIntent.METHOD);
            }
        }

        return Optional.empty();
    }

    private boolean matchesAny(String lowerQuery, String[] signals) {
        for (String signal : signals) {
            if (lowerQuery.contains(signal)) {
                return true;
            }
        }
        return false;
    }

    private boolean isCommonWord(String candidate) {
        for (String common : COMMON_WORDS) {
            if (common.equalsIgnoreCase(candidate)) {
                return true;
            }
        }
        return false;
    }
}
