package com.shravan.jcode_intelligence.cli.commands;

import com.shravan.jcode_intelligence.cli.client.ApiClient;
import com.shravan.jcode_intelligence.cli.client.ApiException;
import com.shravan.jcode_intelligence.cli.shell.CommandResult;
import com.shravan.jcode_intelligence.cli.ui.ConsoleUI;

import java.util.List;

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

            consoleUI.printStats(repoId, stats);
        } catch (ApiException e) {
            consoleUI.printError("Failed to get stats: " + e.getMessage());
        }
        return CommandResult.SUCCESS;
    }
}
