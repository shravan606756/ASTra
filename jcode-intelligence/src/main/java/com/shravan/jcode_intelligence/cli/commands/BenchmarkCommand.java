package com.shravan.jcode_intelligence.cli.commands;

import com.shravan.jcode_intelligence.cli.client.ApiClient;
import com.shravan.jcode_intelligence.cli.client.ApiException;
import com.shravan.jcode_intelligence.cli.shell.CommandResult;
import com.shravan.jcode_intelligence.cli.shell.ShellContext;
import com.shravan.jcode_intelligence.cli.ui.BenchmarkTableFormatter;
import com.shravan.jcode_intelligence.cli.ui.BunnyState;
import com.shravan.jcode_intelligence.cli.ui.ConsoleUI;
import com.shravan.jcode_intelligence.cli.ui.TableRenderer;
import com.shravan.jcode_intelligence.dto.request.BenchmarkRequest;
import com.shravan.jcode_intelligence.dto.response.BenchmarkResponse;
import com.shravan.jcode_intelligence.dto.response.BenchmarkSummaryDTO;
import com.shravan.jcode_intelligence.dto.response.QueryBenchmarkResultDTO;
import com.shravan.jcode_intelligence.dto.response.QueryBenchmarkResultDTO.RetrievedChunkDTO;

import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/**
 * CLI command to execute deterministic retrieval benchmarks against an indexed repository.
 * Supports both predefined ground-truth benchmark suites and developer-provided custom questions.
 */
public class BenchmarkCommand implements Command {

    private final ApiClient apiClient;
    private final ShellContext context;
    private final ConsoleUI consoleUI;
    private final Scanner scanner;

    public BenchmarkCommand(ApiClient apiClient, ShellContext context, ConsoleUI consoleUI) {
        this(apiClient, context, consoleUI, new Scanner(System.in));
    }

    public BenchmarkCommand(ApiClient apiClient, ShellContext context, ConsoleUI consoleUI, Scanner scanner) {
        this.apiClient = apiClient;
        this.context = context;
        this.consoleUI = consoleUI;
        this.scanner = scanner;
    }

    @Override
    public String name() {
        return "benchmark";
    }

    @Override
    public String description() {
        return "Run retrieval evaluation benchmark against an indexed repository";
    }

