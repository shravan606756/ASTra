package com.shravan.jcode_intelligence.parser;

import com.shravan.jcode_intelligence.exception.EmbeddingException;
import com.shravan.jcode_intelligence.model.CodeChunk;
import com.shravan.jcode_intelligence.parser.spi.ChunkBudgetEstimator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Pre-embedding validation and observability component.
 *
 * <p>Audits document sizes before vector storage and enforces hard budget limits.
 * If any document exceeds the configured embedding budget, this validator logs
 * comprehensive diagnostics and throws an {@link EmbeddingException} before
 * the embedding model can fail.
 */
@Component
public class EmbeddingBudgetValidator {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingBudgetValidator.class);

    private final ChunkBudgetEstimator budgetEstimator;

    public EmbeddingBudgetValidator(ChunkBudgetEstimator budgetEstimator) {
        this.budgetEstimator = budgetEstimator;
    }

    public record DocumentAudit(
            int index,
            String type,
            String className,
            String elementName,
            String filePath,
            int charCount,
            int estimatedTokens,
            String chunkId
    ) {}

    /**
     * Audits all documents and throws an exception if any document exceeds the embedding limit.
     */
    public void validateAndAudit(List<Document> documents, List<CodeChunk> originalChunks) {
        if (documents == null || documents.isEmpty()) {
            return;
        }

        int maxDocumentChars = budgetEstimator.getMaxDocumentBudget();
        List<DocumentAudit> audits = new ArrayList<>();
        DocumentAudit violatingAudit = null;

        for (int i = 0; i < documents.size(); i++) {
            Document doc = documents.get(i);
            Map<String, Object> meta = doc.getMetadata();

            String type = String.valueOf(meta.getOrDefault("type", "UNKNOWN"));
            String className = String.valueOf(meta.getOrDefault("className", "UNKNOWN"));
            String elementName = String.valueOf(meta.getOrDefault("elementName", "UNKNOWN"));
            String filePath = String.valueOf(meta.getOrDefault("filePath", "UNKNOWN"));
            String chunkId = String.valueOf(meta.getOrDefault("chunkId", "UNKNOWN"));

            int chars = doc.getText() != null ? doc.getText().length() : 0;
            int estTokens = chars / 4;

            DocumentAudit audit = new DocumentAudit(i, type, className, elementName, filePath, chars, estTokens, chunkId);
            audits.add(audit);

            if (chars > maxDocumentChars && violatingAudit == null) {
                violatingAudit = audit;
            }
        }

        // Sort by size for diagnostic logging
        audits.sort((a, b) -> Integer.compare(b.charCount(), a.charCount()));

        log.info("=== EMBEDDING BUDGET OBSERVABILITY LOG (TOP 20 LARGEST DOCUMENTS) ===");
        for (int i = 0; i < Math.min(20, audits.size()); i++) {
            DocumentAudit a = audits.get(i);
            log.info(String.format("TOP-%-2d [Index %-4d] %-15s | %-25s | %-25s | chars=%-6d | estTokens=%-5d | path=%s",
                    i + 1, a.index(), a.type(), a.className(), a.elementName(), a.charCount(), a.estimatedTokens(), a.filePath()));
        }
        log.info("==========================================================================================");

        // Fail-fast validation check
        if (violatingAudit != null) {
            String msg = String.format(
                    "PRE-EMBEDDING BUDGET VALIDATION FAILED! Document index %d [%s: %s#%s] size (%d chars / ~%d tokens) exceeds maximum limit of %d chars (~%d tokens). File: %s",
                    violatingAudit.index(),
                    violatingAudit.type(),
                    violatingAudit.className(),
                    violatingAudit.elementName(),
                    violatingAudit.charCount(),
                    violatingAudit.estimatedTokens(),
                    maxDocumentChars,
                    maxDocumentChars / 4,
                    violatingAudit.filePath()
            );
            log.error(msg);
            throw new EmbeddingException(msg);
        }
    }
}
