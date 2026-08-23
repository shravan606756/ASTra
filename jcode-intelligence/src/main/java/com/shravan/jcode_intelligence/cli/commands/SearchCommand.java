package com.shravan.jcode_intelligence.cli.commands;

import com.shravan.jcode_intelligence.cli.client.ApiClient;
import com.shravan.jcode_intelligence.cli.client.ApiException;
import com.shravan.jcode_intelligence.cli.shell.CommandResult;
import com.shravan.jcode_intelligence.cli.shell.ShellContext;
import com.shravan.jcode_intelligence.cli.ui.ConsoleUI;
import com.shravan.jcode_intelligence.dto.request.ChatMode;
import com.shravan.jcode_intelligence.dto.request.ChatRequest;
import com.shravan.jcode_intelligence.dto.response.ChatResponse;
import com.shravan.jcode_intelligence.dto.response.ChunkResponse;

import java.util.List;

public class SearchCommand implements Command {
    private final ApiClient apiClient;
    private final ShellContext context;
    private final ConsoleUI consoleUI;

    public SearchCommand(ApiClient apiClient, ShellContext context, ConsoleUI consoleUI) {
        this.apiClient = apiClient;
        this.context = context;
        this.consoleUI = consoleUI;
    }

    @Override
    public String name() { return "search"; }

    @Override
    public String description() { return "Search for a specific code element in the active repository"; }

    @Override
    public CommandResult execute(List<String> args) {
        if (context.getCurrentRepository() == null) {
            consoleUI.printError("No active repository selected. Use: use <repository>");
            return CommandResult.SUCCESS;
        }
        if (args.isEmpty()) {
            consoleUI.printError("Usage: search <Query>");
            return CommandResult.SUCCESS;
        }

        ChatRequest request = new ChatRequest(String.join(" ", args), 10, context.getCurrentRepository());
        request.setMode(ChatMode.SEARCH);
        consoleUI.startProgressAnimation(com.shravan.jcode_intelligence.cli.ui.BunnyState.SEARCHING, new String[]{"Sniffing around..."});

        try {
            ChatResponse response = apiClient.ask(request);
            consoleUI.printChatResponse(response);
        } catch (ApiException e) {
            consoleUI.printError("Search failed. Reason: " + e.getMessage());
        } finally {
            consoleUI.stopProgressAnimation();
        }
        return CommandResult.SUCCESS;
    }
}
