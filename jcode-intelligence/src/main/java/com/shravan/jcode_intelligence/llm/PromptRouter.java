package com.shravan.jcode_intelligence.llm;

import com.shravan.jcode_intelligence.model.QueryIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Routes user queries to the appropriate prompt template.
 *
 * <p>When a {@link QueryIntent} is available, uses the intent's built-in template mapping.
 * When operating in legacy mode (no intent), falls back to pattern-based routing.
 */
@Component
public class PromptRouter {

    private static final Logger log = LoggerFactory.getLogger(PromptRouter.class);

    private static final Pattern CLASS_EXPLAIN_PATTERN = Pattern.compile("(?i)^(explain|what is|describe)\\s+[A-Z]\\w*$");

    /**
     * Routes based on a classified {@link QueryIntent}.
     * This is the primary routing path in the intent-aware pipeline.
     */
    public String route(QueryIntent intent) {
        String template = intent.templateName();
        log.info("Routed intent {} to template: {}", intent, template);
        return template;
    }

    /**
     * Legacy routing based on query text analysis.
     * Used when no intent classification is available (fallback for AUTO mode).
     */
    public String route(String query) {
        if (query == null || query.isBlank()) {
            return "answer-question.st";
        }

        String lower = query.toLowerCase(Locale.ROOT).trim();

        if (query.contains("()") || lower.contains("method") || lower.contains("function")) {
            log.info("Routed query to template: explain-method.st");
            return "explain-method.st";
        }

        if (lower.contains("workflow") || lower.contains("how does") || lower.contains("step by step")
                || lower.contains("end to end") || lower.contains("data flow") || lower.contains("execution flow")) {
            log.info("Routed query to template: workflow.st");
            return "workflow.st";
        }

        if (lower.contains("relationship") || lower.contains("which classes use")
                || lower.contains("who calls") || lower.contains("depends on")
                || lower.contains("dependency") || lower.contains("inheritance")) {
            log.info("Routed query to template: relationship.st");
            return "relationship.st";
        }

        if (lower.contains("design pattern") || lower.contains("abstraction")
                || lower.contains("extension point") || lower.contains("solid")) {
            log.info("Routed query to template: design.st");
            return "design.st";
        }

        if (lower.contains("where is") || lower.contains("find ") || lower.contains("locate")
                || lower.contains("which file") || lower.contains("look up")) {
            log.info("Routed query to template: search.st");
            return "search.st";
        }

        if (lower.contains("architecture") || lower.contains("pipeline") || lower.contains("design")) {
            log.info("Routed query to template: architecture.st");
            return "architecture.st";
        }

        if (lower.contains("summary") || lower.contains("summarize") ||
            lower.contains("overview") || lower.contains("repository")) {
            log.info("Routed query to template: summarize-project.st");
            return "summarize-project.st";
        }

        if (lower.contains("package structure") || lower.contains("package organization")
                || lower.contains("packages")) {
            log.info("Routed query to template: summarize-project.st");
            return "summarize-project.st";
        }

        if (lower.contains("class") || CLASS_EXPLAIN_PATTERN.matcher(query.trim()).matches()) {
            log.info("Routed query to template: explain-class.st");
            return "explain-class.st";
        }

        log.info("Routed query to default template: answer-question.st");
        return "answer-question.st";
    }
}
