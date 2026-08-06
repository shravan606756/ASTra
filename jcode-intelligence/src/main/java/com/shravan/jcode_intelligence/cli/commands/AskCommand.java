package com.shravan.jcode_intelligence.cli.commands;

import com.shravan.jcode_intelligence.cli.client.ApiClient;
import com.shravan.jcode_intelligence.cli.client.ApiException;
import com.shravan.jcode_intelligence.cli.shell.CommandResult;
import com.shravan.jcode_intelligence.cli.shell.ShellContext;
import com.shravan.jcode_intelligence.cli.ui.ConsoleUI;
import com.shravan.jcode_intelligence.dto.request.ChatRequest;
import com.shravan.jcode_intelligence.dto.response.ChatResponse;

import com.shravan.jcode_intelligence.dto.response.ChunkResponse;

import java.util.List;

/**
 * Sends a question to the backend LLM chat endpoint for the active repository.
 */
public class AskCommand implements Command {

    private final ApiClient apiClient;
    private final ShellContext context;
    private final ConsoleUI consoleUI;

    public AskCommand(ApiClient apiClient, ShellContext context, ConsoleUI consoleUI) {
        this.apiClient = apiClient;
        this.context = context;
        this.consoleUI = consoleUI;
    }

    @Override
    public String name() {
        return "ask";
    }

    @Override
    public String description() {
        return "Ask a question about the active repository";
    }

    @Override
    public CommandResult execute(List<String> args) {
        if (context.getCurrentRepository() == null) {
            consoleUI.printError("No active repository selected.");
            consoleUI.printInfo("Use:");
            consoleUI.printInfo("  use <repository>");
            consoleUI.printInfo("before asking questions.");
            return CommandResult.SUCCESS;
        }

        if (args.isEmpty()) {
            consoleUI.printError("Usage: ask <your question here>");
            return CommandResult.SUCCESS;
        }

        String question = String.join(" ", args);
        ChatRequest request = new ChatRequest(question, 5, context.getCurrentRepository());

        consoleUI.startProgressAnimation(com.shravan.jcode_intelligence.cli.ui.BunnyDialogue.getThinkingAnimation());

        try {
            ChatResponse response = apiClient.ask(request);
            consoleUI.stopProgressAnimation();
            consoleUI.printChatResponse(response);
        } catch (ApiException e) {
            consoleUI.stopProgressAnimation();
            consoleUI.printError("Failed to get answer: " + e.getMessage());
        }

        return CommandResult.SUCCESS;
    }
}
