package com.shravan.jcode_intelligence.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.shravan.jcode_intelligence.dto.request.ChatMode;
import com.shravan.jcode_intelligence.model.QueryIntent;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds LLM prompts by combining intent-specific templates with
 * structured, hierarchically-organized code context.
 *
 * <p>Context is organized into sections:
 * <pre>
 *   === REPOSITORY OVERVIEW ===       (PACKAGE chunks)
 *   === RELEVANT CLASSES ===          (CLASS + INTERFACE + ENUM + RECORD chunks)
 *   === RELEVANT METHODS ===          (METHOD + CONSTRUCTOR + METHOD_FRAGMENT chunks)
 *   === SUPPORTING FIELDS ===         (FIELD chunks — only if present)
 * </pre>
 */
@Component
public class PromptBuilder {

    private static final Logger log = LoggerFactory.getLogger(PromptBuilder.class);

    private final PromptRouter router;
    private final PromptTemplateLoader templateLoader;
    private final com.shravan.jcode_intelligence.service.ArchitectureContextBuilder architectureContextBuilder;
    private final com.shravan.jcode_intelligence.service.ArchitectureAnalyzer architectureAnalyzer;

    @Value("${astra.prompt.max-context-documents:20}")
    private int maxContextDocuments = 20;

    @Value("${astra.prompt.max-context-size:18000}")
    private int maxContextSize = 18000;

    @Value("${astra.prompt.reserved-response-size:2000}")
    private int reservedResponseSize = 2000;

    public PromptBuilder(PromptRouter router,
                         PromptTemplateLoader templateLoader,
                         com.shravan.jcode_intelligence.service.ArchitectureContextBuilder architectureContextBuilder,
                         com.shravan.jcode_intelligence.service.ArchitectureAnalyzer architectureAnalyzer) {
        this.router = router;
        this.templateLoader = templateLoader;
        this.architectureContextBuilder = architectureContextBuilder;
        this.architectureAnalyzer = architectureAnalyzer;
    }

    // ── Intent-Aware Entry Point ──────────────────────────────

    /**
     * Builds a prompt using the classified {@link QueryIntent} for template selection
     * and structured context formatting.
     *
     * @param query            the user query
     * @param contextDocuments the retrieved documents
     * @param repositoryId     the target repository
     * @param intent           the classified query intent
     * @return the assembled prompt string
     */
    public String buildPrompt(String query, List<Document> contextDocuments,
                               String repositoryId, QueryIntent intent) {
        String templateName = router.route(intent);
        String rawTemplate = templateLoader.getTemplate(templateName);

        String contextText;
        if (intent == QueryIntent.ARCHITECTURE) {
            String effectiveRepoId = resolveRepositoryId(repositoryId, contextDocuments);
            com.shravan.jcode_intelligence.model.ArchitectureContext archContext =
                    architectureContextBuilder.buildContext(effectiveRepoId);
            contextText = architectureAnalyzer.analyzeAndFormat(archContext);
        } else {
            List<Document> budgetedDocuments = applyContextBudget(contextDocuments);
            contextText = formatStructuredContext(budgetedDocuments);
        }

        String effectiveRepoId = resolveRepositoryId(repositoryId, contextDocuments);

        return rawTemplate
                .replace("{{question}}", query != null ? query : "")
                .replace("{{context}}", contextText)
                .replace("{{repository}}", effectiveRepoId);
    }

    // ── Legacy Entry Points (backward compatibility) ──────────

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
        String contextText = formatStructuredContext(budgetedDocuments);

        String effectiveRepoId = resolveRepositoryId(repositoryId, contextDocuments);

