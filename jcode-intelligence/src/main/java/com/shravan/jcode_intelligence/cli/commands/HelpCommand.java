package com.shravan.jcode_intelligence.cli.commands;

import com.shravan.jcode_intelligence.cli.shell.CommandDispatcher;
import com.shravan.jcode_intelligence.cli.shell.CommandResult;
import com.shravan.jcode_intelligence.cli.ui.ConsoleUI;

import java.util.List;

/**
 * Displays all available commands with their descriptions.
 *
 * <p>Reads from {@link CommandDispatcher#getRegisteredCommands()} to avoid
 * maintaining a duplicate command registry.
 */
public class HelpCommand implements Command {

    private final CommandDispatcher dispatcher;
    private final ConsoleUI consoleUI;

    public HelpCommand(CommandDispatcher dispatcher, ConsoleUI consoleUI) {
        this.dispatcher = dispatcher;
        this.consoleUI = consoleUI;
    }

    @Override
    public String name() {
        return "help";
    }

    @Override
    public String description() {
        return "Show available commands";
    }

    @Override
    public CommandResult execute(List<String> args) {
        consoleUI.showSignBunny("Quick Start", com.shravan.jcode_intelligence.cli.ui.BunnyState.WELCOME);
        consoleUI.printInfo("");
        consoleUI.printInfo("  1. index");
        consoleUI.printInfo("  2. repos");
        consoleUI.printInfo("  3. use");
        consoleUI.printInfo("  4. summary");
        consoleUI.printInfo("  5. ask");
        consoleUI.printInfo("");
        
        consoleUI.printHeader("All Commands");
        
        java.util.Map<String, java.util.List<Command>> groups = new java.util.LinkedHashMap<>();
        groups.put("Repository", new java.util.ArrayList<>());
        groups.put("Context", new java.util.ArrayList<>());
        groups.put("AI Queries", new java.util.ArrayList<>());
        groups.put("System", new java.util.ArrayList<>());
        
        for (Command cmd : dispatcher.getRegisteredCommands()) {
            String name = cmd.name();
            if (List.of("doctor", "repos", "stats", "remove", "index").contains(name)) {
                groups.get("Repository").add(cmd);
            } else if (List.of("use").contains(name)) {
                groups.get("Context").add(cmd);
            } else if (List.of("help", "clear", "exit").contains(name)) {
                groups.get("System").add(cmd);
            } else {
                groups.get("AI Queries").add(cmd);
            }
        }
        
        for (java.util.Map.Entry<String, java.util.List<Command>> entry : groups.entrySet()) {
            if (entry.getValue().isEmpty()) continue;
            consoleUI.printSection(entry.getKey());
            com.shravan.jcode_intelligence.cli.ui.TableRenderer table = new com.shravan.jcode_intelligence.cli.ui.TableRenderer("Command", "Description");
            for (Command cmd : entry.getValue()) {
                table.addRow(cmd.name(), cmd.description());
            }
            consoleUI.printTable(table);
        }
        
        consoleUI.printInfo("");
        consoleUI.printInfo(com.shravan.jcode_intelligence.cli.ui.ColorPalette.ACCENT + "Tip from the bunny: " + com.shravan.jcode_intelligence.cli.ui.BunnyDialogue.getTip() + com.shravan.jcode_intelligence.cli.ui.ColorPalette.RESET);
        
        return CommandResult.SUCCESS;
    }
}
