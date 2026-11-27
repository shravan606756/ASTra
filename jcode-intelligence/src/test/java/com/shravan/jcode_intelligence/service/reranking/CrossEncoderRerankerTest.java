package com.shravan.jcode_intelligence.service.reranking;

import com.shravan.jcode_intelligence.config.IntentRetrievalConfig;
import com.shravan.jcode_intelligence.model.QueryIntent;
import com.shravan.jcode_intelligence.service.RetrievalReranker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class CrossEncoderRerankerTest {

    private final IntentRetrievalConfig config = new IntentRetrievalConfig();
    private final RetrievalReranker heuristicReranker = new RetrievalReranker(config);

    @Test
    @DisplayName("name() returns 'cross-encoder'")
    void testName() {
        CrossEncoderReranker reranker = new CrossEncoderReranker(heuristicReranker);
        assertEquals("cross-encoder", reranker.name());
        reranker.close();
    }

    @Test
    @DisplayName("formatChunkText correctly prefixes class, element, and signature metadata")
    void testFormatChunkText() {
        CrossEncoderReranker reranker = new CrossEncoderReranker(heuristicReranker);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("className", "CompilationUnit");
        metadata.put("elementName", "getImports");
        metadata.put("signature", "public NodeList<ImportDeclaration> getImports()");
        Document doc = Document.builder()
                .id("doc-1")
                .text("return this.imports;")
                .metadata(metadata)
                .build();

        String formatted = reranker.formatChunkText(doc);
        assertTrue(formatted.contains("Class: CompilationUnit"));
        assertTrue(formatted.contains("Element: getImports"));
        assertTrue(formatted.contains("Signature: public NodeList<ImportDeclaration> getImports()"));
        assertTrue(formatted.contains("return this.imports;"));
        reranker.close();
    }

    @Test
    @DisplayName("formatChunkText handles missing metadata gracefully")
    void testFormatChunkTextMissingMetadata() {
        CrossEncoderReranker reranker = new CrossEncoderReranker(heuristicReranker);

        Document doc = Document.builder()
                .id("doc-2")
                .text("plain text chunk")
                .metadata(Collections.emptyMap())
                .build();

        String formatted = reranker.formatChunkText(doc);
        assertEquals("plain text chunk", formatted);
        reranker.close();
    }

    @Test
    @DisplayName("rerank falls back to heuristic order when model file does not exist")
    void testFallbackWhenModelMissing() {
        CrossEncoderReranker reranker = new CrossEncoderReranker(
                heuristicReranker,
                true,
                "nonexistent-models/model.onnx",
                "nonexistent-models/tokenizer.json",
                512,
                10
        );

        List<Document> docs = new ArrayList<>();
        docs.add(Document.builder().id("1").text("chunk 1").metadata(Map.of("type", "FIELD")).build());
        docs.add(Document.builder().id("2").text("chunk 2").metadata(Map.of("type", "CLASS")).build());
        docs.add(Document.builder().id("3").text("chunk 3").metadata(Map.of("type", "METHOD")).build());

        List<Document> result = reranker.rerank(docs, "find method", QueryIntent.METHOD, 2);

        assertNotNull(result);
        assertEquals(2, result.size());
        // In heuristic METHOD intent, METHOD is ranked top
        assertEquals("3", result.get(0).getId());
        assertFalse(reranker.isInitialized());
        reranker.close();
    }

    @Test
    @DisplayName("rerank falls back to heuristic order when disabled")
    void testFallbackWhenDisabled() {
        CrossEncoderReranker reranker = new CrossEncoderReranker(
                heuristicReranker,
                false,
                "models/ms-marco-MiniLM-L-6-v2.onnx",
                "models/tokenizer.json",
                512,
                10
        );

        List<Document> docs = List.of(
                Document.builder().id("1").text("doc 1").metadata(Map.of("type", "CLASS")).build(),
                Document.builder().id("2").text("doc 2").metadata(Map.of("type", "METHOD")).build()
        );

        List<Document> result = reranker.rerank(docs, "test query", QueryIntent.METHOD, 5);
        assertEquals(2, result.size());
        assertEquals("2", result.get(0).getId()); // METHOD preferred
        reranker.close();
    }

    @Test
    @DisplayName("rerank falls back to heuristic order when query is null or blank")
    void testFallbackWhenQueryBlank() {
        CrossEncoderReranker reranker = new CrossEncoderReranker(
                heuristicReranker,
                true,
                "nonexistent.onnx",
                "nonexistent.json",
                512,
                10
        );

        List<Document> docs = List.of(
                Document.builder().id("1").text("doc 1").metadata(Map.of("type", "CLASS")).build()
        );

        List<Document> resultNull = reranker.rerank(docs, null, QueryIntent.CLASS, 5);
        assertEquals(1, resultNull.size());

        List<Document> resultBlank = reranker.rerank(docs, "   ", QueryIntent.CLASS, 5);
        assertEquals(1, resultBlank.size());
        reranker.close();
    }

    @Test
    @DisplayName("close() is safe and idempotent")
    void testCloseIdempotent() {
        CrossEncoderReranker reranker = new CrossEncoderReranker(heuristicReranker);
        assertDoesNotThrow(reranker::close);
        assertDoesNotThrow(reranker::close);
    }
}
