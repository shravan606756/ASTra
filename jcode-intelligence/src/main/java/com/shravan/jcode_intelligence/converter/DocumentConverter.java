package com.shravan.jcode_intelligence.converter;

import com.shravan.jcode_intelligence.model.CodeChunk;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class DocumentConverter {

    public Document convert(CodeChunk chunk) {

        Map<String, Object> metadata = new HashMap<>();

        metadata.put("type", chunk.getType());
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

        System.out.println("\n==================================");
        System.out.println("Converting Chunk");
        System.out.println("==================================");

        System.out.println("Element : " + chunk.getElementName());

        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            System.out.printf("%-12s : %s%n", entry.getKey(), entry.getValue());

            if (entry.getValue() == null) {
                System.out.println(">>> NULL FOUND IN METADATA : " + entry.getKey());
            }
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