    @Override
    public CommandResult execute(List<String> args) {
        String repositoryName = null;
        List<String> customQuestions = new ArrayList<>();
        String suiteName = null;
        int topK = 5;
        String retrievalStrategy = "DENSE_ONLY";

        // Parse command line arguments if provided
        if (!args.isEmpty()) {
            if ("--help".equalsIgnoreCase(args.get(0)) || "-h".equalsIgnoreCase(args.get(0))) {
                printUsage();
                return CommandResult.SUCCESS;
            }

            repositoryName = args.get(0);

            if (args.size() > 1) {
                if ("--suite".equalsIgnoreCase(args.get(1))) {
                    suiteName = (args.size() > 2) ? args.get(2) : "default";
                } else {
                    for (int i = 1; i < args.size(); i++) {
                        String q = args.get(i).trim();
                        if (!q.isEmpty()) {
                            customQuestions.add(q);
                        }
                    }
                }
            }
        }

        // Interactive prompts for missing parameters
        if (repositoryName == null || repositoryName.isBlank()) {
            String activeRepo = context != null ? context.getCurrentRepository() : null;
            if (activeRepo != null && !activeRepo.isBlank()) {
                repositoryName = prompt("Enter repository name [default: " + activeRepo + "]: ");
                if (repositoryName.isBlank()) {
                    repositoryName = activeRepo;
                }
            } else {
                repositoryName = prompt("Enter repository name: ");
            }
        }

        if (repositoryName.isBlank()) {
            consoleUI.printError("Repository name cannot be empty. Aborting benchmark.");
            return CommandResult.SUCCESS;
        }

        // If neither suite nor questions were specified via args, prompt for execution mode
        if (suiteName == null && customQuestions.isEmpty()) {
            consoleUI.printInfo("Select benchmark mode:");
            consoleUI.printInfo("  [1] Predefined benchmark suite (default-benchmark.json - 50 questions)");
            consoleUI.printInfo("  [2] Custom questions");
            String modeChoice = prompt("Selection [1/2] (default: 1): ");

            if ("2".equals(modeChoice)) {
                consoleUI.printInfo("Enter custom questions (one per line, press Enter on an empty line to finish):");
                while (true) {
                    String q = prompt("? ");
                    if (q.isBlank()) {
                        break;
                    }
                    customQuestions.add(q);
                }

                if (customQuestions.isEmpty()) {
                    consoleUI.printError("No questions entered. Aborting benchmark.");
                    return CommandResult.SUCCESS;
                }
            } else {
                suiteName = "default";
            }

            String topKInput = prompt("Enter topK (default: 5): ");
            if (!topKInput.isBlank()) {
                try {
                    int parsed = Integer.parseInt(topKInput.trim());
                    if (parsed > 0) {
                        topK = parsed;
                    }
                } catch (NumberFormatException e) {
                    consoleUI.printWarning("Invalid topK value '" + topKInput + "', using default 5.");
                }
            }

            String strategyInput = prompt("Enter retrieval strategy [DENSE_ONLY] (default: DENSE_ONLY): ");
            if (!strategyInput.isBlank()) {
                retrievalStrategy = strategyInput.trim();
            }
        }

        // Build BenchmarkRequest
        BenchmarkRequest request;
        if (!customQuestions.isEmpty()) {
            request = new BenchmarkRequest(repositoryName, customQuestions);
        } else {
            request = new BenchmarkRequest(repositoryName, suiteName != null ? suiteName : "default", topK, retrievalStrategy, false);
        }
        request.setTopK(topK);
        request.setRetrievalConfiguration(retrievalStrategy);

        consoleUI.startProgressAnimation(BunnyState.THINKING, new String[]{"Running retrieval benchmark..."});

        try {
            BenchmarkResponse response = apiClient.runBenchmark(request);
            consoleUI.stopProgressAnimation();
            displayBenchmarkResults(response);
        } catch (ApiException e) {
            consoleUI.stopProgressAnimation();
            consoleUI.printError("Benchmark execution failed: " + e.getMessage());
        } catch (Exception e) {
            consoleUI.stopProgressAnimation();
            consoleUI.printError("Unexpected error during benchmark execution: " + e.getMessage());
        }

        return CommandResult.SUCCESS;
    }

    private String prompt(String message) {
        System.out.print(message);
        System.out.flush();
        if (scanner != null && scanner.hasNextLine()) {
            return scanner.nextLine().trim();
        }
        return "";
    }

    private void printUsage() {
        consoleUI.printInfo("Usage:");
        consoleUI.printInfo("  benchmark                                    Interactive execution");
        consoleUI.printInfo("  benchmark <repository>                       Interactive execution for repository");
        consoleUI.printInfo("  benchmark <repository> --suite [suiteName]   Run predefined suite");
        consoleUI.printInfo("  benchmark <repository> \"<q1>\" [\"<q2>\" ...]  Run custom questions");
    }

