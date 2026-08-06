package com.shravan.jcode_intelligence.cli.ui;

import java.util.Random;

public class BunnyRenderer {
    private static final Random RANDOM = new Random();

    private static final String[] FACES_DEFAULT = {
            "(•.•)", "(^.^)", "(o.o)", "(•ᴗ•)"
    };

    private static String getRandomFace(BunnyState state) {
        return switch (state) {
            case THINKING -> "(-.-)";
            case READING -> "(o.o)";
            case SEARCHING -> "(o.o)";
            case SUCCESS -> "(^.^)";
            case DIGGING -> "(-.-)";
            case ERROR -> "(>.<)";
            case CONFUSED -> "(•-•)?";
            case WELCOME, GOODBYE -> "(^.^)";
            default -> FACES_DEFAULT[RANDOM.nextInt(FACES_DEFAULT.length)];
        };
    }

    public static String renderSmall(BunnyState state) {
        String face = getRandomFace(state);
        // We use a small left padding to offset the bunny naturally
        return ColorPalette.MUTED + " (\\_/)\n" +
               " " + face + "\n" +
               " / >_" + ColorPalette.RESET;
    }

    public static String renderSign(BunnyState state, String message) {
        String face = getRandomFace(state);
        int len = message.length();
        String border = "+" + "-".repeat(len + 2) + "+";
        
        StringBuilder sb = new StringBuilder();
        sb.append(ColorPalette.MUTED).append(border).append("\n");
        sb.append("| ").append(ColorPalette.TEXT).append(message).append(ColorPalette.MUTED).append(" |\n");
        sb.append(border).append("\n");
        sb.append(" (\\_/)\n");
        sb.append(" ").append(face).append("\n");
        sb.append(" / >_").append(ColorPalette.RESET);
        
        return sb.toString();
    }
}
