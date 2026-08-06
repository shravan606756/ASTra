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

public class CallsCommand implements Command {
    private final ApiClient apiClient;
    private final ShellContext context;
    private final ConsoleUI consoleUI;

    public CallsCommand(ApiClient apiClient, ShellContext context, ConsoleUI consoleUI) {
        this.apiClient = apiClient;
        this.context = context;
        this.consoleUI = consoleUI;
    }

    @Override
    public String name() { return "calls"; }

    @Override
    public String description() { return "Find callers of a specific method or class"; }

    @Override
    public CommandResult execute(List<String> args) {
        if (context.getCurrentRepository() == null) {
            consoleUI.printError("No active repository selected. Use: use <repository>");
            return CommandResult.SUCCESS;
        }
        if (args.isEmpty()) {
            consoleUI.printError("Usage: calls <MethodName>");
            return CommandResult.SUCCESS;
        }

        ChatRequest request = new ChatRequest("Who calls " + String.join(" ", args) + "?", 8, context.getCurrentRepository());
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

