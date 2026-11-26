package com.shravan.jcode_intelligence.service.evaluation.generation;

import com.shravan.jcode_intelligence.dto.request.BenchmarkRequest;
import com.shravan.jcode_intelligence.dto.response.BenchmarkGenerationSummaryDTO;
import com.shravan.jcode_intelligence.dto.response.QueryBenchmarkResultDTO;
import com.shravan.jcode_intelligence.dto.response.QueryGenerationResultDTO;
import com.shravan.jcode_intelligence.llm.LLMClient;
import com.shravan.jcode_intelligence.llm.LLMResponse;
import com.shravan.jcode_intelligence.llm.PromptBuilder;
import com.shravan.jcode_intelligence.llm.PromptRouter;
import com.shravan.jcode_intelligence.llm.PromptTemplateLoader;
import com.shravan.jcode_intelligence.model.QueryIntent;
import com.shravan.jcode_intelligence.model.benchmark.BenchmarkQueryDefinition;
import com.shravan.jcode_intelligence.service.evaluation.citation.CitationMetricsCalculator;
import com.shravan.jcode_intelligence.service.evaluation.citation.CitationParser;
import com.shravan.jcode_intelligence.service.evaluation.citation.CitationValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.document.Document;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GenerationBenchmarkRunnerTest {

    private PromptBuilder promptBuilder;
    private PromptTemplateLoader templateLoader;

    @BeforeEach
    void setUp() {
        templateLoader = new PromptTemplateLoader(new DefaultResourceLoader());
        promptBuilder = new PromptBuilder(new PromptRouter(), templateLoader, null, null);
    }

    private Document createTestDoc() {
        return new Document("public class Sample {}", Map.of(
                "filePath", "src/Sample.java",
                "startLine", 1,
                "endLine", 10
        ));
    }

    @Test
    @DisplayName("Empty or null retained queries returns empty summary with 0 evaluated")
    void testEmptyRetainedQueries() {
        GenerationBenchmarkRunner runner = new GenerationBenchmarkRunner(
                promptBuilder, null, null, null, null, null, null, 10
        );

        BenchmarkGenerationSummaryDTO summary = runner.runGenerationEvaluation(null, new BenchmarkRequest(), null);
        assertNotNull(summary);
        assertEquals(0, summary.getTotalQueriesEvaluated());
        assertEquals(0, summary.getBatchCount());

        BenchmarkGenerationSummaryDTO summaryEmpty = runner.runGenerationEvaluation(List.of(), new BenchmarkRequest(), null);
        assertNotNull(summaryEmpty);
        assertEquals(0, summaryEmpty.getTotalQueriesEvaluated());
        assertEquals(0, summaryEmpty.getBatchCount());
    }

    @Test
    @DisplayName("Aggregates mixed success and failure generation metrics with proper denominators and 4-decimal rounding")
    void testMixedGenerationMetricsAggregation() {
        LLMClient mockLlm = new LLMClient(null) {
            private int counter = 0;
            @Override
            public LLMResponse generateWithUsage(String prompt, ChatOptions options) {
                counter++;
                if (counter == 1) {
                    // Q1 generation
                    return new LLMResponse("Sample answer 1 [cite: src/Sample.java:1-5].", 100, 50, 200L, "mock-model");
                } else if (counter == 2) {
                    // Q1 judge
                    String judgeJson = """
                            {
                              "claims": [
                                { "claim": "Fact 1", "verdict": "SUPPORTED" },
                                { "claim": "Fact 2", "verdict": "UNGROUNDED" }
                              ],
                              "relevancyRating": 4,
                              "correctnessRating": 3,
                              "citationVerdicts": [
                                { "citationIndex": 0, "verdict": "SUPPORTED", "rationale": "Valid" }
                              ]
                            }
                            """;
                    return new LLMResponse(judgeJson, 150, 40, 150L, "mock-judge");
                } else {
                    // Q2 generation fails
                    throw new RuntimeException("API rate limit error");
                }
            }
        };

        GenerationEvaluator evaluator = new GenerationEvaluator(mockLlm, new LlmRetryHelper(1, 0, 1.0, 0, 0), templateLoader);
        GenerationBenchmarkRunner runner = new GenerationBenchmarkRunner(
                promptBuilder, mockLlm, new LlmRetryHelper(1, 0, 1.0, 0, 0),
                new CitationParser(), new CitationValidator(), new CitationMetricsCalculator(),
                evaluator,
                5
        );

        BenchmarkQueryDefinition bq1 = new BenchmarkQueryDefinition("Q1", "Query 1", "Category", "repo", "CLASS", List.of(), "Ref answer 1", List.of("src/Sample.java"));
        BenchmarkQueryDefinition bq2 = new BenchmarkQueryDefinition("Q2", "Query 2", "Category", "repo", "CLASS", List.of(), "Ref answer 2", List.of());

        QueryBenchmarkResultDTO qr1 = new QueryBenchmarkResultDTO();
        qr1.setQueryId("Q1");
        qr1.setStatus("SUCCESS");

        QueryBenchmarkResultDTO qr2 = new QueryBenchmarkResultDTO();
        qr2.setQueryId("Q2");
        qr2.setStatus("SUCCESS");

        List<GenerationBenchmarkRunner.RetainedBenchmarkQuery> retained = List.of(
                new GenerationBenchmarkRunner.RetainedBenchmarkQuery(bq1, qr1, List.of(createTestDoc()), QueryIntent.SEARCH, "repo"),
                new GenerationBenchmarkRunner.RetainedBenchmarkQuery(bq2, qr2, List.of(createTestDoc()), QueryIntent.SEARCH, "repo")
        );

        BenchmarkRequest request = new BenchmarkRequest("repo");
        request.setEvaluateGeneration(true);

        BenchmarkGenerationSummaryDTO summary = runner.runGenerationEvaluation(retained, request, "main");

        assertNotNull(summary);
        assertEquals(2, summary.getTotalQueriesEvaluated());
        assertEquals(1, summary.getSuccessfulGenerations());
        assertEquals(1, summary.getFailedGenerations());
        assertEquals(1, summary.getBatchCount());

        // Latency: only successful generations count
        assertEquals(200.0, summary.getAverageGenerationLatencyMs());
        assertEquals(150.0, summary.getAverageJudgeLatencyMs());

        // Tokens: qr1 has total tokens 150 (prompt 100 + completion 50)
        assertEquals(100L, summary.getTotalPromptTokens());
        assertEquals(50L, summary.getTotalCompletionTokens());
        assertEquals(150L, summary.getTotalTokens());
        assertEquals(150.0, summary.getAverageTokensPerQuery());

        // Quality: 1 supported out of 2 claims = 0.5 faithfulness
        assertEquals(0.5000, summary.getAverageFaithfulness());
        // Relevancy: 4 on scale of 1-5 -> (4-1)/4 = 0.75
        assertEquals(0.7500, summary.getAverageRelevancy());
        // Correctness: 3 on scale of 1-5 -> (3-1)/4 = 0.50
        assertEquals(0.5000, summary.getAverageCorrectness());
        // 1 ungrounded claim
        assertEquals(1, summary.getTotalUngroundedClaims());
        // 1 citation, supported -> 1.0 precision
        assertEquals(1.0000, summary.getAverageCitationPrecision());

        // QR verification
        assertEquals("SUCCESS", qr1.getGenerationResult().getStatus());
        assertEquals("FAILED", qr2.getGenerationResult().getStatus());
    }
}