    private void displayBenchmarkResults(BenchmarkResponse response) {
        BenchmarkSummaryDTO summary = response.getSummary();
        boolean hasFailures = summary != null && summary.getFailedQueries() > 0;

        consoleUI.printHeader("RETRIEVAL BENCHMARK RESULTS");

        if (hasFailures) {
            consoleUI.printWarning("[FAILED] Benchmark execution completed with " + summary.getFailedQueries() + " failure(s).");
        } else {
            consoleUI.printSuccess("[SUCCESS] Benchmark execution completed successfully.");
        }

        System.out.println();

        // 1. Overview Table
        TableRenderer overviewTable = new TableRenderer("Repo", "Mode", "Queries", "Success", "Failed", "Avg Latency", "Avg Chunks");
        overviewTable.addRow(
                response.getRepositoryName() != null ? response.getRepositoryName() : response.getRepositoryId(),
                response.getMode() != null ? response.getMode() : response.getSuiteName(),
                String.valueOf(summary != null ? summary.getTotalQueries() : 0),
                String.valueOf(summary != null ? summary.getSuccessfulQueries() : 0),
                String.valueOf(summary != null ? summary.getFailedQueries() : 0),
                String.format("%.1f ms", summary != null ? summary.getAvgTotalLatencyMs() : 0.0),
                String.format("%.2f", summary != null ? summary.getAvgRetrievedChunks() : 0.0)
        );
        consoleUI.printTable(overviewTable);
        System.out.println();

        // 2. Retrieval Quality Metrics Table (if ground truth available)
        if (summary != null && summary.isHasGroundTruth() && summary.getHitRateAt5() != null) {
            consoleUI.printSection("Retrieval Quality Metrics (Ground Truth)");
            TableRenderer qualityTable = new TableRenderer("Metric", "Cutoff @ 3", "Cutoff @ 5", "Cutoff @ 25");
            qualityTable.addRow("Hit Rate", formatPct(summary.getHitRateAt3()), formatPct(summary.getHitRateAt5()), formatPct(summary.getHitRateAt25()));
            qualityTable.addRow("Recall", formatPct(summary.getRecallAt3()), formatPct(summary.getRecallAt5()), formatPct(summary.getRecallAt25()));
            qualityTable.addRow("Precision", formatPct(summary.getPrecisionAt3()), formatPct(summary.getPrecisionAt5()), formatPct(summary.getPrecisionAt25()));
            qualityTable.addRow("NDCG", formatNum(summary.getNdcgAt3()), formatNum(summary.getNdcgAt5()), formatNum(summary.getNdcgAt25()));
            consoleUI.printTable(qualityTable);

            TableRenderer globalTable = new TableRenderer("MRR", "Context Precision", "Context Recall");
            globalTable.addRow(formatNum(summary.getMrr()), formatNum(summary.getContextPrecision()), formatNum(summary.getContextRecall()));
            consoleUI.printTable(globalTable);
            System.out.println();
        } else {
            consoleUI.printInfo("Note: Retrieval quality metrics (Recall, Precision, MRR, NDCG) are omitted because no ground truth relevance annotations were provided.");
            System.out.println();
        }

        // 3. Per-Question Detailed Results
        if (response.getResults() != null && !response.getResults().isEmpty()) {
            consoleUI.printSection("Question Results (" + response.getResults().size() + ")");
            for (int i = 0; i < response.getResults().size(); i++) {
                QueryBenchmarkResultDTO qr = response.getResults().get(i);
                String qStatus = qr.getStatus();
                long latency = qr.getOperationalMetrics() != null ? qr.getOperationalMetrics().getTotalLatencyMs() : 0;
                int chunkCount = qr.getRetrievedChunks() != null ? qr.getRetrievedChunks().size() : 0;

                System.out.printf("[%d/%d] %s (%s, %d ms, %d chunks)%n",
                        i + 1, response.getResults().size(), qr.getQuery(), qStatus, latency, chunkCount);

                if ("FAILED".equalsIgnoreCase(qStatus)) {
                    consoleUI.printError("   Error: " + qr.getErrorMessage());
                } else if (qr.getRetrievedChunks() != null && !qr.getRetrievedChunks().isEmpty()) {
                    List<RetrievedChunkDTO> displayChunks = qr.getRetrievedChunks();
                    if (response.getTopK() > 0 && displayChunks.size() > response.getTopK()) {
                        displayChunks = displayChunks.subList(0, response.getTopK());
                    }
                    TableRenderer chunkTable = BenchmarkTableFormatter.formatChunksTable(displayChunks, qr.isHasGroundTruth());
                    consoleUI.printTable(chunkTable);
                }
                System.out.println();
            }
        }
    }

    private String formatPct(Double val) {
        if (val == null) return "N/A";
        return String.format("%.1f%%", val * 100.0);
    }

    private String formatNum(Double val) {
        if (val == null) return "N/A";
        return String.format("%.4f", val);
    }
}

