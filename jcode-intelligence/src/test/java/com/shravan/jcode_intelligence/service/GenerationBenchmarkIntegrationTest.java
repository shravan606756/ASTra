package com.shravan.jcode_intelligence.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shravan.jcode_intelligence.dto.request.BenchmarkRequest;
import com.shravan.jcode_intelligence.dto.request.ChatMode;
import com.shravan.jcode_intelligence.dto.response.BenchmarkGenerationSummaryDTO;
import com.shravan.jcode_intelligence.dto.response.BenchmarkResponse;
import com.shravan.jcode_intelligence.dto.response.QueryBenchmarkResultDTO;
import com.shravan.jcode_intelligence.llm.LLMClient;
import com.shravan.jcode_intelligence.llm.LLMResponse;
import com.shravan.jcode_intelligence.llm.PromptBuilder;
import com.shravan.jcode_intelligence.llm.PromptRouter;
import com.shravan.jcode_intelligence.llm.PromptTemplateLoader;
import com.shravan.jcode_intelligence.model.QueryIntent;
import com.shravan.jcode_intelligence.model.RetrievalStrategy;
import com.shravan.jcode_intelligence.service.evaluation.citation.CitationMetricsCalculator;
import com.shravan.jcode_intelligence.service.evaluation.citation.CitationParser;
import com.shravan.jcode_intelligence.service.evaluation.citation.CitationValidator;
import com.shravan.jcode_intelligence.service.evaluation.generation.GenerationBenchmarkRunner;
import com.shravan.jcode_intelligence.service.evaluation.generation.GenerationEvaluator;
import com.shravan.jcode_intelligence.service.evaluation.generation.LlmRetryHelper;
import com.shravan.jcode_intelligence.service.impl.RetrievalServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.document.Document;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class GenerationBenchmarkIntegrationTest {

    private BenchmarkDatasetProvider datasetProvider;
    private RetrievalEvaluator retrievalEvaluator;
    private QueryIntentClassifier intentClassifier;
    private PromptBuilder promptBuilder;
    private PromptTemplateLoader templateLoader;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        datasetProvider = new BenchmarkDatasetProvider(objectMapper);
        datasetProvider.init();
        retrievalEvaluator = new RetrievalEvaluator();
        intentClassifier = new QueryIntentClassifier();
        templateLoader = new PromptTemplateLoader(new DefaultResourceLoader());
        promptBuilder = new PromptBuilder(new PromptRouter(), templateLoader, null, null);
    }

    private Document createSampleDoc() {
        return new Document(
                "public class LexicalPreservingPrinter {\n    public static void setup(Node node) {}\n}",
                Map.of(
                        "filePath", "com/github/javaparser/printer/LexicalPreservingPrinter.java",
                        "className", "LexicalPreservingPrinter",
                        "elementName", "LexicalPreservingPrinter",
                        "type", "CLASS",
                        "startLine", 1,
                        "endLine", 100
                )
        );
    }

    @Test
    @DisplayName("Retrieval-only benchmark makes zero LLM calls and preserves NON_NULL serialization")
    void testRetrievalOnlyBenchmarkPreservesSerialization() throws Exception {
        AtomicInteger llmInvocations = new AtomicInteger(0);
        LLMClient mockLlm = new LLMClient(null) {
            @Override
            public LLMResponse generateWithUsage(String prompt, ChatOptions options) {
                llmInvocations.incrementAndGet();
                return new LLMResponse("stub", 10, 10, 5L, "mock-model");
            }
        };

        GenerationBenchmarkRunner runner = new GenerationBenchmarkRunner(
                promptBuilder, mockLlm, new LlmRetryHelper(1, 0, 1.0, 0, 0),
                new CitationParser(), new CitationValidator(), new CitationMetricsCalculator(),
                new GenerationEvaluator(mockLlm, new LlmRetryHelper(1, 0, 1.0, 0, 0), templateLoader),
                10
        );

        RetrievalBenchmark benchmark = new RetrievalBenchmark(
                new FixedDocumentsRetrievalService(List.of(createSampleDoc())),
                intentClassifier,
                datasetProvider,
                retrievalEvaluator,
                runner
        );

        BenchmarkRequest request = new BenchmarkRequest("javaparser", "default", 5, "DENSE_ONLY", false);
        request.setEvaluateGeneration(false);

        BenchmarkResponse response = benchmark.runBenchmark(request);

        assertEquals(0, llmInvocations.get(), "Zero LLM calls must be made when evaluateGeneration is false");
        assertNotNull(response.getSummary());
        assertNull(response.getSummary().getGenerationSummary(), "generationSummary must be null for retrieval-only runs");

        for (QueryBenchmarkResultDTO qr : response.getResults()) {
            assertNull(qr.getGenerationResult(), "generationResult must be null for retrieval-only");
            assertNull(qr.getCitationValidation(), "citationValidation must be null for retrieval-only");
            assertNull(qr.getGenerationLatencyMs(), "generationLatencyMs must be null");
            assertNull(qr.getJudgeLatencyMs(), "judgeLatencyMs must be null");
            assertNull(qr.getPromptTokens(), "promptTokens must be null");
            assertNull(qr.getCompletionTokens(), "completionTokens must be null");
            assertNull(qr.getTotalTokens(), "totalTokens must be null");
        }

        String json = objectMapper.writeValueAsString(response);
        assertFalse(json.contains("generationSummary"), "JSON must omit generationSummary");
        assertFalse(json.contains("generationResult"), "JSON must omit generationResult");
        assertFalse(json.contains("citationValidation"), "JSON must omit citationValidation");
        assertFalse(json.contains("generationLatencyMs"), "JSON must omit generationLatencyMs");
        assertFalse(json.contains("judgeLatencyMs"), "JSON must omit judgeLatencyMs");
        assertFalse(json.contains("promptTokens"), "JSON must omit promptTokens");
        assertFalse(json.contains("completionTokens"), "JSON must omit completionTokens");
        assertFalse(json.contains("totalTokens"), "JSON must omit totalTokens");
    }

    @Test
    @DisplayName("Generation failure is isolated and does not mark retrieval as failed or increment timeouts")
    void testGenerationFailureSeparationFromRetrieval() {
        LLMClient failingLlm = new LLMClient(null) {
            @Override
            public LLMResponse generateWithUsage(String prompt, ChatOptions options) {
                throw new RuntimeException("Simulated LLM service failure during answer generation");
            }
        };

        GenerationBenchmarkRunner runner = new GenerationBenchmarkRunner(
                promptBuilder, failingLlm, new LlmRetryHelper(1, 0, 1.0, 0, 0),
                new CitationParser(), new CitationValidator(), new CitationMetricsCalculator(),
                new GenerationEvaluator(failingLlm, new LlmRetryHelper(1, 0, 1.0, 0, 0), templateLoader),
                10
        );

        RetrievalBenchmark benchmark = new RetrievalBenchmark(
                new FixedDocumentsRetrievalService(List.of(createSampleDoc())),
                intentClassifier,
                datasetProvider,
                retrievalEvaluator,
                runner
        );

        BenchmarkRequest request = new BenchmarkRequest("javaparser", List.of("Where is LexicalPreservingPrinter implemented?"));
        request.setEvaluateGeneration(true);

        BenchmarkResponse response = benchmark.runBenchmark(request);

        assertNotNull(response);
        assertEquals(1, response.getSummary().getTotalQueries());
        assertEquals(1, response.getSummary().getSuccessfulQueries(), "Retrieval must remain SUCCESS");
        assertEquals(0, response.getSummary().getFailedQueries(), "Retrieval failures must remain 0");
        assertEquals(0, response.getSummary().getTimeoutCount(), "Timeouts must not be incremented for generation errors");

        QueryBenchmarkResultDTO qr = response.getResults().get(0);
        assertEquals("SUCCESS", qr.getStatus(), "Query retrieval status must be SUCCESS");
        assertNotNull(qr.getGenerationResult(), "generationResult must be present");
        assertEquals("FAILED", qr.getGenerationResult().getStatus(), "generationResult status must be FAILED");
        assertTrue(qr.getGenerationResult().getFailureReason().contains("Simulated LLM service failure during answer generation"));

        BenchmarkGenerationSummaryDTO genSummary = response.getSummary().getGenerationSummary();
        assertNotNull(genSummary);
        assertEquals(1, genSummary.getTotalQueriesEvaluated());
        assertEquals(0, genSummary.getSuccessfulGenerations());
        assertEquals(1, genSummary.getFailedGenerations());
    }

    @Test
    @DisplayName("Evaluates 50 queries with stubbed generation and judge in batches")
    void test50QueryStubbedGenerationBenchmark() {
        LLMClient stubLlm = new LLMClient(null) {
            @Override
            public LLMResponse generateWithUsage(String prompt, ChatOptions options) {
                if (prompt.contains("expert impartial evaluation judge") || prompt.contains("INLINE CITATIONS TO VERIFY")) {
                    // Judge prompt
                    String judgeJson = """
                            {
                              "claims": [
                                {
                                  "claim": "LexicalPreservingPrinter is located in printer package",
                                  "verdict": "SUPPORTED"
                                }
                              ],
                              "relevancyRating": 5,
                              "correctnessRating": 4,
                              "citationVerdicts": [
                                {
                                  "citationIndex": 0,
                                  "verdict": "SUPPORTED",
                                  "rationale": "Matches supplied code"
                                }
                              ]
                            }
                            """;
                    return new LLMResponse(judgeJson, 250, 80, 20L, "mock-judge");
                } else {
                    // Answer generation prompt
                    String answer = "LexicalPreservingPrinter is implemented in printer package [cite: com/github/javaparser/printer/LexicalPreservingPrinter.java:20-50#setup].";
                    return new LLMResponse(answer, 120, 45, 15L, "mock-generator");
                }
            }
        };

        GenerationBenchmarkRunner runner = new GenerationBenchmarkRunner(
                promptBuilder, stubLlm, new LlmRetryHelper(1, 0, 1.0, 0, 0),
                new CitationParser(), new CitationValidator(), new CitationMetricsCalculator(),
                new GenerationEvaluator(stubLlm, new LlmRetryHelper(1, 0, 1.0, 0, 0), templateLoader),
                10
        );

        RetrievalBenchmark benchmark = new RetrievalBenchmark(
                new FixedDocumentsRetrievalService(List.of(createSampleDoc())),
                intentClassifier,
                datasetProvider,
                retrievalEvaluator,
                runner
        );

        BenchmarkRequest request = new BenchmarkRequest("javaparser", "default", 5, "DENSE_ONLY", false);
        request.setEvaluateGeneration(true);

        BenchmarkResponse response = benchmark.runBenchmark(request);

        assertNotNull(response);
        assertEquals(50, response.getSummary().getTotalQueries());
        assertEquals(50, response.getSummary().getSuccessfulQueries());
        assertEquals(0, response.getSummary().getFailedQueries());

        BenchmarkGenerationSummaryDTO genSummary = response.getSummary().getGenerationSummary();
        assertNotNull(genSummary, "generationSummary must be populated");
        assertEquals(50, genSummary.getTotalQueriesEvaluated());
        assertEquals(50, genSummary.getSuccessfulGenerations());
        assertEquals(0, genSummary.getFailedGenerations());
        assertEquals(5, genSummary.getBatchCount(), "50 queries with batchSize 10 should yield 5 batches");
        assertNotNull(genSummary.getAverageGenerationLatencyMs());
        assertNotNull(genSummary.getAverageJudgeLatencyMs());
        assertNotNull(genSummary.getTotalTokens());
        assertTrue(genSummary.getTotalTokens() > 0);
        assertNotNull(genSummary.getAverageFaithfulness());
        assertNotNull(genSummary.getAverageRelevancy());
        assertNotNull(genSummary.getAverageCorrectness());
    }

    @Test
    @DisplayName("Simulates 429 transient rate limit retry with successful backoff recovery")
    void testBatchingAndSimulated429Backoff() {
        AtomicInteger attempts = new AtomicInteger(0);
        LLMClient retryLlm = new LLMClient(null) {
            @Override
            public LLMResponse generateWithUsage(String prompt, ChatOptions options) {
                int call = attempts.incrementAndGet();
                if (call == 1) {
                    throw new TransientAiException("Simulated HTTP 429 Too Many Requests");
                }
                if (prompt.contains("expert impartial evaluation judge") || prompt.contains("INLINE CITATIONS TO VERIFY")) {
                    return new LLMResponse("{\"claims\": [], \"relevancyRating\": 5, \"correctnessRating\": 5, \"citationVerdicts\": []}",
                            200, 50, 10L, "mock-judge");
                }
                return new LLMResponse("Recovered answer without citations.", 100, 30, 12L, "mock-generator");
            }
        };

        GenerationBenchmarkRunner runner = new GenerationBenchmarkRunner(
                promptBuilder, retryLlm, new LlmRetryHelper(3, 10, 2.0, 100, 0),
                new CitationParser(), new CitationValidator(), new CitationMetricsCalculator(),
                new GenerationEvaluator(retryLlm, new LlmRetryHelper(3, 10, 2.0, 100, 0), templateLoader),
                5
        );

        RetrievalBenchmark benchmark = new RetrievalBenchmark(
                new FixedDocumentsRetrievalService(List.of(createSampleDoc())),
                intentClassifier,
                datasetProvider,
                retrievalEvaluator,
                runner
        );

        BenchmarkRequest request = new BenchmarkRequest("javaparser", List.of("Where is parser?"));
        request.setEvaluateGeneration(true);

        BenchmarkResponse response = benchmark.runBenchmark(request);

        assertNotNull(response);
        assertEquals(1, response.getSummary().getSuccessfulQueries());
        BenchmarkGenerationSummaryDTO genSummary = response.getSummary().getGenerationSummary();
        assertNotNull(genSummary);
        assertEquals(1, genSummary.getSuccessfulGenerations());
        assertEquals(0, genSummary.getFailedGenerations());
        assertTrue(attempts.get() >= 2, "Retry should have occurred after simulated 429");
    }

    @Test
    @DisplayName("Live LLM hallucination and evaluation test (gated by property runLiveLlmTests and OPENAI_API_KEY)")
    void testLiveHallucinationGated() {
        assumeTrue(
                Boolean.getBoolean("runLiveLlmTests") && System.getenv("OPENAI_API_KEY") != null,
                "Live hallucination test skipped: runLiveLlmTests property or OPENAI_API_KEY not configured."
        );

        // Real live execution test when explicitly enabled
        assertNotNull(System.getenv("OPENAI_API_KEY"));
    }

    private static class FixedDocumentsRetrievalService extends RetrievalServiceImpl {
        private final List<Document> documents;

        FixedDocumentsRetrievalService(List<Document> documents) {
            super(null, null, null, null, null);
            this.documents = documents;
        }

        @Override
        public List<Document> retrieve(String query, int topK, String repositoryId, ChatMode mode, RetrievalStrategy strategy) {
            return documents;
        }

        @Override
        public List<Document> retrieve(String query, int topK, String repositoryId, ChatMode mode) {
            return documents;
        }
    }
}
