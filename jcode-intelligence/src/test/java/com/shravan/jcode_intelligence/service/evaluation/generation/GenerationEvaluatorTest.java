package com.shravan.jcode_intelligence.service.evaluation.generation;

import com.shravan.jcode_intelligence.llm.LLMClient;
import com.shravan.jcode_intelligence.llm.LLMResponse;
import com.shravan.jcode_intelligence.service.evaluation.citation.CitationParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

class GenerationEvaluatorTest {

    private LLMClient mockLlmClient;
    private LlmRetryHelper retryHelper;
    private GenerationEvaluator evaluator;

    @BeforeEach
    void setUp() {
        mockLlmClient = mock(LLMClient.class);
        // Fast retry helper for tests: 2 attempts, 5ms delay
        retryHelper = new LlmRetryHelper(2, 5, 2.0, 50, 0);
        evaluator = new GenerationEvaluator(mockLlmClient, retryHelper, null, new CitationParser(), null);
    }

    @Test
    @DisplayName("Successful evaluation computes faithfulness, ungrounded claims, ratings, and citation precision")
    void testSuccessfulEvaluation_computesAllMetrics() {
        String judgeJsonResponse = """
                {
                  "claims": [
                    { "claim": "ServiceA performs task validation", "verdict": "SUPPORTED" },
                    { "claim": "ServiceA logs execution duration", "verdict": "SUPPORTED" },
                    { "claim": "ServiceA sends ungrounded telemetry", "verdict": "UNGROUNDED" }
                  ],
                  "relevancyRating": 5,
                  "correctnessRating": 4,
                  "citationVerdicts": [
                    { "citationIndex": 0, "verdict": "SUPPORTED", "rationale": "Directly mentioned in chunk" }
                  ]
                }
                """;

        when(mockLlmClient.generateWithUsage(any(), isNull()))
                .thenReturn(new LLMResponse(judgeJsonResponse, 120, 85, 45L, "judge-model"));

        Document doc = new Document(
                "public void execute() { validate(); logDuration(); }",
                Map.of("filePath", "src/main/ServiceA.java", "startLine", 10, "endLine", 25)
        );

        String answer = "ServiceA validates tasks and logs duration [cite: src/main/ServiceA.java:10-25#execute].";
        String prompt = "Question: How does ServiceA execute?";

        GenerationEvaluationResult result = evaluator.evaluate(
                "How does ServiceA execute?", prompt, answer, "ServiceA validates and logs", List.of(doc)
        );

        assertNotNull(result);
        assertEquals("SUCCESS", result.status());
        assertNull(result.failureReason());

        // Faithfulness: 2 supported out of 3 claims = 2/3 = 0.6667
        assertEquals(3, result.totalClaims());
        assertEquals(1, result.ungroundedClaims());
        assertEquals(2.0 / 3.0, result.faithfulness(), 0.001);

        // Relevancy: 5 on scale 1-5 = (5-1)/4 = 1.0
        assertEquals(1.0, result.relevancy(), 0.001);

        // Correctness: 4 on scale 1-5 = (4-1)/4 = 0.75
        assertEquals(0.75, result.correctness(), 0.001);
        assertTrue(result.correctnessApproximate());

        // Citation Precision: 1 supported citation out of 1 = 1.0
        assertEquals(1, result.totalCitations());
        assertEquals(1, result.supportedCitations());
        assertEquals(1.0, result.citationPrecision(), 0.001);

        assertEquals(45L, result.judgeLatencyMs());
    }

    @Test
    @DisplayName("Unresolved citation is marked unsupported deterministically without LLM support")
    void testUnresolvedCitation_markedUnsupportedDeterministically() {
        String judgeJsonResponse = """
                {
                  "claims": [
                    { "claim": "Claim 1", "verdict": "SUPPORTED" }
                  ],
                  "relevancyRating": 4,
                  "correctnessRating": 4,
                  "citationVerdicts": []
                }
                """;

        when(mockLlmClient.generateWithUsage(any(), isNull()))
                .thenReturn(new LLMResponse(judgeJsonResponse, 80, 50, 30L, "judge-model"));

        // Document has filePath "Other.java", while citation cites "Missing.java"
        Document doc = new Document("code", Map.of("filePath", "Other.java", "startLine", 1, "endLine", 10));
        String answer = "Fact stated here [cite: Missing.java:1-5].";

        GenerationEvaluationResult result = evaluator.evaluate(
                "query", "prompt", answer, "reference", List.of(doc)
        );

        assertNotNull(result);
        assertEquals("SUCCESS", result.status());
        assertEquals(1, result.totalCitations());
        assertEquals(0, result.supportedCitations());
        assertEquals(0.0, result.citationPrecision(), 0.001, "Unresolved citation must result in 0.0 precision");

        List<CitationVerdict> verdicts = result.citationVerdicts();
        assertEquals(1, verdicts.size());
        assertFalse(verdicts.get(0).resolved());
        assertEquals("UNSUPPORTED", verdicts.get(0).verdict());
    }

