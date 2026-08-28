package com.shravan.jcode_intelligence.cli.util;

import java.util.Scanner;

public class TerminalUtils {

    /** Best-effort terminal width fallback */
    private static final int DEFAULT_WIDTH = 80;
    private static int cachedWidth = 0;

    public static int getTerminalWidth() {
        if (cachedWidth > 0)
            return cachedWidth;

        String columns = System.getenv("COLUMNS");
        if (columns != null) {
            try {
                int parsed = Integer.parseInt(columns.trim());
                if (parsed > 0) {
                    cachedWidth = parsed;
                    return parsed;
                }
            } catch (NumberFormatException ignored) {
            }
        }

        try {
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                Process p = new ProcessBuilder("cmd", "/c", "mode con").start();
                try (Scanner sc = new Scanner(p.getInputStream())) {
                    while (sc.hasNextLine()) {
                        String line = sc.nextLine();
                        if (line.contains("Columns:") || line.contains("columns:")) {
                            cachedWidth = Integer.parseInt(line.replaceAll("[^0-9]", ""));
                            return cachedWidth;
                        }
                    }
                }
            } else {
                Process p = new ProcessBuilder("sh", "-c", "tput cols").start();
                try (Scanner sc = new Scanner(p.getInputStream())) {
                    if (sc.hasNextInt()) {
                        cachedWidth = sc.nextInt();
                        return cachedWidth;
                    }
                }
            }
        } catch (Exception ignored) {
        }

        cachedWidth = DEFAULT_WIDTH;
        return DEFAULT_WIDTH;
    }

    public static String repeat(char c, int count) {
        return String.valueOf(c).repeat(Math.max(0, count));
    }

    public static int visibleLength(String text) {
        if (text == null)
            return 0;
        return text.replaceAll("\u001B\\[[;\\d]*[mK]", "")
                .replaceAll("\u001B\\]8;;.*?(?:\u0007|\u001B\\\\)", "")
                .length();
    }

    public static String center(String text, int width) {
        int vLen = visibleLength(text);
        if (vLen >= width)
            return text;
        int padding = width - vLen;
        int left = padding / 2;
        int right = padding - left;
        return repeat(' ', left) + text + repeat(' ', right);
    }

    public static String centerLeft(String text, int width) {
        int vLen = visibleLength(text);
        if (vLen >= width)
            return text;
        int padding = width - vLen;
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
