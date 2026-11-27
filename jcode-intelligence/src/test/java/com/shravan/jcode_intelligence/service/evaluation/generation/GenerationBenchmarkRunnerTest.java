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
import com.shravan.jcode_intelligence.service.evaluation.citation.RevisionStatus;
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

    @Test
    @DisplayName("Dirty checkout produces null citation scores and preserves DIRTY revision status in aggregate summary")
    void testDirtyCheckoutSkipsCitationScoresAndPreservesDirtyStatus() {
        LLMClient mockLlm = new LLMClient(null) {
            private int counter = 0;
            @Override
            public LLMResponse generateWithUsage(String prompt, ChatOptions options) {
                counter++;
                if (counter == 1) {
                    return new LLMResponse("Sample answer with citation [cite: src/Sample.java:1-5].", 100, 50, 200L, "mock-model");
                } else {
                    String judgeJson = """
                            {
                              "claims": [
                                { "claim": "Fact 1", "verdict": "SUPPORTED" }
                              ],
                              "relevancyRating": 5,
                              "correctnessRating": 5,
                              "citationVerdicts": [
                                { "citationIndex": 0, "verdict": "SUPPORTED", "rationale": "Valid" }
                              ]
                            }
                            """;
                    return new LLMResponse(judgeJson, 150, 40, 150L, "mock-judge");
                }
            }
        };

        CitationValidator dirtyValidator = new CitationValidator() {
            @Override
            public RevisionStatus checkRevision(java.nio.file.Path repositoryRoot, String expectedRevision) {
                return RevisionStatus.DIRTY;
            }

            @Override
            public List<com.shravan.jcode_intelligence.service.evaluation.citation.CitationValidationResult> validateAll(
                    List<com.shravan.jcode_intelligence.service.evaluation.citation.Citation> citations,
                    String repositoryRoot,
                    String expectedRevision
            ) {
                return citations.stream()
                        .map(c -> com.shravan.jcode_intelligence.service.evaluation.citation.CitationValidationResult.skipped(
                                c, RevisionStatus.DIRTY, "Validation skipped: repository revision is DIRTY"))
                        .toList();
            }
        };

        GenerationEvaluator evaluator = new GenerationEvaluator(mockLlm, new LlmRetryHelper(1, 0, 1.0, 0, 0), templateLoader);
        GenerationBenchmarkRunner runner = new GenerationBenchmarkRunner(
                promptBuilder, mockLlm, new LlmRetryHelper(1, 0, 1.0, 0, 0),
                new CitationParser(), dirtyValidator, new CitationMetricsCalculator(),
                evaluator,
                5
        );

        BenchmarkQueryDefinition bq = new BenchmarkQueryDefinition(
                "Q1", "Query 1", "Category", "repo", "CLASS", List.of(), "Ref answer 1", List.of("src/Sample.java")
        );
        QueryBenchmarkResultDTO qr = new QueryBenchmarkResultDTO();
        qr.setQueryId("Q1");
        qr.setStatus("SUCCESS");

        List<GenerationBenchmarkRunner.RetainedBenchmarkQuery> retained = List.of(
                new GenerationBenchmarkRunner.RetainedBenchmarkQuery(bq, qr, List.of(createTestDoc()), QueryIntent.SEARCH, "repo")
        );

        BenchmarkRequest request = new BenchmarkRequest("repo");
        request.setEvaluateGeneration(true);
        request.setSourceRoot("/fake/path");

        BenchmarkGenerationSummaryDTO summary = runner.runGenerationEvaluation(retained, request, "main");

        assertNotNull(summary);
        // Per-query DTO verification
        assertNotNull(qr.getCitationValidation());
        assertNull(qr.getCitationValidation().getCitationCorrectness(), "Per-query citation correctness must be null on dirty checkout");
        assertNull(qr.getCitationValidation().getExpectedSourceRecall(), "Per-query expected source recall must be null on dirty checkout");
        assertEquals("DIRTY", qr.getCitationValidation().getRevisionStatus());

        // Aggregate summary verification: unevaluated scores must be null, not 0.0
        assertNull(summary.getAverageCitationCorrectness(), "Aggregate average citation correctness must be null on dirty checkout");
        assertNull(summary.getAverageExpectedSourceRecall(), "Aggregate average expected source recall must be null on dirty checkout");
        assertEquals("DIRTY", summary.getRevisionStatus(), "Summary revision status must be DIRTY");
    }

    @Test
    @DisplayName("Clean checkout with a malformed citation before a valid citation retains MATCHED status and non-null scores")
    void testCleanCheckoutWithMalformedCitationBeforeValidOne() {
        LLMClient mockLlm = new LLMClient(null) {
            private int counter = 0;
            @Override
            public LLMResponse generateWithUsage(String prompt, ChatOptions options) {
                counter++;
                if (counter == 1) {
                    // Answer has malformed citation [cite: broken] before valid citation [cite: src/Sample.java:1-5]
                    return new LLMResponse("Explanation [cite: broken] and code in [cite: src/Sample.java:1-5].", 100, 50, 200L, "mock-model");
                } else {
                    String judgeJson = """
                            {
                              "claims": [
                                { "claim": "Fact 1", "verdict": "SUPPORTED" }
                              ],
                              "relevancyRating": 5,
                              "correctnessRating": 5,
                              "citationVerdicts": [
                                { "citationIndex": 1, "verdict": "SUPPORTED", "rationale": "Valid" }
                              ]
                            }
                            """;
                    return new LLMResponse(judgeJson, 150, 40, 150L, "mock-judge");
                }
            }
        };

        CitationValidator matchedValidator = new CitationValidator() {
            @Override
            public RevisionStatus checkRevision(java.nio.file.Path repositoryRoot, String expectedRevision) {
                return RevisionStatus.MATCHED;
            }

            @Override
            public List<com.shravan.jcode_intelligence.service.evaluation.citation.CitationValidationResult> validateAll(
                    List<com.shravan.jcode_intelligence.service.evaluation.citation.Citation> citations,
                    String repositoryRoot,
                    String expectedRevision
            ) {
                return citations.stream().map(c -> {
                    if (!c.validSyntax()) {
                        return com.shravan.jcode_intelligence.service.evaluation.citation.CitationValidationResult.malformed(
                                c, RevisionStatus.MATCHED, "Malformed syntax"
                        );
                    }
                    return new com.shravan.jcode_intelligence.service.evaluation.citation.CitationValidationResult(
                            c, true, RevisionStatus.MATCHED,
                            com.shravan.jcode_intelligence.service.evaluation.citation.FileStatus.VALID,
                            com.shravan.jcode_intelligence.service.evaluation.citation.EntityStatus.VALID,
                            "Valid"
                    );
                }).toList();
            }
        };

        GenerationEvaluator evaluator = new GenerationEvaluator(mockLlm, new LlmRetryHelper(1, 0, 1.0, 0, 0), templateLoader);
        GenerationBenchmarkRunner runner = new GenerationBenchmarkRunner(
                promptBuilder, mockLlm, new LlmRetryHelper(1, 0, 1.0, 0, 0),
                new CitationParser(), matchedValidator, new CitationMetricsCalculator(),
                evaluator,
                5
        );

        BenchmarkQueryDefinition bq = new BenchmarkQueryDefinition(
                "Q1", "Query 1", "Category", "repo", "CLASS", List.of(), "Ref answer 1", List.of("src/Sample.java")
        );
        QueryBenchmarkResultDTO qr = new QueryBenchmarkResultDTO();
        qr.setQueryId("Q1");
        qr.setStatus("SUCCESS");

        List<GenerationBenchmarkRunner.RetainedBenchmarkQuery> retained = List.of(
                new GenerationBenchmarkRunner.RetainedBenchmarkQuery(bq, qr, List.of(createTestDoc()), QueryIntent.SEARCH, "repo")
        );

        BenchmarkRequest request = new BenchmarkRequest("repo");
        request.setEvaluateGeneration(true);
        request.setSourceRoot("/fake/path");

        BenchmarkGenerationSummaryDTO summary = runner.runGenerationEvaluation(retained, request, "main");

        assertNotNull(summary);
        assertNotNull(qr.getCitationValidation());
        // Revision status must be MATCHED, not overridden to UNAVAILABLE by the malformed first citation
        assertEquals("MATCHED", qr.getCitationValidation().getRevisionStatus());
        // Scores must not be null: 1 structurally valid citation out of 2 attempted = 0.5 correctness
        assertEquals(0.50, qr.getCitationValidation().getCitationCorrectness(), 0.0001);
        assertEquals(1.00, qr.getCitationValidation().getExpectedSourceRecall(), 0.0001);
        assertEquals(2, qr.getCitationValidation().getTotalCitations());
        assertEquals(1, qr.getCitationValidation().getValidSyntaxCitations());
        assertEquals(1, qr.getCitationValidation().getStructurallyValidCitations());

        // Aggregate summary must be MATCHED and have 0.50 correctness and 1.00 recall
        assertEquals("MATCHED", summary.getRevisionStatus());
        assertEquals(0.5000, summary.getAverageCitationCorrectness());
        assertEquals(1.0000, summary.getAverageExpectedSourceRecall());
    }

    @Test
    @DisplayName("Matched query followed by mismatched or unavailable query aggregates non-matched status and excludes unevaluated scores")
    void testMatchedQueryFollowedByMismatchedQuery_aggregatesMismatchedStatus() {
        LLMClient mockLlm = new LLMClient(null) {
            private int counter = 0;
            @Override
            public LLMResponse generateWithUsage(String prompt, ChatOptions options) {
                counter++;
                if (counter == 1) {
                    // Q1 generation
                    return new LLMResponse("Q1 answer [cite: src/Sample.java:1-5].", 100, 50, 200L, "mock-model");
                } else if (counter == 2) {
                    // Q1 judge
                    return new LLMResponse("""
                            {
                              "claims": [{ "claim": "Fact 1", "verdict": "SUPPORTED" }],
                              "relevancyRating": 5,
                              "correctnessRating": 5,
                              "citationVerdicts": [{ "citationIndex": 0, "verdict": "SUPPORTED", "rationale": "Valid" }]
                            }
                            """, 150, 40, 150L, "mock-judge");
                } else if (counter == 3) {
                    // Q2 generation
                    return new LLMResponse("Q2 answer [cite: src/Sample.java:1-5].", 100, 50, 200L, "mock-model");
                } else {
                    // Q2 judge
                    return new LLMResponse("""
                            {
                              "claims": [{ "claim": "Fact 2", "verdict": "SUPPORTED" }],
                              "relevancyRating": 5,
                              "correctnessRating": 5,
                              "citationVerdicts": [{ "citationIndex": 0, "verdict": "SUPPORTED", "rationale": "Valid" }]
                            }
                            """, 150, 40, 150L, "mock-judge");
                }
            }
        };

        CitationValidator mixedValidator = new CitationValidator() {
            @Override
            public RevisionStatus checkRevision(java.nio.file.Path repositoryRoot, String expectedRevision) {
                if ("rev-matched".equals(expectedRevision)) {
                    return RevisionStatus.MATCHED;
                }
                return RevisionStatus.MISMATCHED;
            }

            @Override
            public List<com.shravan.jcode_intelligence.service.evaluation.citation.CitationValidationResult> validateAll(
                    List<com.shravan.jcode_intelligence.service.evaluation.citation.Citation> citations,
                    String repositoryRoot,
                    String expectedRevision
            ) {
                if ("rev-matched".equals(expectedRevision)) {
                    return citations.stream().map(c -> new com.shravan.jcode_intelligence.service.evaluation.citation.CitationValidationResult(
                            c, true, RevisionStatus.MATCHED,
                            com.shravan.jcode_intelligence.service.evaluation.citation.FileStatus.VALID,
                            com.shravan.jcode_intelligence.service.evaluation.citation.EntityStatus.VALID,
                            "Valid"
                    )).toList();
                } else {
                    return citations.stream().map(c -> com.shravan.jcode_intelligence.service.evaluation.citation.CitationValidationResult.skipped(
                            c, RevisionStatus.MISMATCHED, "Revision mismatched"
                    )).toList();
                }
            }
        };

        GenerationEvaluator evaluator = new GenerationEvaluator(mockLlm, new LlmRetryHelper(1, 0, 1.0, 0, 0), templateLoader);
        GenerationBenchmarkRunner runner = new GenerationBenchmarkRunner(
                promptBuilder, mockLlm, new LlmRetryHelper(1, 0, 1.0, 0, 0),
                new CitationParser(), mixedValidator, new CitationMetricsCalculator(),
                evaluator,
                5
        );

        BenchmarkQueryDefinition bq1 = new BenchmarkQueryDefinition(
                "Q1", "Query 1", "Category", "repo", "CLASS", List.of(), "Ref answer 1", List.of("src/Sample.java")
        );
        bq1.setSourceRevision("rev-matched");
        QueryBenchmarkResultDTO qr1 = new QueryBenchmarkResultDTO();
        qr1.setQueryId("Q1");
        qr1.setStatus("SUCCESS");

        BenchmarkQueryDefinition bq2 = new BenchmarkQueryDefinition(
                "Q2", "Query 2", "Category", "repo", "CLASS", List.of(), "Ref answer 2", List.of("src/Sample.java")
        );
        bq2.setSourceRevision("rev-mismatched");
        QueryBenchmarkResultDTO qr2 = new QueryBenchmarkResultDTO();
        qr2.setQueryId("Q2");
        qr2.setStatus("SUCCESS");

        List<GenerationBenchmarkRunner.RetainedBenchmarkQuery> retained = List.of(
                new GenerationBenchmarkRunner.RetainedBenchmarkQuery(bq1, qr1, List.of(createTestDoc()), QueryIntent.SEARCH, "repo"),
                new GenerationBenchmarkRunner.RetainedBenchmarkQuery(bq2, qr2, List.of(createTestDoc()), QueryIntent.SEARCH, "repo")
        );

        BenchmarkRequest request = new BenchmarkRequest("repo");
        request.setEvaluateGeneration(true);
        request.setSourceRoot("/fake/path");

        BenchmarkGenerationSummaryDTO summary = runner.runGenerationEvaluation(retained, request, null);

        assertNotNull(summary);
        // Q1 is MATCHED with 1.0 correctness and 1.0 recall
        assertEquals("MATCHED", qr1.getCitationValidation().getRevisionStatus());
        assertEquals(1.0, qr1.getCitationValidation().getCitationCorrectness(), 0.0001);
        assertEquals(1.0, qr1.getCitationValidation().getExpectedSourceRecall(), 0.0001);

        // Q2 is MISMATCHED with null correctness and null recall
        assertEquals("MISMATCHED", qr2.getCitationValidation().getRevisionStatus());
        assertNull(qr2.getCitationValidation().getCitationCorrectness());
        assertNull(qr2.getCitationValidation().getExpectedSourceRecall());

        // Aggregate summary must be MISMATCHED (not MATCHED), and only Q1 contributes to average citation scores
        assertEquals("MISMATCHED", summary.getRevisionStatus(), "Run-level status must prioritize non-matched status (MISMATCHED)");
        assertEquals(1.0000, summary.getAverageCitationCorrectness(), "Average citation correctness must include only the evaluated query");
        assertEquals(1.0000, summary.getAverageExpectedSourceRecall(), "Average expected source recall must include only the evaluated query");
    }
}
