package com.shravan.jcode_intelligence.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Pattern;

@Component
public class PromptRouter {

    private static final Logger log = LoggerFactory.getLogger(PromptRouter.class);

    private static final Pattern CLASS_EXPLAIN_PATTERN = Pattern.compile("(?i)^(explain|what is|describe)\\s+[A-Z]\\w*$");

    public String route(String query) {
        if (query == null || query.isBlank()) {
            return "answer-question.st";
        }

        String lower = query.toLowerCase(Locale.ROOT).trim();

        if (query.contains("()") || lower.contains("method") || lower.contains("function")) {
            log.info("Routed query to template: explain-method.st");
            return "explain-method.st";
        }

        if (lower.contains("architecture") || lower.contains("workflow") ||
            lower.contains("pipeline") || lower.contains("design")) {
            log.info("Routed query to template: architecture.st");
            return "architecture.st";
        }

        if (lower.contains("summary") || lower.contains("summarize") ||
            lower.contains("overview") || lower.contains("repository")) {
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
