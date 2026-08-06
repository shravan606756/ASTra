package com.shravan.jcode_intelligence.cli.shell;

import com.shravan.jcode_intelligence.cli.commands.Command;
import com.shravan.jcode_intelligence.cli.ui.ConsoleUI;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maintains a registry of {@link Command} instances and dispatches
 * {@link ParsedCommand} requests to the matching command.
 *
 * <p>This is the single source of truth for all registered commands.
 * {@code HelpCommand} reads from this registry rather than maintaining its own.
 */
public class CommandDispatcher {

    private final Map<String, Command> registry;
    private final ConsoleUI consoleUI;

    /**
     * Create a dispatcher with an initial set of commands.
     *
     * @param commands  the initial commands to register
     * @param consoleUI used to print "Unknown command" errors
     */
    public CommandDispatcher(List<Command> commands, ConsoleUI consoleUI) {
        this.consoleUI = consoleUI;
        this.registry = new LinkedHashMap<>();
        for (Command cmd : commands) {
            registry.put(cmd.name(), cmd);
        }
    }

    /**
     * Register an additional command after construction.
     * Used to resolve circular dependencies (e.g. HelpCommand ↔ CommandDispatcher).
     */
    public void register(Command command) {
        registry.put(command.name(), command);
    }

    /**
     * Dispatch a parsed command to its registered handler.
     *
     * @param parsed the parsed command from {@link CommandParser}
     * @return the {@link CommandResult} from the executed command,
     *         or {@code CommandResult.ERROR} if the command is unknown
     */
    public CommandResult dispatch(ParsedCommand parsed) {
        Command command = registry.get(parsed.command());
        if (command == null) {
            consoleUI.printError("Unknown command: " + parsed.command());
            return CommandResult.ERROR;
        }
        return command.execute(parsed.args());
    }

    /**
     * Returns a read-only view of all registered commands.
     * Used by {@code HelpCommand} to enumerate available commands.
     */
    public Collection<Command> getRegisteredCommands() {
        return Collections.unmodifiableCollection(registry.values());
    }
}
