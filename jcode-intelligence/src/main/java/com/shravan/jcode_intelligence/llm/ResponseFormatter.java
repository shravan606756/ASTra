package com.shravan.jcode_intelligence.llm;

import com.shravan.jcode_intelligence.dto.response.ChunkResponse;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Formats retrieved and budgeted {@link Document} instances into client-facing
 * {@link ChunkResponse} DTOs with path normalization and coordinate-level deduplication.
 */
@Component
public class ResponseFormatter {

    /**
     * Converts a collection of documents into deduplicated, path-normalized {@link ChunkResponse} records.
     * Preserves granular line coordinates for distinct elements while eliminating exact duplicate coordinates.
     *
     * @param documents list of supplied context documents (nullable)
     * @return deduplicated list of formatted chunk responses
     */
    public List<ChunkResponse> formatChunks(List<Document> documents) {
        List<ChunkResponse> responses = new ArrayList<>();
        if (documents == null || documents.isEmpty()) {
            return responses;
        }

        Set<String> seenKeys = new LinkedHashSet<>();

        for (Document doc : documents) {
            Map<String, Object> meta = doc.getMetadata();
            String type = String.valueOf(meta.getOrDefault("type", ""));
            String repoId = String.valueOf(meta.getOrDefault("repositoryId", "default"));
            String packageName = String.valueOf(meta.getOrDefault("packageName", ""));
            String className = String.valueOf(meta.getOrDefault("className", ""));
            String elementName = String.valueOf(meta.getOrDefault("elementName", ""));
            String signature = String.valueOf(meta.getOrDefault("signature", ""));
            String rawPath = String.valueOf(meta.getOrDefault("filePath", ""));
            String normalizedPath = normalizeFilePath(rawPath);

            int startLine = meta.get("startLine") instanceof Number ? ((Number) meta.get("startLine")).intValue() : 0;
            int endLine = meta.get("endLine") instanceof Number ? ((Number) meta.get("endLine")).intValue() : 0;

            String chunkId = (String) meta.get("chunkId");
            // Coordinate tuple: (repositoryId, normalizedFilePath, startLine, endLine, elementName)
            // aligned with Issue #44. chunkId serves as secondary fallback when coordinate attributes are absent.
            String deduplicationKey = String.format("%s:%s:%d:%d:%s", repoId, normalizedPath, startLine, endLine, elementName);
            if (normalizedPath.isEmpty() && startLine == 0 && endLine == 0 && elementName.isEmpty() && chunkId != null && !chunkId.isBlank()) {
                deduplicationKey = String.format("%s:id:%s", repoId, chunkId);
            }

            if (seenKeys.add(deduplicationKey)) {
                ChunkResponse response = new ChunkResponse(
                        type,
                        repoId,
                        packageName,
                        className,
                        elementName,
                        signature,
                        normalizedPath,
                        startLine,
                        endLine,
                        doc.getText()
                );
                responses.add(response);
            }
        }
        return responses;
    }

    /**
     * Normalizes OS-dependent file path separators to consistent forward slashes ('/').
     *
     * @param rawPath raw file path string (nullable)
     * @return normalized forward-slash path, or empty string if input was null/blank
     */
    public String normalizeFilePath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return "";
        }
        return rawPath.trim().replace('\\', '/');
    }
}
