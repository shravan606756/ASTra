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

public class DesignCommand implements Command {
    private final ApiClient apiClient;
    private final ShellContext context;
    private final ConsoleUI consoleUI;

    public DesignCommand(ApiClient apiClient, ShellContext context, ConsoleUI consoleUI) {
        this.apiClient = apiClient;
        this.context = context;
        this.consoleUI = consoleUI;
    }

    @Override
    public String name() { return "design"; }

    @Override
    public String description() { return "Explain design patterns and principles used in the active project"; }

    @Override
    public CommandResult execute(List<String> args) {
        if (context.getCurrentRepository() == null) {
            consoleUI.printError("No active repository selected. Use: use <repository>");
            return CommandResult.SUCCESS;
        }
        if (args.isEmpty()) {
            consoleUI.printError("Usage: design <Query (e.g. patterns in component X)>");
            return CommandResult.SUCCESS;
        }

        ChatRequest request = new ChatRequest(String.join(" ", args), 7, context.getCurrentRepository());
        request.setMode(ChatMode.DESIGN);
        
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

