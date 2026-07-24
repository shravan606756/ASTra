package com.shravan.jcode_intelligence.llm;

import com.shravan.jcode_intelligence.dto.response.ChunkResponse;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class ResponseFormatter {

    public List<ChunkResponse> formatChunks(List<Document> documents) {
        List<ChunkResponse> responses = new ArrayList<>();
        if (documents == null) return responses;

        for (Document doc : documents) {
            Map<String, Object> meta = doc.getMetadata();
            ChunkResponse response = new ChunkResponse(
                    (String) meta.getOrDefault("type", ""),
                    (String) meta.getOrDefault("repositoryId", "default"),
                    (String) meta.getOrDefault("packageName", ""),
                    (String) meta.getOrDefault("className", ""),
                    (String) meta.getOrDefault("elementName", ""),
                    (String) meta.getOrDefault("signature", ""),
                    (String) meta.getOrDefault("filePath", ""),
                    meta.get("startLine") instanceof Number ? ((Number) meta.get("startLine")).intValue() : 0,
                    meta.get("endLine") instanceof Number ? ((Number) meta.get("endLine")).intValue() : 0,
                    doc.getText()
            );
            responses.add(response);
        }
        return responses;
    }
}

