package com.shravan.jcode_intelligence.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.shravan.jcode_intelligence.dto.request.ChatMode;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class PromptBuilder {

    private static final Logger log = LoggerFactory.getLogger(PromptBuilder.class);

    private final PromptRouter router;
    private final PromptTemplateLoader templateLoader;

    @Value("${astra.prompt.max-context-documents:20}")
    private int maxContextDocuments;

    @Value("${astra.prompt.max-context-size:18000}")
    private int maxContextSize;

    @Value("${astra.prompt.reserved-response-size:2000}")
    private int reservedResponseSize;

    public PromptBuilder(PromptRouter router, PromptTemplateLoader templateLoader) {
        this.router = router;
        this.templateLoader = templateLoader;
    }

    public String buildPrompt(String query, List<Document> contextDocuments) {
        return buildPrompt(query, contextDocuments, null);
    }

    public String buildPrompt(String query, List<Document> contextDocuments, String repositoryId) {
        return buildPrompt(query, contextDocuments, repositoryId, ChatMode.AUTO);
    }

    public String buildPrompt(String query, List<Document> contextDocuments, String repositoryId, ChatMode mode) {
        String templateName;
        if (mode == null || mode == ChatMode.AUTO) {
            templateName = router.route(query);
        } else {
            templateName = getTemplateForMode(mode);
        }
        
        String rawTemplate = templateLoader.getTemplate(templateName);

        List<Document> budgetedDocuments = applyContextBudget(contextDocuments);
        String contextText = formatContext(budgetedDocuments);

        String effectiveRepoId = repositoryId;
        if ((effectiveRepoId == null || effectiveRepoId.isBlank()) && contextDocuments != null && !contextDocuments.isEmpty()) {
            effectiveRepoId = String.valueOf(contextDocuments.get(0).getMetadata().getOrDefault("repositoryId", "default"));
        }
        if (effectiveRepoId == null || effectiveRepoId.isBlank()) {
            effectiveRepoId = "default";
        }

        return rawTemplate
                .replace("{{question}}", query != null ? query : "")
                .replace("{{context}}", contextText)
                .replace("{{repository}}", effectiveRepoId);
    }

    private String formatContext(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            log.warn("No documents available for prompt context");
            return "No relevant code chunks were found in the indexed repository.";
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < documents.size(); i++) {
            Document doc = documents.get(i);
            Map<String, Object> meta = doc.getMetadata();

            sb.append(String.format("File: %s (Lines %s-%s)\n",
                    meta.getOrDefault("filePath", "UNKNOWN"),
                    meta.getOrDefault("startLine", "?"),
                    meta.getOrDefault("endLine", "?")));
            sb.append(String.format("Type: %s | Package: %s | Class: %s | Element: %s\n",
                    meta.getOrDefault("type", "UNKNOWN"),
                    meta.getOrDefault("packageName", ""),
                    meta.getOrDefault("className", ""),
                    meta.getOrDefault("elementName", "")));

            Object signature = meta.get("signature");
            if (signature != null && !signature.toString().isBlank()) {
                sb.append(String.format("Signature: %s\n", signature));
            }

            sb.append("Source Code:\n```java\n");
            sb.append(doc.getText());
            sb.append("\n```\n\n");
        }

        return sb.toString().trim();
    }

    private String getTemplateForMode(ChatMode mode) {
        switch (mode) {
            case QUESTION_ANSWER:
                return "answer-question.st";
            case EXPLAIN_CLASS:
                return "explain-class.st";
            case EXPLAIN_METHOD:
                return "explain-method.st";
            case ARCHITECTURE:
                return "architecture.st";
            case PROJECT_SUMMARY:
                return "summarize-project.st";
            default:
                throw new IllegalArgumentException("Unsupported chat mode: " + mode);
        }
    }

    private List<Document> applyContextBudget(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }

        int availableBudget = Math.max(0, maxContextSize - reservedResponseSize);
        int currentSize = 0;
        int includedCount = 0;

        List<Document> budgetedDocuments = new ArrayList<>();

        for (Document doc : documents) {
            if (includedCount >= maxContextDocuments) {
                log.info("Context budget reached (max documents: {}). Remaining {} documents omitted.", 
                         maxContextDocuments, documents.size() - includedCount);
                break;
            }

            int docSize = calculateSize(doc);
            if (currentSize + docSize > availableBudget) {
                log.info("Context budget reached (size limit: {}/{}). Remaining {} documents omitted.", 
                         currentSize + docSize, availableBudget, documents.size() - includedCount);
                break;
            }

            budgetedDocuments.add(doc);
            currentSize += docSize;
            includedCount++;
        }

        log.info("Budget summary - Retrieved: {}, Included: {}, Excluded: {}, Budget usage: {}/{}",
                 documents.size(), includedCount, documents.size() - includedCount, currentSize, availableBudget);

        return budgetedDocuments;
    }

    private int calculateSize(Document doc) {
        if (doc == null || doc.getText() == null) {
            return 0;
        }
        // Approximate overhead of formatting (metadata headers)
        return doc.getText().length() + 150;
    }
}
