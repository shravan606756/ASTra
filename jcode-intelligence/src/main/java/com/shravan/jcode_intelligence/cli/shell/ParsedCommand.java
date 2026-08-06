package com.shravan.jcode_intelligence.cli.shell;

import java.util.List;

/**
 * Immutable representation of a parsed user command.
 *
 * @param command the command name (lowercased)
 * @param args    the remaining arguments after the command name
 */
public record ParsedCommand(String command, List<String> args) {
}
