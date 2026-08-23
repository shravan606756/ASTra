package com.shravan.jcode_intelligence.cli.util;

public class TerminalUtils {

    /** Best-effort terminal width fallback */
    private static final int DEFAULT_WIDTH = 80;

    public static int getTerminalWidth() {
        String columns = System.getenv("COLUMNS");
        if (columns != null) {
            try {
                int parsed = Integer.parseInt(columns.trim());
                if (parsed > 0) {
                    return parsed;
                }
            } catch (NumberFormatException ignored) {
                // Fallback
            }
        }
        return DEFAULT_WIDTH;
    }

    public static String repeat(char c, int count) {
        return String.valueOf(c).repeat(Math.max(0, count));
    }

    public static String center(String text, int width) {
        if (text.length() >= width) return text;
        int padding = width - text.length();
        int left = padding / 2;
        int right = padding - left;
        return repeat(' ', left) + text + repeat(' ', right);
    }

    public static String centerLeft(String text, int width) {
        if (text.length() >= width) return text;
        int padding = width - text.length();
        int left = padding / 2;
        return repeat(' ', left) + text;
    }

    public static String renderSeparator(String title) {
        int width = getTerminalWidth();
        if (title == null || title.isEmpty()) {
            return repeat('─', width);
        }
        String paddedTitle = " " + title + " ";
        int lineLength = Math.max(0, (width - paddedTitle.length()) / 2);
        String line = repeat('─', lineLength);
        return line + paddedTitle + line + (width % 2 != paddedTitle.length() % 2 ? "─" : "");
    }
}
