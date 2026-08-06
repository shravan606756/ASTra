package com.shravan.jcode_intelligence.cli.commands;

import com.shravan.jcode_intelligence.cli.shell.CommandResult;

import java.util.List;

/**
 * Contract for all CLI commands.
 *
 * <p>Commands are thin presentation adapters: they parse arguments,
 * delegate to an API client or local state, and format output via ConsoleUI.
 * No business logic should exist inside a command.
 */
public interface Command {

    /** The command name used to invoke this command (e.g. "index", "ask"). */
    String name();

    /** A short description shown by the help command. */
    String description();

    /**
     * Execute the command with the given arguments.
     *
     * @param args the arguments following the command name
     * @return a {@link CommandResult} signalling outcome to the shell
     */
    CommandResult execute(List<String> args);
}