    @Test
    @DisplayName("Malformed citation markers are included in precision denominator")
    void testMalformedCitation_includedInPrecisionDenominator() {
        String judgeJsonResponse = """
                {
                  "claims": [
                    { "claim": "Valid statement", "verdict": "SUPPORTED" }
                  ],
                  "relevancyRating": 5,
                  "correctnessRating": 5,
                  "citationVerdicts": [
                    { "citationIndex": 0, "verdict": "SUPPORTED", "rationale": "Supported" }
                  ]
                }
                """;

        when(mockLlmClient.generateWithUsage(any(), isNull()))
                .thenReturn(new LLMResponse(judgeJsonResponse, 90, 40, 25L, "judge-model"));

        Document doc = new Document("valid code", Map.of("filePath", "Valid.java", "startLine", 1, "endLine", 10));
        String answer = "Fact 1 [cite: Valid.java:1-10] and broken fact [cite: malformed-marker].";

        GenerationEvaluationResult result = evaluator.evaluate(
                "query", "prompt", answer, "ref", List.of(doc)
        );

        assertNotNull(result);
        assertEquals("SUCCESS", result.status());
        assertEquals(2, result.totalCitations());
        assertEquals(1, result.supportedCitations());
        assertEquals(0.5, result.citationPrecision(), 0.001, "Precision must be 1 supported / 2 total = 0.5");
    }

    @Test
    @DisplayName("No reference answer results in null correctness")
    void testNoReferenceAnswer_returnsNullCorrectness() {
        String judgeJsonResponse = """
                {
                  "claims": [
                    { "claim": "Fact", "verdict": "SUPPORTED" }
                  ],
                  "relevancyRating": 5,
                  "correctnessRating": null
                }
                """;

        when(mockLlmClient.generateWithUsage(any(), isNull()))
                .thenReturn(new LLMResponse(judgeJsonResponse, 50, 30, 20L, "judge-model"));

        GenerationEvaluationResult result = evaluator.evaluate(
                "query", "prompt", "Answer text.", null, List.of()
        );

        assertNotNull(result);
        assertEquals("SUCCESS", result.status());
        assertNull(result.correctness(), "Correctness must be null when no reference answer is provided");
    }

    @Test
    @DisplayName("LLM failure or JSON parsing failure returns FAILED with null metrics")
    void testLlmCallOrJsonFailure_returnsFailedWithNullMetrics() {
        when(mockLlmClient.generateWithUsage(any(), isNull()))
                .thenReturn(new LLMResponse("{ corrupted invalid json :::", 10, 10, 15L, "judge-model"));

        GenerationEvaluationResult result = evaluator.evaluate(
                "query", "prompt", "Some answer.", "reference", List.of()
        );

        assertNotNull(result);
        assertEquals("FAILED", result.status());
        assertNotNull(result.failureReason());
        assertNull(result.faithfulness());
        assertNull(result.ungroundedClaims());
        assertNull(result.relevancy());
        assertNull(result.correctness());
        assertNull(result.citationPrecision());
    }

    @Test
    @DisplayName("Zero citations in generated answer returns null citation precision")
    void testNoCitationsInAnswer_citationPrecisionIsNull() {
        String judgeJsonResponse = """
                {
                  "claims": [
                    { "claim": "Fact without citation", "verdict": "SUPPORTED" }
                  ],
                  "relevancyRating": 5,
                  "correctnessRating": 4
                }
                """;

        when(mockLlmClient.generateWithUsage(any(), isNull()))
                .thenReturn(new LLMResponse(judgeJsonResponse, 60, 30, 18L, "judge-model"));

        GenerationEvaluationResult result = evaluator.evaluate(
                "query", "prompt", "This is an answer without citations.", "ref", List.of()
        );

        assertNotNull(result);
        assertEquals("SUCCESS", result.status());
        assertEquals(0, result.totalCitations());
        assertNull(result.citationPrecision(), "Citation precision must be null when answer contains 0 citations");
    }

