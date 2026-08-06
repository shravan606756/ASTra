package com.shravan.jcode_intelligence.cli.shell;

/**
 * Signals returned by {@link com.shravan.jcode_intelligence.cli.commands.Command#execute}
 * to communicate execution outcome to {@link InteractiveShell}.
 */
public enum CommandResult {

    /** Command completed normally. */
    SUCCESS,

    /** Command encountered an error (already printed via ConsoleUI). */
    ERROR,

    /** Shell should terminate. */
    EXIT
}
