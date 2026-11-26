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
            "workflow", "how does java", "how does symbol", "step by step", "step-by-step",
            "end to end", "end-to-end", "data flow", "execution flow", "traversal flow",
            "what happens when", "process of", "pipeline", "start to finish",
            "batch-parse", "trace the", "trace ", "initialization with", "ordering and",
            "import ordering", "collected during", "handling and", "syntax errors",
            "how are comments", "how does source root"
    };

    private static final String[] PACKAGE_SIGNALS = {
            "package structure", "package organization", "package overview",
            "packages", "package summary", "package layout"
    };

    private static final String[] RELATIONSHIP_SIGNALS = {
            "which classes use", "which classes extend", "which classes call",
            "which classes implement", "who calls", "who uses", "what uses",
            "what calls", "what implements", "what extends", "callers of",
            "called by", "users of", "used by", "implementations of",
            "subclasses of", "what interfaces does", "interfaces does",
            "implements ", "depends on", "dependency", "dependencies",
            "inheritance", "hierarchy", "call graph", "related to", "relationship"
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
            "return value", "parameters of", "signature of", "causes an",
            "findfirst", "getparentnode", "remove", "replace", "clone",
            "walk", "compute", "determine", "enable lexical"
    };

    private static final String[] CLASS_SIGNALS = {
            "class", "interface", "enum", "record", "responsibilities of",
            "fields of", "members of", "api of", "public api", "configured",
            "configuration", "parsing and", "fragmented", "customized for"
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

        // Priority 1: Relationship signals (take precedence even over method parens: e.g. "callers of getChildNodes()")
        if (matchesAny(lower, RELATIONSHIP_SIGNALS)) {
            log.info("Intent classified as RELATIONSHIP for query: '{}'", query);
            return QueryIntent.RELATIONSHIP;
        }

        // Priority 2: Explicit method call syntax — "explain findAll()"
        if (METHOD_PARENS_PATTERN.matcher(query).find()) {
            log.info("Intent classified as METHOD (method-call syntax detected) for query: '{}'", query);
            return QueryIntent.METHOD;
        }

        // Priority 3: Workflow multi-step signals
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

        if (matchesAny(lower, DESIGN_SIGNALS)) {
            log.info("Intent classified as DESIGN for query: '{}'", query);
            return QueryIntent.DESIGN;
        }

        // Priority 4: Search/locate patterns — resolve to CLASS or METHOD if code identifier present
        if (matchesAny(lower, SEARCH_SIGNALS)) {
            Optional<QueryIntent> codeIdentifier = classifyByIdentifier(query);
            if (codeIdentifier.isPresent()) {
                log.info("Intent classified as {} (search targeting identifier) for query: '{}'", codeIdentifier.get(), query);
                return codeIdentifier.get();
            }
            log.info("Intent classified as SEARCH for query: '{}'", query);
            return QueryIntent.SEARCH;
        }

        // Priority 5: Explicit method/class keywords
        if (matchesAny(lower, METHOD_SIGNALS)) {
            log.info("Intent classified as METHOD (keyword match) for query: '{}'", query);
            return QueryIntent.METHOD;
        }

        if (matchesAny(lower, CLASS_SIGNALS)) {
            log.info("Intent classified as CLASS (keyword match) for query: '{}'", query);
            return QueryIntent.CLASS;
        }

        // Priority 6: Code identifier heuristics — "Explain Node" → CLASS
        Optional<QueryIntent> identifierIntent = classifyByIdentifier(query);
        if (identifierIntent.isPresent()) {
            log.info("Intent classified as {} (identifier heuristic) for query: '{}'",
                    identifierIntent.get(), query);
            return identifierIntent.get();
        }

        // Priority 7: Broad architecture/overview signals
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
