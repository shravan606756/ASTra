package com.shravan.jcode_intelligence.cli.commands;

import com.shravan.jcode_intelligence.cli.client.ApiClient;
import com.shravan.jcode_intelligence.cli.client.ApiException;
import com.shravan.jcode_intelligence.cli.shell.CommandResult;
import com.shravan.jcode_intelligence.cli.shell.ShellContext;
import com.shravan.jcode_intelligence.cli.ui.ConsoleUI;

import java.util.List;

/**
 * Removes all indexed vectors for a specific repository via the backend.
 */
public class RemoveCommand implements Command {

    private final ApiClient apiClient;
    private final ShellContext context;
    private final ConsoleUI consoleUI;

    public RemoveCommand(ApiClient apiClient, ShellContext context, ConsoleUI consoleUI) {
        this.apiClient = apiClient;
        this.context = context;
        this.consoleUI = consoleUI;
    }

    @Override
    public String name() {
        return "remove";
    }

    @Override
    public String description() {
        return "Remove an indexed repository";
    }

    @Override
    public CommandResult execute(List<String> args) {
        if (args.isEmpty()) {
            consoleUI.printError("Usage: remove <repositoryId>");
            return CommandResult.SUCCESS; // Return SUCCESS to not break the shell loop
        }

        String repoId = args.get(0);
        consoleUI.printProgress("Removing repository: " + repoId + "...");
        try {
            boolean removed = apiClient.removeRepository(repoId);
            if (removed) {
                consoleUI.printSuccess(com.shravan.jcode_intelligence.cli.ui.BunnyDialogue.getRemoveSuccess());

                // If the user removed the active repository, clear the context
                if (repoId.equals(context.getCurrentRepository())) {
                    context.setCurrentRepository(null);
                }
            } else {
                consoleUI.printWarning("Repository '" + repoId + "' not found.");
            }
        } catch (ApiException e) {
            consoleUI.printError("Failed to remove repository: " + e.getMessage());
        }
        
        return CommandResult.SUCCESS;
    }
}
