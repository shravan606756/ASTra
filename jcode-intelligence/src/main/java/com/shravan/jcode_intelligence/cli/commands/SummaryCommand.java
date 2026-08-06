package com.shravan.jcode_intelligence.cli.commands;

import com.shravan.jcode_intelligence.cli.client.ApiClient;
import com.shravan.jcode_intelligence.cli.client.ApiException;
import com.shravan.jcode_intelligence.cli.shell.CommandResult;
import com.shravan.jcode_intelligence.cli.shell.ShellContext;
import com.shravan.jcode_intelligence.cli.ui.ConsoleUI;
import com.shravan.jcode_intelligence.dto.request.ChatMode;
import com.shravan.jcode_intelligence.dto.request.ChatRequest;
import com.shravan.jcode_intelligence.dto.response.ChatResponse;

import java.util.List;

public class SummaryCommand implements Command {
    private final ApiClient apiClient;
    private final ShellContext context;
    private final ConsoleUI consoleUI;

    public SummaryCommand(ApiClient apiClient, ShellContext context, ConsoleUI consoleUI) {
        this.apiClient = apiClient;
        this.context = context;
        this.consoleUI = consoleUI;
    }

    @Override
    public String name() { return "summary"; }

    @Override
    public String description() { return "Provide a high-level summary of the active project"; }

    @Override
    public CommandResult execute(List<String> args) {
        if (context.getCurrentRepository() == null) {
            consoleUI.printError("No active repository selected. Use: use <repository>");
            return CommandResult.SUCCESS;
        }

        String query = args.isEmpty() ? "Provide a project summary" : String.join(" ", args);
        ChatRequest request = new ChatRequest(query, 10, context.getCurrentRepository());
        request.setMode(ChatMode.PROJECT_SUMMARY);
        
        consoleUI.startProgressAnimation(com.shravan.jcode_intelligence.cli.ui.BunnyDialogue.getThinkingAnimation());

        try {
            ChatResponse response = apiClient.ask(request);
            consoleUI.stopProgressAnimation();
            consoleUI.printChatResponse(response);
        } catch (ApiException e) {
            consoleUI.stopProgressAnimation();
            consoleUI.printError("Failed to get summary. Reason: " + e.getMessage());
        }
        return CommandResult.SUCCESS;
    }
}

