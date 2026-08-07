package com.shravan.jcode_intelligence.cli.shell;

import com.shravan.jcode_intelligence.cli.ui.ConsoleUI;

import java.util.Scanner;

/**
 * The main REPL loop for the ASTra interactive CLI.
 *
 * <p>Reads user input, delegates parsing to {@link CommandParser},
 * dispatches via {@link CommandDispatcher}, and interprets the
 * {@link CommandResult} to control the loop lifecycle.
 */
public class InteractiveShell {

    private final ConsoleUI consoleUI;
    private final CommandParser parser;
    private final CommandDispatcher dispatcher;
    private final ShellContext context;

    public InteractiveShell(ConsoleUI consoleUI, CommandParser parser,
                            CommandDispatcher dispatcher, ShellContext context) {
        this.consoleUI = consoleUI;
        this.parser = parser;
        this.dispatcher = dispatcher;
        this.context = context;
    }

    /**
     * Start the REPL loop. Displays the banner once, then reads
     * and dispatches commands until {@link CommandResult#EXIT} is received.
     */
    public void start() {
        java.util.List<String> tryCommands = dispatcher.getRegisteredCommands().stream()
                .map(com.shravan.jcode_intelligence.cli.commands.Command::name)
                .filter(name -> java.util.List.of("index", "use", "ask").contains(name))
                .toList();
        
        consoleUI.printBanner(tryCommands);

        boolean running = true;

        try (Scanner scanner = new Scanner(System.in)) {
            while (running) {
                consoleUI.printPrompt(ShellPrompt.getPrompt(context));

                if (!scanner.hasNextLine()) {
                    break;
                }

                String input = scanner.nextLine().trim();
                consoleUI.stopIdleAnimation();

                if (input.isEmpty()) {
                    continue;
                }

                ParsedCommand parsed = parser.parse(input);
                CommandResult result = dispatcher.dispatch(parsed);

                if (result == CommandResult.EXIT) {
                    consoleUI.printFarewell();
                    running = false;
                }
            }
        }
    }
}
