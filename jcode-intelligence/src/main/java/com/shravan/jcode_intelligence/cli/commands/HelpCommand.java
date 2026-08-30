package com.shravan.jcode_intelligence.cli.commands;

import com.shravan.jcode_intelligence.cli.shell.CommandDispatcher;
import com.shravan.jcode_intelligence.cli.shell.CommandResult;
import com.shravan.jcode_intelligence.cli.ui.ConsoleUI;

import java.util.List;

/**
 * Displays all available commands with their descriptions.
 *
 * <p>
 * Reads from {@link CommandDispatcher#getRegisteredCommands()} to avoid
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
        String accent = com.shravan.jcode_intelligence.cli.ui.ColorPalette.ACCENT;
        String text = com.shravan.jcode_intelligence.cli.ui.ColorPalette.TEXT;
        String muted = com.shravan.jcode_intelligence.cli.ui.ColorPalette.MUTED;
        String reset = com.shravan.jcode_intelligence.cli.ui.ColorPalette.RESET;

        System.out.println(accent + "ASTra :: Code Intelligence CLI" + reset);
        System.out.println(muted + "────────────────────────────────────────────────────────" + reset);
        System.out.println();
        System.out.println(text + "QUICK START" + reset);
        System.out.println(text + "[1] index" + reset);
        System.out.println(text + "[2] repos" + reset);
        System.out.println(text + "[3] use" + reset);
        System.out.println(text + "[4] summary" + reset);
        System.out.println(text + "[5] ask" + reset);
        System.out.println();

        java.util.Map<String, java.util.List<Command>> groups = new java.util.LinkedHashMap<>();
        groups.put("REPOSITORY", new java.util.ArrayList<>());
        groups.put("CONTEXT", new java.util.ArrayList<>());
        groups.put("AI QUERIES", new java.util.ArrayList<>());
        groups.put("SYSTEM", new java.util.ArrayList<>());

        for (Command cmd : dispatcher.getRegisteredCommands()) {
            String name = cmd.name();
            if (List.of("doctor", "repos", "stats", "remove", "index").contains(name)) {
                groups.get("REPOSITORY").add(cmd);
            } else if (List.of("use").contains(name)) {
                groups.get("CONTEXT").add(cmd);
            } else if (List.of("help", "clear", "exit").contains(name)) {
                groups.get("SYSTEM").add(cmd);
            } else {
                groups.get("AI QUERIES").add(cmd);
            }
        }

        for (java.util.Map.Entry<String, java.util.List<Command>> entry : groups.entrySet()) {
            if (entry.getValue().isEmpty())
                continue;

            System.out.println(text + entry.getKey() + reset);
            System.out.println();
            for (Command cmd : entry.getValue()) {
                System.out.printf("%s> %-13s %s%s%s%n", accent, cmd.name(), text, cmd.description(), reset);
            }
            System.out.println();
        }

        System.out.println(muted + "────────────────────────────────────────────────────────" + reset);
        System.out.println();

        return CommandResult.SUCCESS;
    }
}
