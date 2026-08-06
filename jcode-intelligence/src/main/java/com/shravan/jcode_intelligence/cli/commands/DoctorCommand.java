package com.shravan.jcode_intelligence.cli.commands;

import com.shravan.jcode_intelligence.cli.client.ApiClient;
import com.shravan.jcode_intelligence.cli.client.ApiException;
import com.shravan.jcode_intelligence.cli.shell.CommandResult;
import com.shravan.jcode_intelligence.cli.ui.ConsoleUI;

import java.util.List;

/**
 * Validates backend connectivity using the /actuator/health endpoint.
 */
public class DoctorCommand implements Command {

    private final ApiClient apiClient;
    private final ConsoleUI consoleUI;

    public DoctorCommand(ApiClient apiClient, ConsoleUI consoleUI) {
        this.apiClient = apiClient;
        this.consoleUI = consoleUI;
    }

    @Override
    public String name() {
        return "doctor";
    }

    @Override
    public String description() {
        return "Check backend connectivity";
    }

    @Override
    public CommandResult execute(List<String> args) {
        consoleUI.printHeader("Backend Status");
        consoleUI.startProgressAnimation(com.shravan.jcode_intelligence.cli.ui.BunnyState.SEARCHING, new String[]{"Checking backend..."});
        try {
            boolean isUp = apiClient.health();
            consoleUI.stopProgressAnimation();
            if (isUp) {
                consoleUI.printSuccess("Backend is healthy.\nReady to answer your questions!");
            } else {
                consoleUI.printError("Backend reachable but status is not UP.");
            }
        } catch (ApiException e) {
            consoleUI.stopProgressAnimation();
            consoleUI.printError("Backend is unavailable.\nStart it using:\n    .\\mvnw.cmd spring-boot:run");
        }
        return CommandResult.SUCCESS; // Shell continues even if doctor fails
    }
}
