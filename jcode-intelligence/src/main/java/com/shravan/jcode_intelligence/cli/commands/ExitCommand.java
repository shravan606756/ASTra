package com.shravan.jcode_intelligence.cli.commands;

import com.shravan.jcode_intelligence.cli.shell.CommandResult;
import com.shravan.jcode_intelligence.cli.ui.ConsoleUI;

import java.util.List;

/**
 * Terminates the interactive shell by returning {@link CommandResult#EXIT}.
 */
public class ExitCommand implements Command {

    private final ConsoleUI consoleUI;

    public ExitCommand(ConsoleUI consoleUI) {
        this.consoleUI = consoleUI;
    }

    @Override
    public String name() {
        return "exit";
    }

    @Override
    public String description() {
        return "Exit the ASTra CLI";
    }

    @Override
    public CommandResult execute(List<String> args) {
        return CommandResult.EXIT;
    }
}
