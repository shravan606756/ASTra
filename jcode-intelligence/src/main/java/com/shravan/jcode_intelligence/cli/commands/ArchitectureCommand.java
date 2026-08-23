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

public class ArchitectureCommand implements Command {
    private final ApiClient apiClient;
    private final ShellContext context;
    private final ConsoleUI consoleUI;

    public ArchitectureCommand(ApiClient apiClient, ShellContext context, ConsoleUI consoleUI) {
        this.apiClient = apiClient;
        this.context = context;
        this.consoleUI = consoleUI;
    }

    @Override
    public String name() { return "architecture"; }

    @Override
    public String description() { return "Explain the architecture of the active repository or a specific component"; }

    @Override
    public CommandResult execute(List<String> args) {
        if (context.getCurrentRepository() == null) {
            consoleUI.printError("No active repository selected. Use: use <repository>");
            return CommandResult.SUCCESS;
        }
        
        String query = args.isEmpty() ? "Explain the overall architecture" : String.join(" ", args);
        ChatRequest request = new ChatRequest(query, 10, context.getCurrentRepository());
        request.setMode(ChatMode.ARCHITECTURE);
        consoleUI.startProgressAnimation(com.shravan.jcode_intelligence.cli.ui.BunnyState.READING, new String[]{"Studying the blueprints..."});

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
