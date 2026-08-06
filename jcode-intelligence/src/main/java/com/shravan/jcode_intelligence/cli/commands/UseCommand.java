package com.shravan.jcode_intelligence.cli.commands;

import com.shravan.jcode_intelligence.cli.shell.CommandResult;
import com.shravan.jcode_intelligence.cli.shell.ShellContext;
import com.shravan.jcode_intelligence.cli.ui.ConsoleUI;

import java.util.List;

/**
 * Sets the active repository for subsequent commands.
 *
 * <p>Usage: {@code use <repositoryId>}
 */
public class UseCommand implements Command {

    private final ShellContext context;
    private final ConsoleUI consoleUI;

    public UseCommand(ShellContext context, ConsoleUI consoleUI) {
        this.context = context;
        this.consoleUI = consoleUI;
    }

    @Override
    public String name() {
        return "use";
    }

    @Override
    public String description() {
        return "Set the active repository";
    }

    @Override
    public CommandResult execute(List<String> args) {
        if (args.isEmpty()) {
            if (context.getCurrentRepository() == null) {
                consoleUI.printInfo("No repository selected.");
            } else {
                consoleUI.printInfo("Current repository: " + context.getCurrentRepository());
            }
            return CommandResult.SUCCESS;
        }

        String repoId = args.get(0);
        context.setCurrentRepository(repoId);
        consoleUI.printSuccess("Switched to repository: " + repoId);
        return CommandResult.SUCCESS;
    }
}
