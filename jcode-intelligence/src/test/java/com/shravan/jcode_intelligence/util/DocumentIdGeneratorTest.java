package com.shravan.jcode_intelligence.util;

import com.shravan.jcode_intelligence.model.EmbeddedRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DocumentIdGeneratorTest {

    @Test
    @DisplayName("Deterministic ID produces valid RFC-4122 type 3 UUID")
    void testValidRfc4122Type3Uuid() {
        String id = DocumentIdGenerator.generateDeterministicId("repo-1", "chunk-42", "content");
        assertNotNull(id);
        UUID uuid = assertDoesNotThrow(() -> UUID.fromString(id));
        assertEquals(3, uuid.version(), "Must be version 3 name-based UUID");
    }

    @Test
    @DisplayName("Non-blank chunkId takes precedence over content")
    void testChunkIdPrecedence() {
        String id1 = DocumentIdGenerator.generateDeterministicId("repo-1", "chunk-42", "content-A");
        String id2 = DocumentIdGenerator.generateDeterministicId("repo-1", "chunk-42", "content-B");
        assertEquals(id1, id2, "ID must be identical regardless of content when chunkId is non-blank");
    }

    @Test
    @DisplayName("Null or blank chunkId falls back to content")
    void testFallbackToContentWhenChunkIdBlank() {
        String id1 = DocumentIdGenerator.generateDeterministicId("repo-1", null, "content-A");
        String id2 = DocumentIdGenerator.generateDeterministicId("repo-1", "", "content-A");
        String id3 = DocumentIdGenerator.generateDeterministicId("repo-1", "   ", "content-A");
        String id4 = DocumentIdGenerator.generateDeterministicId("repo-1", null, "content-B");

        assertEquals(id1, id2);
        assertEquals(id1, id3);
        assertNotEquals(id1, id4, "Different content must produce different IDs when chunkId is missing");
    }

    @Test
    @DisplayName("Null or blank repositoryId defaults to 'default'")
    void testDefaultRepositoryId() {
        String idDefault = DocumentIdGenerator.generateDeterministicId("default", "chunk-1", "code");
        String idNull = DocumentIdGenerator.generateDeterministicId(null, "chunk-1", "code");
        String idEmpty = DocumentIdGenerator.generateDeterministicId("", "chunk-1", "code");
        String idBlank = DocumentIdGenerator.generateDeterministicId("   ", "chunk-1", "code");

        assertEquals(idDefault, idNull);
        assertEquals(idDefault, idEmpty);
        assertEquals(idDefault, idBlank);
    }

    @Test
    @DisplayName("DocumentIdGenerator and EmbeddedRecord produce identical IDs (parity)")
    void testParityWithEmbeddedRecord() {
        String id1 = DocumentIdGenerator.generateDeterministicId("repo-x", "c-1", "body");
        String id2 = EmbeddedRecord.generateDeterministicId("repo-x", "c-1", "body");
        assertEquals(id1, id2);
    }
}
