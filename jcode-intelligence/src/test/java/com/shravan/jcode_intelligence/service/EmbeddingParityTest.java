package com.shravan.jcode_intelligence.service;

import com.shravan.jcode_intelligence.model.EmbeddedRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests verifying embedding parity, Document content preservation, metadata immutability,
 * and deterministic identifier generation in accordance with Issue #47.
 */
class EmbeddingParityTest {

    @Test
    @DisplayName("EmbeddedRecord faithfully retains Document text and metadata attributes")
    void testEmbeddedRecordRetainsDocumentAttributes() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("type", "METHOD");
        metadata.put("repositoryId", "ASTra");
        metadata.put("className", "OrderService");
        metadata.put("elementName", "processOrder");
        metadata.put("chunkId", "CHUNK_123");
        metadata.put("startLine", 10);
        metadata.put("endLine", 35);

        Document doc = new Document("public void processOrder() { execute(); }", metadata);

        String recordId = EmbeddedRecord.generateDeterministicId(
                (String) doc.getMetadata().get("repositoryId"),
                (String) doc.getMetadata().get("chunkId"),
                doc.getText()
        );

        float[] embedding = new float[]{0.25f, -0.75f, 0.5f};

        EmbeddedRecord record = new EmbeddedRecord(
                recordId,
                "job-1",
                "ASTra",
                doc.getText(),
                doc.getMetadata(),
                embedding,
                new CompletableFuture<>()
        );

        assertEquals(doc.getText(), record.content(), "Content must exactly match Document text");
        assertEquals("OrderService", record.metadata().get("className"));
        assertEquals("processOrder", record.metadata().get("elementName"));
        assertEquals(10, record.metadata().get("startLine"));
        assertEquals(35, record.metadata().get("endLine"));
        assertArrayEquals(embedding, record.embedding());

        // Verify metadata immutability
        assertThrows(UnsupportedOperationException.class, () ->
                record.metadata().put("newKey", "newValue"), "Metadata map must be immutable");
    }

    @Test
    @DisplayName("Deterministic record ID generation conforms to RFC-4122 type 3 UUID format")
    void testDeterministicIdFormat() {
        String id = EmbeddedRecord.generateDeterministicId("repo-astra", "CHUNK_ABC", "code");
        UUID uuid = UUID.fromString(id);
        assertEquals(3, uuid.version(), "Must be an RFC-4122 version 3 (name-based MD5) UUID");
    }
}