    @Test
    @DisplayName("Empty judge JSON {} is treated as failed evaluation rather than receiving perfect faithfulness")
    void testEmptyJudgeJson_treatedAsFailedEvaluation() {
        when(mockLlmClient.generateWithUsage(any(), isNull()))
                .thenReturn(new LLMResponse("{}", 10, 10, 15L, "judge-model"));

        GenerationEvaluationResult result = evaluator.evaluate(
                "query", "prompt", "Generated answer.", "reference", List.of()
        );

        assertNotNull(result);
        assertEquals("FAILED", result.status());
        assertFalse(result.isSuccess());
        assertNotNull(result.failureReason());
        assertTrue(result.failureReason().contains("claims") || result.failureReason().contains("Invalid judge JSON"));
        assertNull(result.faithfulness(), "Faithfulness must be null on failed evaluation, never 1.0");
        assertNull(result.relevancy(), "Relevancy must be null on failed evaluation");
        assertNull(result.correctness(), "Correctness must be null on failed evaluation");
        assertNull(result.totalClaims());
        assertNull(result.ungroundedClaims());
    }

    @Test
    @DisplayName("Incomplete judge JSON missing relevancyRating is treated as failed evaluation")
    void testIncompleteJudgeJson_missingRelevancyRating_treatedAsFailedEvaluation() {
        String judgeJsonResponse = """
                {
                  "claims": [
                    { "claim": "Valid statement", "verdict": "SUPPORTED" }
                  ],
                  "correctnessRating": 4
                }
                """;

        when(mockLlmClient.generateWithUsage(any(), isNull()))
                .thenReturn(new LLMResponse(judgeJsonResponse, 40, 20, 15L, "judge-model"));

        GenerationEvaluationResult result = evaluator.evaluate(
                "query", "prompt", "Generated answer.", "reference", List.of()
        );

        assertNotNull(result);
        assertEquals("FAILED", result.status());
        assertFalse(result.isSuccess());
        assertNotNull(result.failureReason());
        assertTrue(result.failureReason().contains("relevancyRating"));
        assertNull(result.faithfulness());
    }

    @Test
    @DisplayName("Incomplete judge JSON missing correctnessRating when reference answer is present is treated as failed evaluation")
    void testIncompleteJudgeJson_missingCorrectnessWithReference_treatedAsFailedEvaluation() {
        String judgeJsonResponse = """
                {
                  "claims": [
                    { "claim": "Valid statement", "verdict": "SUPPORTED" }
                  ],
                  "relevancyRating": 5
                }
                """;

        when(mockLlmClient.generateWithUsage(any(), isNull()))
                .thenReturn(new LLMResponse(judgeJsonResponse, 40, 20, 15L, "judge-model"));

        GenerationEvaluationResult result = evaluator.evaluate(
                "query", "prompt", "Generated answer.", "ground-truth reference answer", List.of()
        );

        assertNotNull(result);
        assertEquals("FAILED", result.status());
        assertFalse(result.isSuccess());
        assertNotNull(result.failureReason());
        assertTrue(result.failureReason().contains("correctnessRating"));
        assertNull(result.faithfulness());
    }

    @Test
    @DisplayName("Invalid field types in judge JSON are treated as failed evaluation")
    void testInvalidFieldTypes_treatedAsFailedEvaluation() {
        String judgeJsonResponse = """
                {
                  "claims": "not-an-array",
                  "relevancyRating": "five",
                  "correctnessRating": 4
                }
                """;

        when(mockLlmClient.generateWithUsage(any(), isNull()))
                .thenReturn(new LLMResponse(judgeJsonResponse, 30, 20, 15L, "judge-model"));

        GenerationEvaluationResult result = evaluator.evaluate(
                "query", "prompt", "Generated answer.", "reference", List.of()
        );

        assertNotNull(result);
        assertEquals("FAILED", result.status());
        assertFalse(result.isSuccess());
        assertNull(result.faithfulness());
    }
}
