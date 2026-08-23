package com.shravan.jcode_intelligence.cli.commands;

import com.shravan.jcode_intelligence.cli.client.ApiClient;
import com.shravan.jcode_intelligence.cli.client.ApiException;
import com.shravan.jcode_intelligence.cli.shell.CommandResult;
import com.shravan.jcode_intelligence.cli.ui.ConsoleUI;
import com.shravan.jcode_intelligence.dto.request.IndexRequest;
import com.shravan.jcode_intelligence.dto.response.IndexResponse;

import java.util.List;

/**
 * Commands the backend to index a local or remote Git repository.
 */
public class IndexCommand implements Command {

    private final ApiClient apiClient;
    private final ConsoleUI consoleUI;

    public IndexCommand(ApiClient apiClient, ConsoleUI consoleUI) {
        this.apiClient = apiClient;
        this.consoleUI = consoleUI;
    }

    @Override
    public String name() {
        return "index";
    }

    @Override
    public String description() {
        return "Index a repository (local path or git url)";
    }

    @Override
    public CommandResult execute(List<String> args) {
        if (args.isEmpty()) {
            consoleUI.printError("Usage: index <path_or_url> [repositoryId]");
            return CommandResult.SUCCESS;
        }

        String target = args.get(0);
        String repositoryId = args.size() > 1 ? args.get(1) : null;

        IndexRequest request = new IndexRequest();
        if (target.startsWith("http://") || target.startsWith("https://") || target.startsWith("git@")) {
            request.setGitUrl(target);
        } else {
            request.setProjectPath(target);
        }
        request.setRepositoryId(repositoryId);

        consoleUI.startProgressAnimation(com.shravan.jcode_intelligence.cli.ui.BunnyState.DIGGING, com.shravan.jcode_intelligence.cli.ui.BunnyDialogue.getIndexingAnimation());

        try {
            IndexResponse response = apiClient.index(request);
            if ("SUCCESS".equalsIgnoreCase(response.getStatus())) {
                consoleUI.showSignBunny("Repository mapped!", com.shravan.jcode_intelligence.cli.ui.BunnyState.SUCCESS);
                
                com.shravan.jcode_intelligence.cli.ui.TableRenderer table = new com.shravan.jcode_intelligence.cli.ui.TableRenderer("Repository ID", "Indexed Chunks");
                table.addRow(response.getRepositoryId(), String.valueOf(response.getIndexedChunksCount()));
                consoleUI.printTable(table);
            } else {
                consoleUI.printError("Indexing failed.");
                consoleUI.printError("Reason: " + response.getMessage());
            }
        } catch (ApiException e) {
            consoleUI.printError("Indexing failed due to an error.");
            consoleUI.printError("Reason: " + e.getMessage());
        } finally {
            consoleUI.stopProgressAnimation();
        }

        return CommandResult.SUCCESS;
    }
}
