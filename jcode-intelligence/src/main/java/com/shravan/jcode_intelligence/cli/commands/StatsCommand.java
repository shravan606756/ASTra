package com.shravan.jcode_intelligence.cli.commands;

import com.shravan.jcode_intelligence.cli.client.ApiClient;
import com.shravan.jcode_intelligence.cli.client.ApiException;
import com.shravan.jcode_intelligence.cli.shell.CommandResult;
import com.shravan.jcode_intelligence.cli.ui.ConsoleUI;

import java.util.List;
import java.util.Map;

/**
 * Retrieves chunk count and type breakdown statistics for a given repository.
 */
public class StatsCommand implements Command {

    private final ApiClient apiClient;
    private final ConsoleUI consoleUI;

    public StatsCommand(ApiClient apiClient, ConsoleUI consoleUI) {
        this.apiClient = apiClient;
        this.consoleUI = consoleUI;
    }

    @Override
    public String name() {
        return "stats";
    }

    @Override
    public String description() {
        return "Show indexing statistics for a repository";
    }

    @Override
    public CommandResult execute(List<String> args) {
        if (args.isEmpty()) {
            consoleUI.printError("Usage: stats <repositoryId>");
            return CommandResult.SUCCESS;
        }

        String repoId = args.get(0);
        try {
            com.shravan.jcode_intelligence.dto.response.RepositoryStatsDTO stats = apiClient.getRepositoryStats(repoId);
            
            if (stats.getTotalChunks() == 0) {
                consoleUI.printInfo("Repository '" + repoId + "' not found or contains no indexed chunks.");
                return CommandResult.SUCCESS;
            }

            consoleUI.printHeader("Repository Statistics");
            System.out.println("Repository : " + repoId);
            System.out.println();
            
            System.out.println("Code Structure");
            System.out.println("──────────────────────────────");
            System.out.printf("%-18s %d%n", "Packages", stats.getPackages());
            System.out.printf("%-18s %d%n", "Classes", stats.getClasses());
            System.out.printf("%-18s %d%n", "Interfaces", stats.getInterfaces());
            System.out.printf("%-18s %d%n", "Enums", stats.getEnums());
            System.out.printf("%-18s %d%n", "Records", stats.getRecords());
            System.out.println();
            
            System.out.println("Members");
            System.out.println("──────────────────────────────");
            System.out.printf("%-18s %d%n", "Fields", stats.getFields());
            System.out.printf("%-18s %d%n", "Constructors", stats.getConstructors());
            System.out.printf("%-18s %d%n", "Methods", stats.getMethods());
            System.out.printf("%-18s %d%n", "Fragments", stats.getFragments());
            System.out.println();
            
            System.out.println("Summary");
            System.out.println("──────────────────────────────");
            System.out.printf("%-18s %d%n", "Total Chunks", stats.getTotalChunks());
            System.out.printf("%-18s %s%n", "Largest Class", stats.getLargestClass() != null ? stats.getLargestClass() : "N/A");
            System.out.printf("%-18s %s%n", "Largest Method", stats.getLargestMethod() != null ? stats.getLargestMethod() : "N/A");
            System.out.printf("%-18s %.1f s%n", "Indexing Time", stats.getIndexingTimeMs() / 1000.0);
            System.out.println();
            
        } catch (ApiException e) {
            consoleUI.printError("Failed to get stats: " + e.getMessage());
        }
        return CommandResult.SUCCESS;
    }

    private String formatNumber(Object num) {
        if (num instanceof Number) {
            return String.valueOf(((Number) num).intValue());
        }
        return String.valueOf(num);
    }
}
