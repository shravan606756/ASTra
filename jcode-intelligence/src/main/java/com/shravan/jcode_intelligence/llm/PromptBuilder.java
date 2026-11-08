package com.shravan.jcode_intelligence.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.shravan.jcode_intelligence.dto.request.ChatMode;
import com.shravan.jcode_intelligence.model.QueryIntent;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    // ── Intent-Aware Entry Points ─────────────────────────────

    /**
     * Builds a prompt context result containing the assembled prompt string,
     * the budgeted documents, and all omitted documents. Architecture intent further
     * filters and deduplicates the budgeted documents for source attribution and queries
     * repository statistics; other intents format all budgeted documents.
     * The character budget is an estimate, not a limit on the final prompt length.
     *
     * @param query            the user query; null is rendered as an empty string
     * @param contextDocuments candidate documents in priority order; null means no context;
     *                         entries must be non-null
     * @param repositoryId     target repository; null or blank uses the first budgeted
     *                         document's repository metadata, then defaults to "default"
     * @param intent           the classified query intent
     * @return the prompt context result aligning prompt text with supplied context
     * @throws NullPointerException if intent is null or a candidate document is null
     */
    public PromptContextResult buildPromptContext(String query, List<Document> contextDocuments,
                                                  String repositoryId, QueryIntent intent) {
        String templateName = router.route(intent);
        String rawTemplate = templateLoader.getTemplate(templateName);

        BudgetResult budgetResult = applyContextBudget(contextDocuments);
        String effectiveRepoId = resolveRepositoryId(repositoryId, budgetResult.budgetedDocuments());

        String contextText;
        List<Document> suppliedDocuments;
        List<Document> omittedDocuments;

        if (intent == QueryIntent.ARCHITECTURE) {
            com.shravan.jcode_intelligence.model.ArchitectureContext archContext =
                    architectureContextBuilder.buildBudgetedContext(effectiveRepoId, budgetResult.budgetedDocuments());
            contextText = architectureAnalyzer.analyzeAndFormat(archContext);
            suppliedDocuments = (archContext != null) ? archContext.toFlatDocumentList() : List.of();

            // Track documents actually excluded during architecture formatting (e.g., FIELD, METHOD_FRAGMENT)
            Set<Document> suppliedSet = Collections.newSetFromMap(new IdentityHashMap<>());
            suppliedSet.addAll(suppliedDocuments);

            omittedDocuments = new ArrayList<>();
            if (contextDocuments != null) {
                for (Document doc : contextDocuments) {
                    if (!suppliedSet.contains(doc)) {
                        omittedDocuments.add(doc);
                    }
                }
            }
        } else {
            suppliedDocuments = budgetResult.budgetedDocuments();
            omittedDocuments = budgetResult.omittedDocuments();
            contextText = formatStructuredContext(suppliedDocuments);
        }

        String promptText = rawTemplate
                .replace("{{question}}", query != null ? query : "")
                .replace("{{context}}", contextText)
                .replace("{{repository}}", effectiveRepoId);

        return new PromptContextResult(promptText, suppliedDocuments, omittedDocuments);
    }

    /**
     * Builds a prompt context result using a specific {@link ChatMode}.
     * Architecture and project summary modes filter and deduplicate budgeted documents
     * for source attribution and query repository statistics. Other modes format all
     * budgeted documents, even when automatic routing selects an architecture template.
     * The character budget is an estimate, not a limit on the final prompt length.
     *
     * @param query            the user query; null is rendered as an empty string
     * @param contextDocuments candidate documents in priority order; null means no context;
     *                         entries must be non-null
     * @param repositoryId     target repository; null or blank uses the first budgeted
     *                         document's repository metadata, then defaults to "default"
     * @param mode             the chat mode; null or AUTO selects a template from query text
     * @return the prompt context result aligning prompt text with supplied context
     * @throws NullPointerException if a candidate document is null
     */
    public PromptContextResult buildPromptContext(String query, List<Document> contextDocuments,
                                                  String repositoryId, ChatMode mode) {
        String templateName;
        if (mode == null || mode == ChatMode.AUTO) {
            templateName = router.route(query);
        } else {
            templateName = getTemplateForMode(mode);
        }

        String rawTemplate = templateLoader.getTemplate(templateName);

        BudgetResult budgetResult = applyContextBudget(contextDocuments);
        String effectiveRepoId = resolveRepositoryId(repositoryId, budgetResult.budgetedDocuments());

        String contextText;
        List<Document> suppliedDocuments;
        List<Document> omittedDocuments;

        if (mode == ChatMode.ARCHITECTURE || mode == ChatMode.PROJECT_SUMMARY) {
            com.shravan.jcode_intelligence.model.ArchitectureContext archContext =
                    architectureContextBuilder.buildBudgetedContext(effectiveRepoId, budgetResult.budgetedDocuments());
            contextText = architectureAnalyzer.analyzeAndFormat(archContext);
            suppliedDocuments = (archContext != null) ? archContext.toFlatDocumentList() : List.of();

            Set<Document> suppliedSet = Collections.newSetFromMap(new IdentityHashMap<>());
            suppliedSet.addAll(suppliedDocuments);

            omittedDocuments = new ArrayList<>();
            if (contextDocuments != null) {
                for (Document doc : contextDocuments) {
                    if (!suppliedSet.contains(doc)) {
                        omittedDocuments.add(doc);
                    }
                }
            }
        } else {
            suppliedDocuments = budgetResult.budgetedDocuments();
            omittedDocuments = budgetResult.omittedDocuments();
            contextText = formatStructuredContext(suppliedDocuments);
        }

        String promptText = rawTemplate
                .replace("{{question}}", query != null ? query : "")
                .replace("{{context}}", contextText)
                .replace("{{repository}}", effectiveRepoId);

        return new PromptContextResult(promptText, suppliedDocuments, omittedDocuments);
    }

    /**
     * Builds prompt context with {@link ChatMode#AUTO} and an inferred repository ID.
     *
     * @param query            user query
     * @param contextDocuments candidate documents
     * @return prompt context result
     */
    public PromptContextResult buildPromptContext(String query, List<Document> contextDocuments) {
        return buildPromptContext(query, contextDocuments, null);
    }

    /**
     * Builds prompt context with {@link ChatMode#AUTO}, selecting a template from query text.
     *
     * @param query            user query
     * @param contextDocuments candidate documents
     * @param repositoryId     target repository ID
     * @return prompt context result
     */
    public PromptContextResult buildPromptContext(String query, List<Document> contextDocuments, String repositoryId) {
        return buildPromptContext(query, contextDocuments, repositoryId, ChatMode.AUTO);
    }

    // ── Legacy Entry Points (backward compatibility) ──────────

    /**
     * Builds a prompt string using the classified {@link QueryIntent}.
     *
     * @param query            the user query
     * @param contextDocuments the retrieved documents
     * @param repositoryId     the target repository
     * @param intent           the classified query intent
     * @return the assembled prompt string
     * @deprecated use {@link #buildPromptContext(String, List, String, QueryIntent)} to obtain supplied context
     */
    @Deprecated
    public String buildPrompt(String query, List<Document> contextDocuments,
                               String repositoryId, QueryIntent intent) {
        return buildPromptContext(query, contextDocuments, repositoryId, intent).promptText();
    }

    /**
     * Legacy entry point without repository ID.
     *
     * @param query            the user query
     * @param contextDocuments candidate documents
     * @return prompt text
     * @deprecated use {@link #buildPromptContext(String, List)}
     */
    @Deprecated
    public String buildPrompt(String query, List<Document> contextDocuments) {
        return buildPromptContext(query, contextDocuments).promptText();
    }

    /**
     * Legacy entry point with repository ID.
     *
     * @param query            the user query
     * @param contextDocuments candidate documents
     * @param repositoryId     target repository ID
     * @return prompt text
     * @deprecated use {@link #buildPromptContext(String, List, String)}
     */
    @Deprecated
    public String buildPrompt(String query, List<Document> contextDocuments, String repositoryId) {
        return buildPromptContext(query, contextDocuments, repositoryId).promptText();
    }

    /**
     * Legacy entry point with chat mode.
     *
     * @param query            the user query
     * @param contextDocuments candidate documents
     * @param repositoryId     target repository ID
     * @param mode             chat mode
     * @return prompt text
     * @deprecated use {@link #buildPromptContext(String, List, String, ChatMode)}
     */
    @Deprecated
    public String buildPrompt(String query, List<Document> contextDocuments, String repositoryId, ChatMode mode) {
        return buildPromptContext(query, contextDocuments, repositoryId, mode).promptText();
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

    /**
     * Appends a formatted Markdown document block with metadata headers and source code.
     *
     * @param sb  target string builder
     * @param doc document to append
     */
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

    /**
     * Resolves the template resource filename corresponding to the provided chat mode.
     *
     * @param mode chat mode
     * @return template filename
     */
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

    /**
     * Resolves the effective repository ID from explicit parameter or document metadata.
     *
     * @param repositoryId     explicit repository ID (nullable)
     * @param contextDocuments list of context documents (nullable)
     * @return effective repository ID
     */
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

    /**
     * Internal container capturing the partitioned budgeted and omitted document subsets.
     *
     * @param budgetedDocuments documents admitted within budget limits
     * @param omittedDocuments  documents dropped due to budget constraints
     */
    private record BudgetResult(List<Document> budgetedDocuments, List<Document> omittedDocuments) {}

    /**
     * Admits a prefix of candidate documents in input order under the configured count
     * limit and estimated character budget, clamped to zero after reserving response space.
     * A document that exactly fills the character budget is admitted; the first document
     * that exceeds either limit and every subsequent document are omitted, even if a later
     * document would fit. The estimate includes text and fixed framing overhead, not the
     * complete rendered prompt.
     *
     * @param documents candidate documents; null or empty yields two empty lists
     * @return admitted prefix and omitted remainder, each preserving input order
     */
    private BudgetResult applyContextBudget(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return new BudgetResult(List.of(), List.of());
        }

        int availableBudget = Math.max(0, maxContextSize - reservedResponseSize);
        int currentSize = 0;
        int includedCount = 0;

        List<Document> budgetedDocuments = new ArrayList<>();
        List<Document> omittedDocuments = new ArrayList<>();
        boolean budgetExhausted = false;

        for (Document doc : documents) {
            if (budgetExhausted) {
                omittedDocuments.add(doc);
                continue;
            }

            if (includedCount >= maxContextDocuments) {
                log.info("Context budget reached (max documents: {}). Remaining {} documents omitted.", 
                         maxContextDocuments, documents.size() - includedCount);
                budgetExhausted = true;
                omittedDocuments.add(doc);
                continue;
            }

            int docSize = calculateSize(doc);
            if (currentSize + docSize > availableBudget) {
                log.info("Context budget reached (size limit: {}/{}). Remaining {} documents omitted.", 
                         currentSize + docSize, availableBudget, documents.size() - includedCount);
                budgetExhausted = true;
                omittedDocuments.add(doc);
                continue;
            }

            budgetedDocuments.add(doc);
            currentSize += docSize;
            includedCount++;
        }

        log.debug("PROMPT_BUDGET_DIAGNOSTICS | inputChunks(reranked)={} | budgetedChunks={} | estimatedPromptSize={} chars | maxAllowedSize={}",
                 documents.size(), includedCount, currentSize, availableBudget);

        log.info("Budget summary - Retrieved: {}, Included: {}, Excluded: {}, Budget usage: {}/{}",
                 documents.size(), includedCount, omittedDocuments.size(), currentSize, availableBudget);

        return new BudgetResult(budgetedDocuments, omittedDocuments);
    }

    /**
     * Estimates document character length including block markdown framing overhead.
     *
     * @param doc document to measure
     * @return estimated character count
     */
    private int calculateSize(Document doc) {
        if (doc == null || doc.getText() == null) {
            return 0;
        }
        return doc.getText().length() + 150;
    }
}
