package com.shravan.jcode_intelligence.converter;

import com.shravan.jcode_intelligence.model.CodeChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts {@link CodeChunk} instances into Spring AI {@link Document}
 * objects suitable for embedding and storage in vector stores.
 *
 * <p>Ensures that no metadata key with a null value is ever added to a Document,
 * fully satisfying Spring AI's requirement that metadata values must never be null.
 */
@Component
public class DocumentConverter {

    private static final Logger log = LoggerFactory.getLogger(DocumentConverter.class);

    public Document convert(CodeChunk chunk) {

        Map<String, Object> metadata = new HashMap<>();

        // ── Core identity ─────────────────────────────────────
        putIfNotNull(metadata, "type", chunk.getType());
        putIfNotNull(metadata, "repositoryId",
                chunk.getRepositoryId() == null || chunk.getRepositoryId().isBlank()
                        ? "default" : chunk.getRepositoryId());
        putIfNotNull(metadata, "packageName", chunk.getPackageName());
        putIfNotNull(metadata, "className", chunk.getClassName());
        putIfNotNull(metadata, "elementName", chunk.getElementName());
        putIfNotNull(metadata, "signature", chunk.getSignature());
        putIfNotNull(metadata, "filePath", chunk.getFilePath());

        metadata.put("startLine", chunk.getStartLine());
        metadata.put("endLine", chunk.getEndLine());

        putIfNotNull(metadata, "language", chunk.getLanguage());
        putIfNotNull(metadata, "imports", chunk.getImports());
        putIfNotNull(metadata, "annotations", chunk.getAnnotations());
        putIfNotNull(metadata, "modifiers", chunk.getModifiers());

        // ── Hierarchy & Nesting ───────────────────────────────
        putIfNotNull(metadata, "parentChunkId", chunk.getParentChunkId());
        metadata.put("nestingDepth", chunk.getNestingDepth());
        putIfNotNull(metadata, "outerClassName", chunk.getOuterClassName());

        // ── Type-declaration metadata ─────────────────────────
        putIfNotNull(metadata, "javadoc", chunk.getJavadoc());
        putIfNotNull(metadata, "superClass", chunk.getSuperClass());
        putIfNotNull(metadata, "interfaces", chunk.getInterfaces());

        // ── Extensible Relationships ──────────────────────────
        if (chunk.getRelationships() != null && !chunk.getRelationships().isEmpty()) {
            Map<String, List<String>> cleanRels = new HashMap<>();
            for (Map.Entry<String, List<String>> entry : chunk.getRelationships().entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null && !entry.getValue().isEmpty()) {
                    cleanRels.put(entry.getKey(), entry.getValue());
                }
            }
            putIfNotNull(metadata, "relationships", cleanRels);
        }

        // ── Fragment metadata ─────────────────────────────────
        putIfNotNull(metadata, "fragmentIndex", chunk.getFragmentIndex());
        putIfNotNull(metadata, "methodName", chunk.getMethodName());

        // ── Chunk ID for parent-child lookups ─────────────────
        putIfNotNull(metadata, "chunkId", chunk.getId());

        // ── Diagnostics & Sizing ──────────────────────────────
        metadata.put("contentLength", chunk.getContentLength());
        metadata.put("originalElementLength", chunk.getOriginalElementLength());
        metadata.put("summarized", chunk.isSummarized());
        metadata.put("fragmented", chunk.isFragmented());

        if (log.isDebugEnabled()) {
            log.debug("Converted CodeChunk: [element={}, type={}, repositoryId={}, parentChunkId={}]",
                    chunk.getElementName(), chunk.getType(),
                    chunk.getRepositoryId(), chunk.getParentChunkId());
        }

        String content = chunk.getContent() != null ? chunk.getContent() : "";
        return new Document(content, metadata);
    }

    public List<Document> convert(List<CodeChunk> chunks) {

        List<Document> documents = new ArrayList<>();

        if (chunks != null) {
            for (CodeChunk chunk : chunks) {
                if (chunk != null) {
                    documents.add(convert(chunk));
                }
            }
        }

        return documents;
    }

    /**
     * Helper to insert entries into the metadata map ONLY if the value is non-null.
     * Also filters out blank strings and empty collections/maps to keep metadata clean.
     */
    private void putIfNotNull(Map<String, Object> map, String key, Object value) {
        if (value == null) {
            return;
        }

        if (value instanceof String str) {
            if (!str.isBlank()) {
                map.put(key, str);
            }
        } else if (value instanceof Collection<?> col) {
            if (!col.isEmpty()) {
                map.put(key, col);
            }
        } else if (value instanceof Map<?, ?> m) {
            if (!m.isEmpty()) {
                map.put(key, m);
            }
        } else {
            map.put(key, value);
        }
    }
}