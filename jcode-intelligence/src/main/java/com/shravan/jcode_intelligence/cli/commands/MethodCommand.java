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

public class MethodCommand implements Command {
    private final ApiClient apiClient;
    private final ShellContext context;
    private final ConsoleUI consoleUI;

    public MethodCommand(ApiClient apiClient, ShellContext context, ConsoleUI consoleUI) {
        this.apiClient = apiClient;
        this.context = context;
        this.consoleUI = consoleUI;
    }

    @Override
    public String name() { return "method"; }

    @Override
    public String description() { return "Explain a method in the active repository"; }

    @Override
    public CommandResult execute(List<String> args) {
        if (context.getCurrentRepository() == null) {
            consoleUI.printError("No active repository selected. Use: use <repository>");
            return CommandResult.SUCCESS;
        }
        if (args.isEmpty()) {
            consoleUI.printError("Usage: method <MethodName>");
            return CommandResult.SUCCESS;
        }

        ChatRequest request = new ChatRequest(String.join(" ", args), 5, context.getCurrentRepository());
        request.setMode(ChatMode.EXPLAIN_METHOD);
        consoleUI.startProgressAnimation(com.shravan.jcode_intelligence.cli.ui.BunnyState.THINKING, new String[]{"Walking through the method..."});

        try {
            ChatResponse response = apiClient.ask(request);
            consoleUI.printChatResponse(response);
        } catch (ApiException e) {
            consoleUI.printError("Failed to get explanation. Reason: " + e.getMessage());
        } finally {
            consoleUI.stopProgressAnimation();
        }
        return CommandResult.SUCCESS;
    }
}
