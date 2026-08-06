package com.shravan.jcode_intelligence.cli.commands;

import com.shravan.jcode_intelligence.cli.shell.CommandResult;
import com.shravan.jcode_intelligence.cli.ui.ConsoleUI;

import java.util.List;

/**
 * Clears the terminal screen.
 */
public class ClearCommand implements Command {

    private final ConsoleUI consoleUI;

    public ClearCommand(ConsoleUI consoleUI) {
        this.consoleUI = consoleUI;
    }

    @Override
    public String name() {
        return "clear";
    }

    @Override
    public String description() {
        return "Clear the terminal screen";
    }

    @Override
    public CommandResult execute(List<String> args) {
        consoleUI.clearScreen();
        return CommandResult.SUCCESS;
    }
}
