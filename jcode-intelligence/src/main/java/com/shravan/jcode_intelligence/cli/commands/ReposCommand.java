package com.shravan.jcode_intelligence.cli.commands;

import com.shravan.jcode_intelligence.cli.client.ApiClient;
import com.shravan.jcode_intelligence.cli.client.ApiException;
import com.shravan.jcode_intelligence.cli.shell.CommandResult;
import com.shravan.jcode_intelligence.cli.ui.ConsoleUI;

import java.util.List;

/**
 * Lists all distinct repository IDs indexed in the backend.
 */
public class ReposCommand implements Command {

    private final ApiClient apiClient;
    private final ConsoleUI consoleUI;

    public ReposCommand(ApiClient apiClient, ConsoleUI consoleUI) {
        this.apiClient = apiClient;
        this.consoleUI = consoleUI;
    }

    @Override
    public String name() {
        return "repos";
    }

    @Override
    public String description() {
        return "List all indexed repositories";
    }

    @Override
    public CommandResult execute(List<String> args) {
        try {
            List<String> repos = apiClient.listRepositories();
            if (repos == null || repos.isEmpty()) {
                consoleUI.printInfo("No repositories indexed.");
            } else {
                consoleUI.printHeader("Indexed Repositories");
                com.shravan.jcode_intelligence.cli.ui.TableRenderer table = new com.shravan.jcode_intelligence.cli.ui.TableRenderer("Repository ID");
                for (String repo : repos) {
                    table.addRow(repo);
                }
                consoleUI.printTable(table);
            }
        } catch (ApiException e) {
            consoleUI.printError("Failed to list repositories: " + e.getMessage());
        }
        return CommandResult.SUCCESS;
    }
}
