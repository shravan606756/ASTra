package com.shravan.jcode_intelligence.sparse.mapper;

import org.apache.lucene.document.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LuceneDocumentMapperTest {

    @Test
    @DisplayName("Maps Spring AI document to Lucene and preserves complete metadata roundtrip")
    void testRoundtripPreservation() {
        LuceneDocumentMapper mapper = new LuceneDocumentMapper();

        Map<String, Object> meta = new HashMap<>();
        meta.put("repositoryId", "repo-123");
        meta.put("chunkId", "chunk-456");
        meta.put("type", "METHOD");
        meta.put("elementName", "findUser");
        meta.put("className", "UserService");
        meta.put("filePath", "src/main/java/UserService.java");
        meta.put("packageName", "com.example.service");
        meta.put("signature", "public User findUser(Long id)");
        meta.put("startLine", 42);
        meta.put("endLine", 55);
        meta.put("modifiers", List.of("public"));
        meta.put("relationships", Map.of("CALLS", List.of("UserRepo.findById")));

        org.springframework.ai.document.Document original =
                new org.springframework.ai.document.Document("doc-id-789", "public User findUser(Long id) { return null; }", meta);

        Document luceneDoc = mapper.toLuceneDocument(original);

        assertNotNull(luceneDoc);
        assertEquals("doc-id-789", luceneDoc.get("id"));
        assertEquals("repo-123", luceneDoc.get("repositoryId"));
        assertEquals("chunk-456", luceneDoc.get("chunkId"));
        assertEquals("METHOD", luceneDoc.get("type"));
        assertEquals("findUser", luceneDoc.get("elementName"));
        assertEquals("UserService", luceneDoc.get("className"));

        org.springframework.ai.document.Document roundtrip = mapper.toSpringAiDocument(luceneDoc, 3.14f);

        assertEquals("doc-id-789", roundtrip.getId());
        assertEquals("public User findUser(Long id) { return null; }", roundtrip.getText());
        assertEquals("repo-123", roundtrip.getMetadata().get("repositoryId"));
        assertEquals("chunk-456", roundtrip.getMetadata().get("chunkId"));
        assertEquals("METHOD", roundtrip.getMetadata().get("type"));
        assertEquals("findUser", roundtrip.getMetadata().get("elementName"));
        assertEquals("UserService", roundtrip.getMetadata().get("className"));
        assertEquals(3.14, (Double) roundtrip.getMetadata().get("score"), 0.001);
        assertEquals(3.14, (Double) roundtrip.getMetadata().get("sparse_score"), 0.001);

        // Verify nested collections preserved without loss
        assertNotNull(roundtrip.getMetadata().get("modifiers"));
        assertNotNull(roundtrip.getMetadata().get("relationships"));
    }
}
