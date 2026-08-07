package com.shravan.jcode_intelligence.cli.ui;

public class BunnyRenderer {

    public static String renderFrame(String[] frame) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < frame.length; i++) {
            sb.append(ColorPalette.MUTED).append(frame[i]).append(ColorPalette.RESET);
            if (i < frame.length - 1) {
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    public static String renderCenteredFrame(String[] frame) {
        StringBuilder sb = new StringBuilder();
        int width = com.shravan.jcode_intelligence.cli.util.TerminalUtils.getTerminalWidth();
        for (int i = 0; i < frame.length; i++) {
            String centeredLine = com.shravan.jcode_intelligence.cli.util.TerminalUtils.centerLeft(frame[i], width);
            sb.append(ColorPalette.MUTED).append(centeredLine).append(ColorPalette.RESET);
            if (i < frame.length - 1) {
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    public static String renderSmall(BunnyState state) {
        String[] frame = BunnyAnimation.get(state).getFrames().get(0);
        return renderFrame(frame);
    }

    public static String renderBubble(BunnyState state, String message) {
        String[] frame = BunnyAnimation.get(state).getFrames().get(0);
        int len = message.length();
        String topBorder = "╭" + "─".repeat(len + 2) + "╮";
        String bottomBorder = "╰" + "─".repeat(len + 2) + "╯";
        
        StringBuilder sb = new StringBuilder();
        sb.append(ColorPalette.MUTED).append(topBorder).append("\n");
        sb.append("│ ").append(ColorPalette.TEXT).append(message).append(ColorPalette.MUTED).append(" │\n");
        sb.append(bottomBorder).append("\n");
        
        for (int i = 0; i < frame.length; i++) {
            sb.append(frame[i]);
            if (i < frame.length - 1) {
                sb.append("\n");
            }
        }
        sb.append(ColorPalette.RESET);
        
        return sb.toString();
    }

    // Legacy support to match old method name if necessary, though we will update ConsoleUI
    public static String renderSign(BunnyState state, String message) {
        return renderBubble(state, message);
    }
}
