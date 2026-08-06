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

public class DependenciesCommand implements Command {
    private final ApiClient apiClient;
    private final ShellContext context;
    private final ConsoleUI consoleUI;

    public DependenciesCommand(ApiClient apiClient, ShellContext context, ConsoleUI consoleUI) {
        this.apiClient = apiClient;
        this.context = context;
        this.consoleUI = consoleUI;
    }

    @Override
    public String name() { return "dependencies"; }

    @Override
    public String description() { return "List dependencies or find what uses a specific class/method"; }

    @Override
    public CommandResult execute(List<String> args) {
        if (context.getCurrentRepository() == null) {
            consoleUI.printError("No active repository selected. Use: use <repository>");
            return CommandResult.SUCCESS;
        }
        if (args.isEmpty()) {
            consoleUI.printError("Usage: dependencies <Class/Method>");
            return CommandResult.SUCCESS;
        }

        ChatRequest request = new ChatRequest("What are the dependencies for " + String.join(" ", args) + "?", 8, context.getCurrentRepository());
        request.setMode(ChatMode.RELATIONSHIP);
        consoleUI.startProgressAnimation(com.shravan.jcode_intelligence.cli.ui.BunnyDialogue.getThinkingAnimation());

        try {
            ChatResponse response = apiClient.ask(request);
            consoleUI.stopProgressAnimation();
            consoleUI.printChatResponse(response);
        } catch (ApiException e) {
            consoleUI.stopProgressAnimation();
            consoleUI.printError("Failed to get explanation. Reason: " + e.getMessage());
        }
        return CommandResult.SUCCESS;
    }
}

