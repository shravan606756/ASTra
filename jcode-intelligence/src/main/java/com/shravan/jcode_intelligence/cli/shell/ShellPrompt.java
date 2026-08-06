package com.shravan.jcode_intelligence.cli.shell;

public class ShellPrompt {
    public static String getPrompt(ShellContext context) {
        if (context.getCurrentRepository() == null || context.getCurrentRepository().isEmpty()) {
            return "astra>";
        } else {
            return context.getCurrentRepository() + ">";
        }
    }
}
