package com.shravan.jcode_intelligence.cli.shell;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Parses raw user input into a {@link ParsedCommand}.
 *
 * <p>Sits between {@link InteractiveShell} (which reads raw strings)
 * and {@link CommandDispatcher} (which only accepts parsed commands).
 */
public class CommandParser {

    /**
     * Parse a raw input line into a command name and argument list.
     *
     * @param rawInput the trimmed, non-empty input from the user
     * @return a {@link ParsedCommand} with lowercased command name and args
     */
    public ParsedCommand parse(String rawInput) {
        String[] tokens = rawInput.split("\\s+");
        String command = tokens[0].toLowerCase();

        List<String> args;
        if (tokens.length > 1) {
            args = Collections.unmodifiableList(
                    Arrays.asList(Arrays.copyOfRange(tokens, 1, tokens.length))
            );
        } else {
            args = List.of();
        }

        return new ParsedCommand(command, args);
    }
}
