package com.shravan.jcode_intelligence.llm;

import com.shravan.jcode_intelligence.dto.response.ChunkResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ResponseFormatterTest {

    private final ResponseFormatter formatter = new ResponseFormatter();

    @Test
    @DisplayName("Normalizes Windows path backslashes to forward slashes")
    void testNormalizeFilePath() {
        assertEquals("src/main/java/Foo.java", formatter.normalizeFilePath("src\\main\\java\\Foo.java"));
        assertEquals("com/example/Bar.java", formatter.normalizeFilePath("com/example/Bar.java"));
        assertEquals("", formatter.normalizeFilePath(null));
        assertEquals("", formatter.normalizeFilePath("   "));
    }

    @Test
    @DisplayName("Deduplicates duplicate chunk coordinates while preserving order")
    void testDeduplicatesIdenticalCoordinates() {
        Map<String, Object> meta1 = new HashMap<>();
        meta1.put("type", "METHOD");
        meta1.put("repositoryId", "repo-1");
        meta1.put("filePath", "src\\Foo.java");
        meta1.put("startLine", 10);
        meta1.put("endLine", 30);
        meta1.put("elementName", "doWork");
        meta1.put("chunkId", "CHUNK_1");

        Map<String, Object> meta2 = new HashMap<>(); // Exact duplicate
        meta2.put("type", "METHOD");
        meta2.put("repositoryId", "repo-1");
        meta2.put("filePath", "src/Foo.java");
        meta2.put("startLine", 10);
        meta2.put("endLine", 30);
        meta2.put("elementName", "doWork");
        meta2.put("chunkId", "CHUNK_1");

        Document doc1 = new Document("void doWork() {}", meta1);
        Document doc2 = new Document("void doWork() {}", meta2);

        List<ChunkResponse> result = formatter.formatChunks(List.of(doc1, doc2));

        assertEquals(1, result.size());
        assertEquals("src/Foo.java", result.get(0).getFilePath());
        assertEquals("doWork", result.get(0).getElementName());
    }

    @Test
    @DisplayName("Deduplicates two documents with identical coordinates and different chunkIds, emitting exactly one entry")
    void testDeduplicatesIdenticalCoordinatesWithDifferentChunkIds() {
        Map<String, Object> meta1 = new HashMap<>();
        meta1.put("type", "METHOD");
        meta1.put("repositoryId", "repo-1");
        meta1.put("filePath", "src/Foo.java");
        meta1.put("startLine", 10);
        meta1.put("endLine", 30);
        meta1.put("elementName", "doWork");
        meta1.put("chunkId", "CHUNK_ALPHA");

        Map<String, Object> meta2 = new HashMap<>();
        meta2.put("type", "METHOD");
        meta2.put("repositoryId", "repo-1");
        meta2.put("filePath", "src/Foo.java");
        meta2.put("startLine", 10);
        meta2.put("endLine", 30);
        meta2.put("elementName", "doWork");
        meta2.put("chunkId", "CHUNK_BETA"); // Different chunkId

        Document doc1 = new Document("void doWork() {}", meta1);
        Document doc2 = new Document("void doWork() {}", meta2);

        List<ChunkResponse> result = formatter.formatChunks(List.of(doc1, doc2));

        assertEquals(1, result.size(), "Documents with identical coordinates must emit exactly one entry even with different chunkIds");
        assertEquals("src/Foo.java", result.get(0).getFilePath());
        assertEquals("doWork", result.get(0).getElementName());
    }

    @Test
    @DisplayName("Preserves distinct method coordinate ranges within the same source file")
    void testPreservesDistinctRangesInSameFile() {
        Map<String, Object> m1 = new HashMap<>();
        m1.put("type", "METHOD");
        m1.put("repositoryId", "repo-1");
        m1.put("filePath", "src/Service.java");
        m1.put("startLine", 10);
        m1.put("endLine", 25);
        m1.put("elementName", "methodA");
        m1.put("chunkId", "M_A");

        Map<String, Object> m2 = new HashMap<>();
        m2.put("type", "METHOD");
        m2.put("repositoryId", "repo-1");
        m2.put("filePath", "src/Service.java");
        m2.put("startLine", 30);
        m2.put("endLine", 50);
        m2.put("elementName", "methodB");
        m2.put("chunkId", "M_B");

        Document doc1 = new Document("void methodA() {}", m1);
        Document doc2 = new Document("void methodB() {}", m2);

        List<ChunkResponse> result = formatter.formatChunks(List.of(doc1, doc2));

        assertEquals(2, result.size(), "Both distinct methods in the same file must be preserved");
        assertEquals("methodA", result.get(0).getElementName());
        assertEquals(10, result.get(0).getStartLine());
        assertEquals("methodB", result.get(1).getElementName());
        assertEquals(30, result.get(1).getStartLine());
    }

    @Test
    @DisplayName("Gracefully formats synthetic PACKAGE chunks without file paths")
    void testFormatsSyntheticPackageChunk() {
        Map<String, Object> pkgMeta = new HashMap<>();
        pkgMeta.put("type", "PACKAGE");
        pkgMeta.put("repositoryId", "repo-1");
        pkgMeta.put("packageName", "com.example.service");
        pkgMeta.put("chunkId", "PKG_1");

        Document pkgDoc = new Document("package com.example.service overview", pkgMeta);

        List<ChunkResponse> result = formatter.formatChunks(List.of(pkgDoc));

        assertEquals(1, result.size());
        assertEquals("PACKAGE", result.get(0).getType());
        assertEquals("", result.get(0).getFilePath());
        assertEquals(0, result.get(0).getStartLine());
        assertEquals(0, result.get(0).getEndLine());
    }

    @Test
    @DisplayName("Handles null and empty document collections safely")
    void testHandlesNullAndEmpty() {
        assertTrue(formatter.formatChunks(null).isEmpty());
        assertTrue(formatter.formatChunks(List.of()).isEmpty());
    }
}

