package com.shravan.jcode_intelligence.llm;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * Encapsulates the output of LLM prompt assembly, aligning the rendered
 * prompt template with the exact subset of documents included within the context budget.
 *
 * @param promptText        rendered prompt text supplied directly to the LLM
 * @param suppliedDocuments immutable list of documents actually formatted into the prompt context
 * @param omittedDocuments  immutable list of documents excluded due to budget constraints or intent filtering
 */
public record PromptContextResult(
        String promptText,
        List<Document> suppliedDocuments,
        List<Document> omittedDocuments
) {
    /**
     * Compact constructor establishing immutability and null-safety across document collections.
     */
    public PromptContextResult {
        promptText = (promptText != null) ? promptText : "";
        suppliedDocuments = (suppliedDocuments != null) ? List.copyOf(suppliedDocuments) : List.of();
        omittedDocuments = (omittedDocuments != null) ? List.copyOf(omittedDocuments) : List.of();
    }
}

