package com.shravan.jcode_intelligence.converter;

import com.shravan.jcode_intelligence.model.CodeChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class DocumentConverter {

    private static final Logger log = LoggerFactory.getLogger(DocumentConverter.class);

    public Document convert(CodeChunk chunk) {

        Map<String, Object> metadata = new HashMap<>();

        metadata.put("type", chunk.getType());
        metadata.put("repositoryId", chunk.getRepositoryId() == null || chunk.getRepositoryId().isBlank() ? "default" : chunk.getRepositoryId());
        metadata.put("packageName", chunk.getPackageName());
        metadata.put("className", chunk.getClassName());
        metadata.put("elementName", chunk.getElementName());
        metadata.put("signature",
                chunk.getSignature() == null ? "" : chunk.getSignature());
        metadata.put("filePath", chunk.getFilePath());
        metadata.put("startLine", chunk.getStartLine());
        metadata.put("endLine", chunk.getEndLine());
        metadata.put("language", chunk.getLanguage());
        metadata.put("imports", chunk.getImports());
        metadata.put("annotations", chunk.getAnnotations());
        metadata.put("modifiers", chunk.getModifiers());

        if (log.isDebugEnabled()) {
            log.debug("Converted CodeChunk: [element={}, type={}, repositoryId={}]",
                    chunk.getElementName(), chunk.getType(), chunk.getRepositoryId());
        }

        return new Document(chunk.getContent(), metadata);
    }

    public List<Document> convert(List<CodeChunk> chunks) {

        List<Document> documents = new ArrayList<>();

        for (CodeChunk chunk : chunks) {
            documents.add(convert(chunk));
        }

        return documents;
    }
}