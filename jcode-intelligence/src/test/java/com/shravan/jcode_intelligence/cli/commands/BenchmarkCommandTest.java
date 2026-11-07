package com.shravan.jcode_intelligence.cli.commands;

import com.shravan.jcode_intelligence.cli.client.ApiClient;
import com.shravan.jcode_intelligence.cli.client.ApiException;
import com.shravan.jcode_intelligence.cli.shell.CommandResult;
import com.shravan.jcode_intelligence.cli.shell.ShellContext;
import com.shravan.jcode_intelligence.cli.ui.ConsoleUI;
import com.shravan.jcode_intelligence.dto.request.BenchmarkRequest;
import com.shravan.jcode_intelligence.dto.response.BenchmarkResponse;
import com.shravan.jcode_intelligence.dto.response.BenchmarkSummaryDTO;
import com.shravan.jcode_intelligence.dto.response.QueryBenchmarkResultDTO;
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
                return new BenchmarkResponse(request.getRepositoryName(), "custom", "DENSE_ONLY", 5, summary, List.of());
            }
        };

        BenchmarkCommand cmd = new BenchmarkCommand(mockClient, context, consoleUI, new Scanner(new ByteArrayInputStream(new byte[0])));
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
                return new BenchmarkResponse(request.getRepositoryName(), request.getSuiteName(), "DENSE_ONLY", 5, summary, List.of());
            }
        };

        BenchmarkCommand cmd = new BenchmarkCommand(mockClient, context, consoleUI, new Scanner(new ByteArrayInputStream(new byte[0])));
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
                return new BenchmarkResponse(request.getRepositoryName(), "custom", "DENSE_ONLY", 10, summary, List.of());
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
                ""
        ) + "\n";

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
                return new BenchmarkResponse(request.getRepositoryName(), "default", "DENSE_ONLY", 5, summary, List.of());
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
                ""
        ) + "\n";

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

        BenchmarkCommand cmd = new BenchmarkCommand(failingClient, context, consoleUI, new Scanner(new ByteArrayInputStream(new byte[0])));
        CommandResult result = cmd.execute(List.of("javaparser", "--suite", "default"));

        assertEquals(CommandResult.SUCCESS, result);
    }
}