        return rawTemplate
                .replace("{{question}}", query != null ? query : "")
                .replace("{{context}}", contextText)
                .replace("{{repository}}", effectiveRepoId);
    }

    // ── Structured Context Formatting ─────────────────────────

    /**
     * Formats documents into hierarchically organized sections.
     * Separates PACKAGE → CLASS/INTERFACE → METHOD → FIELD for optimal LLM comprehension.
     */
    @SuppressWarnings("unchecked")
    private String formatStructuredContext(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            log.warn("No documents available for prompt context");
            return "No relevant code chunks were found in the indexed repository.";
        }

        // Categorize documents by type
        List<Document> packageDocs = new ArrayList<>();
        List<Document> classDocs = new ArrayList<>();   // CLASS + INTERFACE + ENUM + RECORD
        List<Document> methodDocs = new ArrayList<>();   // METHOD + CONSTRUCTOR + METHOD_FRAGMENT
        List<Document> fieldDocs = new ArrayList<>();

        for (Document doc : documents) {
            String type = String.valueOf(doc.getMetadata().getOrDefault("type", ""));
            switch (type) {
                case "PACKAGE" -> packageDocs.add(doc);
                case "CLASS", "INTERFACE", "ENUM", "RECORD" -> classDocs.add(doc);
                case "METHOD", "CONSTRUCTOR", "METHOD_FRAGMENT" -> methodDocs.add(doc);
                case "FIELD" -> fieldDocs.add(doc);
                default -> methodDocs.add(doc); // Fallback
            }
        }

        StringBuilder sb = new StringBuilder();

        if (!packageDocs.isEmpty()) {
            sb.append("=== REPOSITORY OVERVIEW ===\n\n");
            for (Document doc : packageDocs) {
                appendDocumentBlock(sb, doc);
            }
        }

        if (!classDocs.isEmpty()) {
            sb.append("=== RELEVANT CLASSES ===\n\n");
            for (Document doc : classDocs) {
                appendDocumentBlock(sb, doc);
            }
        }

        if (!methodDocs.isEmpty()) {
            sb.append("=== RELEVANT METHODS ===\n\n");
            for (Document doc : methodDocs) {
                appendDocumentBlock(sb, doc);
            }
        }

        if (!fieldDocs.isEmpty()) {
            sb.append("=== SUPPORTING FIELDS ===\n\n");
            for (Document doc : fieldDocs) {
                appendDocumentBlock(sb, doc);
            }
        }

        return sb.toString().trim();
    }

    @SuppressWarnings("unchecked")
    private void appendDocumentBlock(StringBuilder sb, Document doc) {
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

        Object parent = meta.get("parentChunkId");
        if (parent != null) {
            sb.append(String.format("Parent Chunk: %s\n", parent));
        }
        Object superClass = meta.get("superClass");
        if (superClass != null) {
            sb.append(String.format("SuperClass: %s\n", superClass));
        }
        Object interfaces = meta.get("interfaces");
        if (interfaces != null) {
            sb.append(String.format("Interfaces: %s\n", interfaces));
        }
        Object rels = meta.get("relationships");
        if (rels instanceof Map<?, ?> relMap && !relMap.isEmpty()) {
            sb.append(String.format("Relationships: %s\n", relMap));
        }

        Object signature = meta.get("signature");
        if (signature != null && !signature.toString().isBlank()) {
            sb.append(String.format("Signature: %s\n", signature));
        }

        sb.append("Source Code:\n```java\n");
        sb.append(doc.getText());
        sb.append("\n```\n\n");
    }

    // ── Helpers ───────────────────────────────────────────────

    private String getTemplateForMode(ChatMode mode) {
        return switch (mode) {
            case QUESTION_ANSWER -> "answer-question.st";
            case EXPLAIN_CLASS -> "explain-class.st";
            case EXPLAIN_METHOD -> "explain-method.st";
            case ARCHITECTURE -> "architecture.st";
            case PROJECT_SUMMARY -> "summarize-project.st";
            case WORKFLOW -> "workflow.st";
            case RELATIONSHIP -> "relationship.st";
            case SEARCH -> "search.st";
            case DESIGN -> "design.st";
            default -> throw new IllegalArgumentException("Unsupported chat mode: " + mode);
        };
    }

    private String resolveRepositoryId(String repositoryId, List<Document> contextDocuments) {
        String effectiveRepoId = repositoryId;
        if ((effectiveRepoId == null || effectiveRepoId.isBlank()) && contextDocuments != null && !contextDocuments.isEmpty()) {
            effectiveRepoId = String.valueOf(contextDocuments.get(0).getMetadata().getOrDefault("repositoryId", "default"));
        }
        if (effectiveRepoId == null || effectiveRepoId.isBlank()) {
            effectiveRepoId = "default";
        }
        return effectiveRepoId;
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
        return doc.getText().length() + 150;
    }
}
