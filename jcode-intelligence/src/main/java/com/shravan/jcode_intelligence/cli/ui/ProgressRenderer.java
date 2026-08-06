package com.shravan.jcode_intelligence.cli.ui;

public class ProgressRenderer {

    public String renderSpinnerMessage(String message) {
        String base = message != null ? message : BunnyDialogue.getDialogue(BunnyState.THINKING);
        return ColorPalette.ACCENT + "⠋ " + ColorPalette.TEXT + base + ColorPalette.RESET;
    }

    public String renderSuccessMessage(String message) {
        String base = message != null ? message : BunnyDialogue.getDialogue(BunnyState.SUCCESS);
        return ColorPalette.SUCCESS + "✓ " + base + ColorPalette.RESET;
    }

    public String renderFailureMessage(String message) {
        String base = message != null ? message : BunnyDialogue.getDialogue(BunnyState.ERROR);
        return ColorPalette.ERROR + "✗ " + base + ColorPalette.RESET;
    }

    public String renderBanner(String message) {
        return ColorPalette.ACCENT + "=== " + message + " ===" + ColorPalette.RESET;
    }
}
