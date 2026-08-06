package com.shravan.jcode_intelligence.cli.ui;

public class ProgressRenderer {

    public String renderSpinnerMessage(String message) {
        String base = message != null ? message : BunnyDialogue.getProgress();
        return ColorPalette.ACCENT + "⠋ " + ColorPalette.TEXT + base + ColorPalette.RESET;
    }

    public String renderSuccessMessage(String message) {
        String base = message != null ? message : BunnyDialogue.getSuccess();
        return ColorPalette.SUCCESS + "✓ " + base + ColorPalette.RESET;
    }

    public String renderFailureMessage(String message) {
        String base = message != null ? message : BunnyDialogue.getError();
        return ColorPalette.ERROR + "✗ " + base + ColorPalette.RESET;
    }

    public String renderBanner(String message) {
        return ColorPalette.ACCENT + "=== " + message + " ===" + ColorPalette.RESET;
    }
}
