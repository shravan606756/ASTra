package com.shravan.jcode_intelligence.cli.commands;

import com.shravan.jcode_intelligence.cli.client.ApiClient;
import com.shravan.jcode_intelligence.cli.client.ApiException;
import com.shravan.jcode_intelligence.cli.shell.CommandResult;
import com.shravan.jcode_intelligence.cli.shell.ShellContext;
import com.shravan.jcode_intelligence.cli.ui.ConsoleUI;
import com.shravan.jcode_intelligence.dto.request.BenchmarkRequest;
import com.shravan.jcode_intelligence.dto.response.BenchmarkGenerationSummaryDTO;
import com.shravan.jcode_intelligence.dto.response.BenchmarkResponse;
import com.shravan.jcode_intelligence.dto.response.BenchmarkSummaryDTO;
import com.shravan.jcode_intelligence.dto.response.CitationValidationDTO;
import com.shravan.jcode_intelligence.dto.response.QueryBenchmarkResultDTO;
import com.shravan.jcode_intelligence.dto.response.QueryBenchmarkResultDTO.RetrievedChunkDTO;
import com.shravan.jcode_intelligence.dto.response.QueryGenerationResultDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class BenchmarkCommandTest {

    private ShellContext context;
    private ConsoleUI consoleUI;

    @BeforeEach
    void setUp() {
        context = new ShellContext();
        consoleUI = new ConsoleUI();
    }

    @Test
    @DisplayName("Command name and description are properly defined")
    void testCommandMetadata() {
        BenchmarkCommand cmd = new BenchmarkCommand(null, context, consoleUI);
        assertEquals("benchmark", cmd.name());
        assertFalse(cmd.description().isBlank());
    }

    @Test
    @DisplayName("Executes custom questions from CLI arguments")
    void testExecuteWithCustomQuestionsArgs() {
        AtomicReference<BenchmarkRequest> capturedRequest = new AtomicReference<>();
        ApiClient mockClient = new ApiClient("http://localhost:8080") {
            @Override
            public BenchmarkResponse runBenchmark(BenchmarkRequest request) {
                capturedRequest.set(request);
                BenchmarkSummaryDTO summary = new BenchmarkSummaryDTO();
                summary.setTotalQueries(2);
                summary.setSuccessfulQueries(2);
                summary.setHasGroundTruth(false);
                return new BenchmarkResponse(request.getRepositoryName(), "custom", "DENSE_ONLY", 5, summary,
                        List.of());
            }
        };

        BenchmarkCommand cmd = new BenchmarkCommand(mockClient, context, consoleUI,
                new Scanner(new ByteArrayInputStream(new byte[0])));
        CommandResult result = cmd.execute(List.of("javaparser", "Where is parser?", "How are errors handled?"));

        assertEquals(CommandResult.SUCCESS, result);
        assertNotNull(capturedRequest.get());
        assertEquals("javaparser", capturedRequest.get().getRepositoryName());
        assertEquals(2, capturedRequest.get().getQuestions().size());
        assertEquals("Where is parser?", capturedRequest.get().getQuestions().get(0));
        assertEquals("How are errors handled?", capturedRequest.get().getQuestions().get(1));
    }

    @Test
    @DisplayName("Executes predefined suite from CLI arguments")
    void testExecuteWithSuiteArg() {
        AtomicReference<BenchmarkRequest> capturedRequest = new AtomicReference<>();
        ApiClient mockClient = new ApiClient("http://localhost:8080") {
            @Override
            public BenchmarkResponse runBenchmark(BenchmarkRequest request) {
                capturedRequest.set(request);
                BenchmarkSummaryDTO summary = new BenchmarkSummaryDTO();
                summary.setTotalQueries(50);
                summary.setSuccessfulQueries(50);
                summary.setHasGroundTruth(true);
                summary.setHitRateAt5(0.95);
                return new BenchmarkResponse(request.getRepositoryName(), request.getSuiteName(), "DENSE_ONLY", 5,
                        summary, List.of());
            }
        };

        BenchmarkCommand cmd = new BenchmarkCommand(mockClient, context, consoleUI,
                new Scanner(new ByteArrayInputStream(new byte[0])));
        CommandResult result = cmd.execute(List.of("javaparser", "--suite", "default"));

        assertEquals(CommandResult.SUCCESS, result);
        assertNotNull(capturedRequest.get());
        assertEquals("javaparser", capturedRequest.get().getRepositoryName());
        assertEquals("default", capturedRequest.get().getSuiteName());
        assertTrue(capturedRequest.get().getQuestions().isEmpty());
    }

    @Test
    @DisplayName("Interactive execution prompts for repository, mode, custom questions, and options")
    void testInteractiveExecutionWithCustomQuestions() {
        AtomicReference<BenchmarkRequest> capturedRequest = new AtomicReference<>();
        ApiClient mockClient = new ApiClient("http://localhost:8080") {
            @Override
            public BenchmarkResponse runBenchmark(BenchmarkRequest request) {
                capturedRequest.set(request);
                BenchmarkSummaryDTO summary = new BenchmarkSummaryDTO();
                summary.setTotalQueries(1);
                summary.setSuccessfulQueries(1);
                summary.setHasGroundTruth(false);
                return new BenchmarkResponse(request.getRepositoryName(), "custom", "DENSE_ONLY", 10, summary,
                        List.of());
            }
        };

        // Inputs:
        // 1. Repo name: "javaparser"
        // 2. Mode: "2" (custom)
        // 3. Question 1: "Where is NodeList?"
        // 4. Blank line to terminate question input
        // 5. topK: "10"
        // 6. strategy: "" (default DENSE_ONLY)
        String simulatedInput = String.join("\n",
                "javaparser",
                "2",
                "Where is NodeList?",
                "",
                "10",
                "") + "\n";

        Scanner scanner = new Scanner(new ByteArrayInputStream(simulatedInput.getBytes(StandardCharsets.UTF_8)));
        BenchmarkCommand cmd = new BenchmarkCommand(mockClient, context, consoleUI, scanner);

        CommandResult result = cmd.execute(List.of());

        assertEquals(CommandResult.SUCCESS, result);
        assertNotNull(capturedRequest.get());
        assertEquals("javaparser", capturedRequest.get().getRepositoryName());
        assertEquals(1, capturedRequest.get().getQuestions().size());
        assertEquals("Where is NodeList?", capturedRequest.get().getQuestions().get(0));
        assertEquals(10, capturedRequest.get().getTopK());
        assertEquals("DENSE_ONLY", capturedRequest.get().getRetrievalConfiguration());
    }

    @Test
    @DisplayName("Interactive execution defaults to active context repository")
    void testInteractiveExecutionUsesContextRepo() {
        context.setCurrentRepository("active-repo-123");

        AtomicReference<BenchmarkRequest> capturedRequest = new AtomicReference<>();
        ApiClient mockClient = new ApiClient("http://localhost:8080") {
            @Override
            public BenchmarkResponse runBenchmark(BenchmarkRequest request) {
                capturedRequest.set(request);
                BenchmarkSummaryDTO summary = new BenchmarkSummaryDTO();
                summary.setTotalQueries(50);
                summary.setSuccessfulQueries(50);
                return new BenchmarkResponse(request.getRepositoryName(), "default", "DENSE_ONLY", 5, summary,
                        List.of());
            }
        };

        // Inputs:
        // 1. Repo name: "" (press Enter to accept default active-repo-123)
        // 2. Mode: "1" (predefined)
        // 3. topK: "" (default 5)
        // 4. strategy: "" (default DENSE_ONLY)
        String simulatedInput = String.join("\n",
                "",
                "1",
                "",
                "") + "\n";

        Scanner scanner = new Scanner(new ByteArrayInputStream(simulatedInput.getBytes(StandardCharsets.UTF_8)));
        BenchmarkCommand cmd = new BenchmarkCommand(mockClient, context, consoleUI, scanner);

        CommandResult result = cmd.execute(List.of());

        assertEquals(CommandResult.SUCCESS, result);
        assertNotNull(capturedRequest.get());
        assertEquals("active-repo-123", capturedRequest.get().getRepositoryName());
        assertEquals("default", capturedRequest.get().getSuiteName());
    }

    @Test
    @DisplayName("Handles API connection failure gracefully without throwing exception")
    void testApiFailureHandledGracefully() {
        ApiClient failingClient = new ApiClient("http://localhost:8080") {
            @Override
            public BenchmarkResponse runBenchmark(BenchmarkRequest request) {
                throw new ApiException("Backend is unavailable (Connection refused).");
            }
        };

        BenchmarkCommand cmd = new BenchmarkCommand(failingClient, context, consoleUI,
                new Scanner(new ByteArrayInputStream(new byte[0])));
        CommandResult result = cmd.execute(List.of("javaparser", "--suite", "default"));

        assertEquals(CommandResult.SUCCESS, result);
    }

    @Test
    @DisplayName("Executes benchmark with --generate and --source-root flags")
    void testExecuteWithGenerateAndSourceRoot() {
        AtomicReference<BenchmarkRequest> capturedRequest = new AtomicReference<>();
        ApiClient mockClient = new ApiClient("http://localhost:8080") {
            @Override
            public BenchmarkResponse runBenchmark(BenchmarkRequest request) {
                capturedRequest.set(request);
                BenchmarkSummaryDTO summary = new BenchmarkSummaryDTO();
                summary.setTotalQueries(1);
                summary.setSuccessfulQueries(1);
                return new BenchmarkResponse(request.getRepositoryName(), "default", "DENSE_ONLY", 5, summary, List.of());
            }
        };

        BenchmarkCommand cmd = new BenchmarkCommand(mockClient, context, consoleUI,
                new Scanner(new ByteArrayInputStream(new byte[0])));
        CommandResult result = cmd.execute(List.of("javaparser", "--suite", "default", "--generate", "--source-root", "C:/repos/javaparser"));

        assertEquals(CommandResult.SUCCESS, result);
        assertNotNull(capturedRequest.get());
        assertTrue(capturedRequest.get().isEvaluateGeneration());
        assertEquals("C:/repos/javaparser", capturedRequest.get().getSourceRoot());
    }

    @Test
    @DisplayName("Interactive mode prompts for generation evaluation and source root")
    void testInteractiveGenerationPromptYes() {
        AtomicReference<BenchmarkRequest> capturedRequest = new AtomicReference<>();
        ApiClient mockClient = new ApiClient("http://localhost:8080") {
            @Override
            public BenchmarkResponse runBenchmark(BenchmarkRequest request) {
                capturedRequest.set(request);
                BenchmarkSummaryDTO summary = new BenchmarkSummaryDTO();
                summary.setTotalQueries(1);
                summary.setSuccessfulQueries(1);
                return new BenchmarkResponse(request.getRepositoryName(), "default", "DENSE_ONLY", 5, summary, List.of());
            }
        };

        // Inputs: repo, mode (1=predefined), topK (default 5), strategy (default), evaluateGen (y), sourceRoot
        String simulatedInput = String.join("\n",
                "javaparser",
                "1",
                "",
                "",
                "y",
                "C:/repos/javaparser") + "\n";

        Scanner scanner = new Scanner(new ByteArrayInputStream(simulatedInput.getBytes(StandardCharsets.UTF_8)));
        BenchmarkCommand cmd = new BenchmarkCommand(mockClient, context, consoleUI, scanner);

        CommandResult result = cmd.execute(List.of());

        assertEquals(CommandResult.SUCCESS, result);
        assertNotNull(capturedRequest.get());
        assertTrue(capturedRequest.get().isEvaluateGeneration());
        assertEquals("C:/repos/javaparser", capturedRequest.get().getSourceRoot());
    }

    @Test
    @DisplayName("Interactive mode defaults to no generation evaluation on empty enter")
    void testInteractiveGenerationPromptDefaultsNo() {
        AtomicReference<BenchmarkRequest> capturedRequest = new AtomicReference<>();
        ApiClient mockClient = new ApiClient("http://localhost:8080") {
            @Override
            public BenchmarkResponse runBenchmark(BenchmarkRequest request) {
                capturedRequest.set(request);
                BenchmarkSummaryDTO summary = new BenchmarkSummaryDTO();
                summary.setTotalQueries(1);
                summary.setSuccessfulQueries(1);
                return new BenchmarkResponse(request.getRepositoryName(), "default", "DENSE_ONLY", 5, summary, List.of());
            }
        };

        // Inputs: repo, mode (1=predefined), topK (default 5), strategy (default), evaluateGen ("" defaults to N)
        String simulatedInput = String.join("\n",
                "javaparser",
                "1",
                "",
                "",
                "") + "\n";

        Scanner scanner = new Scanner(new ByteArrayInputStream(simulatedInput.getBytes(StandardCharsets.UTF_8)));
        BenchmarkCommand cmd = new BenchmarkCommand(mockClient, context, consoleUI, scanner);

        CommandResult result = cmd.execute(List.of());

        assertEquals(CommandResult.SUCCESS, result);
        assertNotNull(capturedRequest.get());
        assertFalse(capturedRequest.get().isEvaluateGeneration());
    }

    @Test
    @DisplayName("Displays generation quality and citation verification tables when generation evaluation is present")
    void testDisplayResultsWithGenerationSummary() {
        BenchmarkSummaryDTO summary = new BenchmarkSummaryDTO();
        summary.setTotalQueries(1);
        summary.setSuccessfulQueries(1);
        summary.setHasGroundTruth(false);

        BenchmarkGenerationSummaryDTO genSummary = new BenchmarkGenerationSummaryDTO();
        genSummary.setTotalQueriesEvaluated(1);
        genSummary.setSuccessfulGenerations(1);
        genSummary.setFailedGenerations(0);
        genSummary.setBatchCount(1);
        genSummary.setAverageGenerationLatencyMs(250.0);
        genSummary.setAverageJudgeLatencyMs(180.0);
        genSummary.setTotalTokens(500L);
        genSummary.setAverageTokensPerQuery(500.0);
        genSummary.setAverageFaithfulness(0.95);
        genSummary.setAverageRelevancy(0.90);
        genSummary.setAverageCorrectness(0.85);
        genSummary.setAverageCitationPrecision(1.0);
        genSummary.setTotalUngroundedClaims(0);
        genSummary.setAverageCitationCorrectness(1.0);
        genSummary.setAverageExpectedSourceRecall(1.0);
        genSummary.setTotalCitationsAttempted(2);
        genSummary.setTotalCitationsValidSyntax(2);
        genSummary.setTotalCitationsStructurallyValid(2);
        genSummary.setRevisionStatus("MATCHED");
        summary.setGenerationSummary(genSummary);

        QueryBenchmarkResultDTO qr = new QueryBenchmarkResultDTO();
        qr.setQueryId("q1");
        qr.setQuery("Where is LexicalPreservingPrinter?");
        qr.setStatus("SUCCESS");
        qr.setGenerationLatencyMs(250L);
        qr.setJudgeLatencyMs(180L);
        qr.setTotalTokens(500);

        QueryGenerationResultDTO genResult = new QueryGenerationResultDTO();
        genResult.setStatus("SUCCESS");
        genResult.setGeneratedAnswer("It is in LexicalPreservingPrinter.java [cite: LexicalPreservingPrinter.java:10-25].");
        genResult.setFaithfulness(0.95);
        genResult.setRelevancy(0.90);
        genResult.setCorrectness(0.85);
        genResult.setCitationPrecision(1.0);
        qr.setGenerationResult(genResult);

        CitationValidationDTO citeVal = new CitationValidationDTO();
        citeVal.setTotalCitations(1);
        citeVal.setValidSyntaxCitations(1);
        citeVal.setStructurallyValidCitations(1);
        citeVal.setCitationCorrectness(1.0);
        citeVal.setExpectedSourceRecall(1.0);
        citeVal.setRevisionStatus("MATCHED");
        qr.setCitationValidation(citeVal);

        BenchmarkResponse response = new BenchmarkResponse("javaparser", "default", "DENSE_ONLY", 5, summary, List.of(qr));

        ApiClient mockClient = new ApiClient("http://localhost:8080") {
            @Override
            public BenchmarkResponse runBenchmark(BenchmarkRequest request) {
                return response;
            }
        };

        BenchmarkCommand cmd = new BenchmarkCommand(mockClient, context, consoleUI,
                new Scanner(new ByteArrayInputStream(new byte[0])));
        CommandResult result = cmd.execute(List.of("javaparser", "--suite", "default", "--generate"));

        assertEquals(CommandResult.SUCCESS, result);
    }

    @Test
    @DisplayName("Executes benchmark with topK=50 and fewer retrieved chunks without indexing errors")
    void testBenchmarkExecutionWithTopK50AndFewerRetrievedChunks() {
        BenchmarkSummaryDTO summary = new BenchmarkSummaryDTO();
        summary.setTotalQueries(1);
        summary.setSuccessfulQueries(1);
        summary.setHasGroundTruth(true);
        summary.setHitRateAt5(1.0);

        // Filename length 44 reproducing the exact path truncation condition
        String path44 = "com/github/javaparser/ast/visitor/GenericVisitorWithDefaultsPrintVisitor.java";
        List<RetrievedChunkDTO> chunks = new ArrayList<>();
        for (int i = 1; i <= 5; i++) { // 5 chunks returned, topK is 50
            chunks.add(new RetrievedChunkDTO(
                    "c" + i,
                    path44,
                    "GenericVisitorWithDefaultsPrintVisitor",
                    "visit",
                    "METHOD",
                    i,
                    true,
                    3
            ));
        }

        QueryBenchmarkResultDTO qr = new QueryBenchmarkResultDTO(
                "Q1", "How are visitors printed?", "visitor", "EXPLAIN_METHOD",
                "EXPLAIN_METHOD", true, "SUCCESS", null,
                chunks, null, null
        );
        qr.setHasGroundTruth(true);

        BenchmarkResponse response = new BenchmarkResponse("javaparser", "default", "HYBRID", 50, summary, List.of(qr));

        ApiClient mockClient = new ApiClient("http://localhost:8080") {
            @Override
            public BenchmarkResponse runBenchmark(BenchmarkRequest request) {
                return response;
            }
        };

        BenchmarkCommand cmd = new BenchmarkCommand(mockClient, context, consoleUI,
                new Scanner(new ByteArrayInputStream(new byte[0])));

        // topK = 50 specified in CLI arguments
        CommandResult result = cmd.execute(List.of("javaparser", "--suite", "default", "--top-k", "50"));

        assertEquals(CommandResult.SUCCESS, result);
    }

    @Test
    @DisplayName("Executes benchmark with topK=50 on DifferenceApplyRemovedRulesCompleteTest in Q01 and Q18")
    void testBenchmarkExecutionWithTopK50OnExactQ01AndQ18Files() {
        BenchmarkSummaryDTO summary = new BenchmarkSummaryDTO();
        summary.setTotalQueries(2);
        summary.setSuccessfulQueries(2);
        summary.setHasGroundTruth(true);
        summary.setHitRateAt5(1.0);

        String path44 = "C:\\Users\\Shravan\\AppData\\Local\\Temp\\astra-repo-12345\\javaparser-core-testing\\src\\test\\java\\com\\github\\javaparser\\printer\\lexicalpreservation\\DifferenceApplyRemovedRulesCompleteTest.java";
        List<RetrievedChunkDTO> chunks = new ArrayList<>();
        for (int i = 1; i <= 28; i++) {
            chunks.add(new RetrievedChunkDTO(
                    "c" + i,
                    path44,
                    "DifferenceApplyRemovedRulesCompleteTest",
                    "print",
                    "CLASS",
                    i,
                    i == 1,
                    i == 1 ? 3 : 0
            ));
        }

        QueryBenchmarkResultDTO q1 = new QueryBenchmarkResultDTO(
                "Q01", "Where is LexicalPreservingPrinter implemented?", "Class identification", "CLASS",
                "CLASS", true, "SUCCESS", null,
                chunks, null, null
        );
        q1.setHasGroundTruth(true);

        QueryBenchmarkResultDTO q18 = new QueryBenchmarkResultDTO(
                "Q18", "Trace the LexicalPreservingPrinter setup and print workflow", "Workflow", "WORKFLOW",
                "WORKFLOW", true, "SUCCESS", null,
                chunks, null, null
        );
        q18.setHasGroundTruth(true);

        BenchmarkResponse response = new BenchmarkResponse("jp2", "default", "HYBRID_RRF", 50, summary, List.of(q1, q18));

        ApiClient mockClient = new ApiClient("http://localhost:8080") {
            @Override
            public BenchmarkResponse runBenchmark(BenchmarkRequest request) {
                return response;
            }
        };

        BenchmarkCommand cmd = new BenchmarkCommand(mockClient, context, consoleUI,
                new Scanner(new ByteArrayInputStream(new byte[0])));

        CommandResult result = cmd.execute(List.of("jp2", "--suite", "default", "--top-k", "50"));
        assertEquals(CommandResult.SUCCESS, result);
    }
}
