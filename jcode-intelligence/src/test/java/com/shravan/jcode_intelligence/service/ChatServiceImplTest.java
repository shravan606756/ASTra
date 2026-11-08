package com.shravan.jcode_intelligence.service;

import com.shravan.jcode_intelligence.config.IntentRetrievalConfig;
import com.shravan.jcode_intelligence.dto.request.ChatMode;
import com.shravan.jcode_intelligence.dto.request.ChatRequest;
import com.shravan.jcode_intelligence.dto.response.ChatResponse;
import com.shravan.jcode_intelligence.dto.response.ChunkResponse;
import com.shravan.jcode_intelligence.llm.LLMClient;
import com.shravan.jcode_intelligence.llm.PromptBuilder;
import com.shravan.jcode_intelligence.llm.PromptContextResult;
import com.shravan.jcode_intelligence.llm.PromptRouter;
import com.shravan.jcode_intelligence.llm.PromptTemplateLoader;
import com.shravan.jcode_intelligence.llm.ResponseFormatter;
import com.shravan.jcode_intelligence.model.QueryIntent;
import com.shravan.jcode_intelligence.service.impl.ChatServiceImpl;
import com.shravan.jcode_intelligence.service.impl.RetrievalServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ChatServiceImplTest {

    private StubRetrievalService retrievalService;
    private StubPromptBuilder promptBuilder;
    private StubLLMClient llmClient;
    private RecordingResponseFormatter responseFormatter;
    private TestConfig config;
    private ChatServiceImpl chatService;

    @BeforeEach
    void setUp() {
        retrievalService = new StubRetrievalService();
        promptBuilder = new StubPromptBuilder();
        llmClient = new StubLLMClient();
        responseFormatter = new RecordingResponseFormatter();
        config = new TestConfig();

        chatService = new ChatServiceImpl(
                retrievalService,
                promptBuilder,
                llmClient,
                responseFormatter,
                config
        );
    }

    @Test
    @DisplayName("ChatServiceImpl passes exclusively supplied documents to ResponseFormatter, omitting budget drops")
    void testAnswerPassesOnlySuppliedDocumentsToResponseFormatter() {
        Document d1 = new Document("d1", Map.of("chunkId", "D1", "type", "METHOD", "filePath", "src/D1.java"));
        Document d2 = new Document("d2", Map.of("chunkId", "D2", "type", "METHOD", "filePath", "src/D2.java"));
        Document d3 = new Document("d3", Map.of("chunkId", "D3", "type", "METHOD", "filePath", "src/D3.java"));
        Document d4 = new Document("d4", Map.of("chunkId", "D4", "type", "METHOD", "filePath", "src/D4.java"));

        // Retrieved candidate list contains all 4 documents
        retrievalService.documentsToReturn = List.of(d1, d2, d3, d4);

        // Prompt builder budget keeps only d1 and d2
        promptBuilder.resultToReturn = new PromptContextResult(
                "Assembled Prompt",
                List.of(d1, d2),
                List.of(d3, d4)
        );

        ChatRequest request = new ChatRequest("Explain execution flow", 10, "repo-1");
        ChatResponse response = chatService.answer(request);

        assertNotNull(response);
        assertEquals("Generated answer", response.getAnswer());

        // Verify that ResponseFormatter received ONLY supplied documents
        assertNotNull(responseFormatter.receivedDocuments);
        assertEquals(2, responseFormatter.receivedDocuments.size(), "ResponseFormatter must receive only budgeted supplied docs");
        assertTrue(responseFormatter.receivedDocuments.contains(d1));
        assertTrue(responseFormatter.receivedDocuments.contains(d2));
        assertFalse(responseFormatter.receivedDocuments.contains(d3), "Omitted document D3 must not be sent to ResponseFormatter");
        assertFalse(responseFormatter.receivedDocuments.contains(d4), "Omitted document D4 must not be sent to ResponseFormatter");

        assertEquals(2, response.getSources().size());
    }

    @Test
    @DisplayName("Debug metrics: retrievedChunkCount, suppliedChunkCount, and omittedChunkCount populated when enabled")
    void testAnswerDebugMetricsPopulatedWhenDebugEnabled() {
        config.debug = true;

        Document d1 = new Document("d1", Map.of("chunkId", "D1", "type", "METHOD"));
        Document d2 = new Document("d2", Map.of("chunkId", "D2", "type", "METHOD"));
        Document d3 = new Document("d3", Map.of("chunkId", "D3", "type", "METHOD"));

        retrievalService.documentsToReturn = List.of(d1, d2, d3);
        promptBuilder.resultToReturn = new PromptContextResult(
                "Prompt text",
                List.of(d1),
                List.of(d2, d3)
        );

        ChatRequest request = new ChatRequest("Explain flow", 10, "repo-1");
        ChatResponse response = chatService.answer(request);

        assertNotNull(response);
        assertEquals(3, response.getRetrievedChunkCount(), "Total retrieved count before budget");
        assertEquals(1, response.getSuppliedChunkCount(), "Supplied count within budget");
        assertEquals(2, response.getOmittedChunkCount(), "Omitted count dropped by budget");
    }

    @Test
    @DisplayName("Debug metrics remain null when debug mode is disabled")
    void testAnswerDebugMetricsNullWhenDebugDisabled() {
        config.debug = false;

        Document d1 = new Document("d1", Map.of("chunkId", "D1", "type", "METHOD"));
        retrievalService.documentsToReturn = List.of(d1);
        promptBuilder.resultToReturn = new PromptContextResult("Prompt text", List.of(d1), List.of());

        ChatRequest request = new ChatRequest("Explain flow", 10, "repo-1");
        ChatResponse response = chatService.answer(request);

        assertNotNull(response);
        assertNull(response.getRetrievedChunkCount());
        assertNull(response.getSuppliedChunkCount());
        assertNull(response.getOmittedChunkCount());
    }

    // ── Test Doubles ──────────────────────────────────────────

    private static class StubRetrievalService extends RetrievalServiceImpl {
        List<Document> documentsToReturn = List.of();

        public StubRetrievalService() {
            super(null, null, null, null, null);
        }

        @Override
        public QueryIntent classifyIntent(String query, ChatMode mode) {
            return QueryIntent.METHOD;
        }

        @Override
        public List<Document> retrieve(String query, int topK, String repositoryId, ChatMode mode) {
            return documentsToReturn;
        }

        @Override
        public Map<String, Integer> computeChunkTypeCounts(List<Document> docs) {
            return Map.of("METHOD", docs != null ? docs.size() : 0);
        }
    }

    private static class StubPromptBuilder extends PromptBuilder {
        PromptContextResult resultToReturn;

        public StubPromptBuilder() {
            super(new PromptRouter(), new PromptTemplateLoader(new DefaultResourceLoader()), null, null);
        }

        @Override
        public PromptContextResult buildPromptContext(String query, List<Document> contextDocuments,
                                                      String repositoryId, QueryIntent intent) {
            return resultToReturn;
        }
    }

    private static class StubLLMClient extends LLMClient {
        public StubLLMClient() {
            super(null);
        }

        @Override
        public String generateAnswer(String promptText) {
            return "Generated answer";
        }
    }

    private static class RecordingResponseFormatter extends ResponseFormatter {
        List<Document> receivedDocuments;

        @Override
        public List<ChunkResponse> formatChunks(List<Document> documents) {
            this.receivedDocuments = documents;
            return super.formatChunks(documents);
        }
    }

    private static class TestConfig extends IntentRetrievalConfig {
        boolean debug = false;

        @Override public int getFinalTopK(QueryIntent intent) { return 10; }
        @Override public int getRawTopK(QueryIntent intent) { return 20; }
        @Override public double getSimilarityThreshold() { return 0.65; }
        @Override public boolean isDebugIntentEnabled() { return debug; }
    }
}

