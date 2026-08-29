package com.shravan.jcode_intelligence.cli.ui;

import com.shravan.jcode_intelligence.cli.util.Ansi;
import com.shravan.jcode_intelligence.cli.util.TerminalUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Formats raw LLM responses into a clean, normalized CLI presentation.
 */
public class AnswerFormatter {

    private static final int MARGIN = 4;
    private static final String BULLET_MARKERS = "[*\\-•]";
    private static final Pattern TREE_CHARS = Pattern.compile("[└├│┌┐┘┤┬┴┼─►→]");

    public String format(String rawAnswer) {
        if (rawAnswer == null || rawAnswer.isBlank()) {
            return "";
        }

        String text = rawAnswer.replace("\r\n", "\n").replace("\r", "\n");

        // Strip raw LLM-generated ANSI escape codes to prevent formatting leakage
        text = text.replaceAll("\u001B\\[[;\\d]*[mK]", "");
        // Catch hallucinated literal formatting brackets like "[1m" or "[37m"
        text = text.replaceAll("(?i)\\[\\d+;?\\d*m", "");

        // Suppress LLM generated dividers (e.g. ──────────────────── or ---)
        text = text.replaceAll("(?m)^[─*_=\\-]{3,}\\s*$", "");

        StringBuilder result = new StringBuilder();
        String[] parts = text.split("```", -1);

        for (int i = 0; i < parts.length; i++) {
            if (i % 2 == 1) {
                // Code block — sacred, emit as-is
                result.append("\n");
                result.append(formatCodeBlock(parts[i]));
                result.append("\n\n");
            } else {
                // Text block
                result.append(formatTextBlock(parts[i]));
            }
        }

        // FIX 1: Trim using a safe loop that preserves ESC (0x1B).
        // Java's \s matches control chars including ESC, which strips ANSI prefixes.
        String output = cleanExcessiveBlankLines(result.toString());
        int start = 0;
        while (start < output.length()) {
            char ch = output.charAt(start);
            if (ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r')
                start++;
            else
                break;
        }
        int end = output.length();
        while (end > start) {
            char ch = output.charAt(end - 1);
            if (ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r')
                end--;
            else
                break;
        }
        return output.substring(start, end);
    }

    private String formatTextBlock(String text) {
        if (text.isBlank())
            return "";

        String[] lines = text.split("\n", -1);
        StringBuilder sb = new StringBuilder();

        List<String> paragraphBuffer = new ArrayList<>();
        List<String> tableBuffer = new ArrayList<>();
        String currentIndent = "";

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];

            if (isTableRow(line)) {
                flushParagraph(paragraphBuffer, currentIndent, sb);
                tableBuffer.add(line);
                continue;
            } else {
                flushTable(tableBuffer, currentIndent, sb);
            }

            // FIX 2: Detect key/value stat blocks.
            // Pattern: label-line, blank, value-line, blank — repeated 3+ times.
            if (!line.isBlank()) {
                int kvResult = tryCompactKeyValueBlock(lines, i, currentIndent, sb, paragraphBuffer);
                if (kvResult > i) {
                    i = kvResult - 1; // -1 because the for loop will i++
                    continue;
                }
            }

            // Preserve lines containing tree-drawing characters as-is
            if (TREE_CHARS.matcher(line).find() && !line.isBlank()) {
                flushParagraph(paragraphBuffer, currentIndent, sb);
                sb.append(currentIndent).append(line.trim()).append("\n");
                continue;
            }

            if (line.isBlank() && !paragraphBuffer.isEmpty()) {
                flushParagraph(paragraphBuffer, currentIndent, sb);
                sb.append("\n");
                continue;
            } else if (line.isBlank()) {
                sb.append("\n");
                continue;
            }

            // Section headers: === Question === or # Heading
            Matcher headerMatcher = Pattern
                    .compile("^\\s*(?:={1,}\\s*(.*?)\\s*={1,}|#{1,6}\\s+(.*)|(TL;DR|SOURCES|ANSWER|QUESTION))\\s*$")
                    .matcher(line);
            if (headerMatcher.matches()) {
                flushParagraph(paragraphBuffer, currentIndent, sb);
                String title = headerMatcher.group(1) != null ? headerMatcher.group(1)
                        : (headerMatcher.group(2) != null ? headerMatcher.group(2) : headerMatcher.group(3));

                sb.append("\n").append(ColorPalette.ACCENT).append(Ansi.BOLD)
                        .append(formatInline(title.toUpperCase()))
                        .append(ColorPalette.RESET).append("\n\n");
                currentIndent = "";
                continue;
            }

            // FIX 3: Numbered heading with reduced indentation.
            Matcher numMatcher = Pattern.compile("^\\s*(\\d+)\\.\\s+(.*)").matcher(line);
            if (numMatcher.matches()) {
                flushParagraph(paragraphBuffer, currentIndent, sb);
                String num = numMatcher.group(1);
                String content = numMatcher.group(2);

                sb.append("\n")
                        .append(ColorPalette.ACCENT).append(Ansi.BOLD)
                        .append(num).append(". ").append(ColorPalette.RESET)
                        .append(Ansi.BOLD).append(formatInline(content)).append(ColorPalette.RESET)
                        .append("\n");

                currentIndent = "   ";
                continue;
            }

            // Bullets
            Matcher bullMatcher = Pattern.compile("^\\s*" + BULLET_MARKERS + "\\s+(.*)").matcher(line);
            if (bullMatcher.matches()) {
                flushParagraph(paragraphBuffer, currentIndent, sb);
                String content = bullMatcher.group(1);

                Matcher labelValMatcher = Pattern.compile("^(.*?)([:\\u2014\\-])(.*)$").matcher(content);
                if (labelValMatcher.matches()) {
                    String label = labelValMatcher.group(1).trim();
                    String sep = labelValMatcher.group(2);
                    String val = labelValMatcher.group(3).trim();

                    sb.append("   ")
                            .append(ColorPalette.ACCENT).append("• ").append(Ansi.BOLD).append(formatInline(label))
                            .append(ColorPalette.RESET);

                    if (!val.isEmpty()) {
                        sb.append(" ").append(ColorPalette.MUTED).append(sep).append(ColorPalette.RESET)
                                .append(" ").append(formatInline(val));
                    }
                    sb.append("\n");
                } else {
                    sb.append("   ")
                            .append(ColorPalette.ACCENT).append("• ").append(ColorPalette.RESET)
                            .append(formatInline(content)).append("\n");
                }

                currentIndent = "     ";
                continue;
            }

            paragraphBuffer.add(line);
        }

        flushParagraph(paragraphBuffer, currentIndent, sb);
        flushTable(tableBuffer, currentIndent, sb);

        return sb.toString();
    }

    private boolean isLineBlank(String line) {
        return line == null || line.matches("^[\\s\\p{Zs}]*$");
    }

    private String trimUnicode(String line) {
        return line.replaceAll("^[\\s\\p{Zs}]+|[\\s\\p{Zs}]+$", "");
    }

    private int measureIndent(String line) {
        return line.length() - line.replaceAll("^[\\s\\p{Zs}]+", "").length();
    }

    /**
     * FIX 2: Tries to detect a key/value stat block starting at line index
     * {@code start}.
     *
     * Pattern in the raw LLM output (after stripping):
     * [indented] Label (short, ≤40 visible chars, single word-ish)
     * [blank]
     * [more-indented] Value (short, ≤30 visible chars, typically a number)
     * [blank]
     * ... repeated 3+ times ...
     *
     * If found, emits a compact aligned block and returns the line index AFTER the
     * block.
     * If not found, returns {@code start} (unchanged).
     */
    private int tryCompactKeyValueBlock(String[] lines, int start, String indent,
            StringBuilder sb, List<String> paragraphBuffer) {
        int scan = start;
        List<String[]> pairs = new ArrayList<>();

        while (scan < lines.length) {
            // Expect label line: non-blank, short, not structural
            if (scan >= lines.length || isLineBlank(lines[scan]))
                break;
            String labelTrimmed = trimUnicode(lines[scan]);
            String labelVis = formatInline(labelTrimmed);

            // Label guards
            if (TerminalUtils.visibleLength(labelVis) > 40
                    || labelTrimmed.contains("|")
                    || TREE_CHARS.matcher(labelTrimmed).find()
                    || labelTrimmed.startsWith("=")) {
                break;
            }
            int labelLineIdx = scan;
            scan++;

            // Optional blank line between label and value
            if (scan < lines.length && isLineBlank(lines[scan])) {
                scan++;
            }

            // Expect value line (more indented, short)
            if (scan >= lines.length || isLineBlank(lines[scan]))
                break;
            String valueTrimmed = trimUnicode(lines[scan]);
            String valueVis = formatInline(valueTrimmed);

            if (TerminalUtils.visibleLength(valueVis) > 30 || valueTrimmed.contains("|")
                    || TREE_CHARS.matcher(valueTrimmed).find())
                break;

            // Value line must be indented more than label line
            int labelIndentLen = measureIndent(lines[labelLineIdx]);
            int valueIndentLen = measureIndent(lines[scan]);
            if (valueIndentLen <= labelIndentLen)
                break;

            pairs.add(new String[] { labelVis, valueVis });
            scan++;

            // Expect blank line after value (or end of input)
            if (scan < lines.length && isLineBlank(lines[scan])) {
                scan++;
            } else {
                break;
            }
        }

        // Only compact if we found 3+ pairs (a genuine stat block)
        if (pairs.size() >= 3) {
            flushParagraph(paragraphBuffer, indent, sb);

            int maxLabel = 0;
            for (String[] p : pairs) {
                maxLabel = Math.max(maxLabel, TerminalUtils.visibleLength(p[0]));
            }

            sb.append("\n");
            for (String[] p : pairs) {
                int visLen = TerminalUtils.visibleLength(p[0]);
                int pad = maxLabel - visLen + 2;
                sb.append(indent)
                        .append(ColorPalette.MUTED).append(p[0]).append(ColorPalette.RESET)
                        .append(" ".repeat(Math.max(1, pad)))
                        .append(p[1])
                        .append("\n");
            }
            sb.append("\n");
            return scan;
        }

        return start; // no block found
    }

    private boolean isTableRow(String line) {
        String t = line.trim();
        if (t.isEmpty())
            return false;
        long nPipes = t.chars().filter(c -> c == '|').count();
        return nPipes >= 2;
    }

    private void flushParagraph(List<String> buffer, String indent, StringBuilder sb) {
        if (buffer.isEmpty())
            return;

        String text = String.join(" ", buffer).replaceAll("\\s+", " ").trim();
        buffer.clear();

        if (text.isEmpty())
            return;

        text = formatInline(text);

        int width = Math.max(40, TerminalUtils.getTerminalWidth() - MARGIN) - indent.length();
        String wrapped = wrapText(text, width);

        String[] lines = wrapped.split("\n");
        for (String l : lines) {
            sb.append(indent).append(l).append("\n");
        }
    }

    private void flushTable(List<String> buffer, String indent, StringBuilder sb) {
        if (buffer.isEmpty())
            return;

        List<List<String>> rows = new ArrayList<>();
        for (String line : buffer) {
            String trimmed = line.trim();
            if (trimmed.startsWith("|"))
                trimmed = trimmed.substring(1);
            if (trimmed.endsWith("|"))
                trimmed = trimmed.substring(0, trimmed.length() - 1);

            String[] cols = trimmed.split("\\|");

            boolean isSeparator = true;
            List<String> row = new ArrayList<>();
            for (String c : cols) {
                String colText = c.trim();
                if (!colText.matches("^[\\-\\:\\s]+$"))
                    isSeparator = false;
                row.add(colText);
            }

            if (!isSeparator)
                rows.add(row);
        }
        buffer.clear();

        if (rows.isEmpty())
            return;

        int numCols = 0;
        for (List<String> r : rows) {
            if (r.size() > numCols)
                numCols = r.size();
        }

        int[] colWidths = new int[numCols];
        for (List<String> r : rows) {
            for (int i = 0; i < Math.min(r.size(), numCols); i++) {
                int len = TerminalUtils.visibleLength(formatInline(r.get(i)));
                colWidths[i] = Math.max(colWidths[i], len);
            }
        }

        int maxTableWidth = Math.max(40, TerminalUtils.getTerminalWidth() - MARGIN - indent.length());
        int totalReq = 0;
        for (int w : colWidths)
            totalReq += w;
        totalReq += (numCols - 1) * 3;

        if (totalReq > maxTableWidth && numCols > 0) {
            // Render as vertical cards since it exceeds terminal width
            sb.append("\n");
            List<String> headers = rows.get(0);

            int cardIndex = 1;
            for (int i = 1; i < rows.size(); i++) {
                List<String> row = rows.get(i);
                if (row.isEmpty())
                    continue;

                String primaryVal = formatInline(row.get(0).trim());

                sb.append(indent)
                        .append(ColorPalette.ACCENT).append(Ansi.BOLD).append(cardIndex + ". ")
                        .append(ColorPalette.RESET)
                        .append(Ansi.BOLD).append(primaryVal).append(ColorPalette.RESET).append("\n\n");

                for (int j = 1; j < numCols; j++) {
                    String header = j < headers.size() ? headers.get(j).trim() : "Column " + j;
                    String val = j < row.size() ? row.get(j).trim() : "";

                    if (!val.isEmpty()) {
                        if (header.equalsIgnoreCase("Value") || header.equalsIgnoreCase("Type")
                                || header.equalsIgnoreCase("Description") || header.isEmpty()) {
                            String wrappedVal = wrapText(formatInline(val), maxTableWidth - 3);
                            for (String vl : wrappedVal.split("\n")) {
                                sb.append(indent).append("   ").append(vl).append("\n");
                            }
                            sb.append("\n");
                        } else {
                            sb.append(indent).append("   ").append(ColorPalette.MUTED).append(header)
                                    .append(ColorPalette.RESET).append("\n");
                            String wrappedVal = wrapText(formatInline(val), maxTableWidth - 5);
                            for (String vl : wrappedVal.split("\n")) {
                                sb.append(indent).append("     ").append(vl).append("\n");
                            }
                            sb.append("\n");
                        }
                    }
                }
                cardIndex++;
            }
            return;
        }

        sb.append("\n");
        List<String> horizontalHeaders = rows.get(0);
        for (int i = 0; i < rows.size(); i++) {
            List<String> r = rows.get(i);

            boolean compactValueTable = numCols == 2 && (horizontalHeaders.size() < 2
                    || horizontalHeaders.get(1).equalsIgnoreCase("Value")
                    || horizontalHeaders.get(1).equalsIgnoreCase("Count")
                    || horizontalHeaders.get(1).isEmpty());

            if (compactValueTable) {
                if (i == 0)
                    continue;
                sb.append(indent).append("   ").append(Ansi.BOLD).append(formatInline(r.get(0)))
                        .append(ColorPalette.RESET).append("\n");
                if (r.size() > 1) {
                    String wrapped = wrapText(formatInline(r.get(1)), maxTableWidth - 6);
                    for (String l : wrapped.split("\n")) {
                        sb.append(indent).append("      ").append(l).append("\n");
                    }
                }
                sb.append("\n");
                continue;
            }

            sb.append(indent);
            for (int j = 0; j < numCols; j++) {
                String cellText = j < r.size() ? r.get(j) : "";
                cellText = formatInline(cellText);
                int visLen = TerminalUtils.visibleLength(cellText);
                int pad = Math.max(0, colWidths[j] - visLen);
                sb.append(cellText).append(" ".repeat(pad));
                if (j < numCols - 1)
                    sb.append("   ");
            }
            sb.append("\n");

            if (i == 0) {
                sb.append(indent);
                for (int j = 0; j < numCols; j++) {
                    sb.append(ColorPalette.MUTED).append("─".repeat(Math.max(1, colWidths[j])))
                            .append(ColorPalette.RESET);
                    if (j < numCols - 1)
                        sb.append("   ");
                }
                sb.append("\n");
            }
        }
        sb.append("\n");
    }

    private String formatCodeBlock(String code) {
        while (code.endsWith("\n"))
            code = code.substring(0, code.length() - 1);
        while (code.startsWith("\n"))
            code = code.substring(1);

        String[] lines = code.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i == 0 && lines[i].matches("^[a-zA-Z0-9]+$")) {
                sb.append(ColorPalette.MUTED).append(lines[i]).append(ColorPalette.RESET).append("\n");
            } else {
                sb.append(lines[i]).append("\n");
            }
        }
        return sb.toString();
    }

    private String formatInline(String text) {
        if (text == null)
            return "";
        text = text.replaceAll("\\*\\*(.*?)\\*\\*", Ansi.BOLD + "$1" + ColorPalette.RESET);
        text = text.replaceAll("`([^`]+)`", ColorPalette.MUTED + "$1" + ColorPalette.RESET);
        return text;
    }

    private String wrapText(String text, int width) {
        if (width <= 0)
            return text;
        String[] words = text.split(" ");
        StringBuilder result = new StringBuilder();
        StringBuilder currentLine = new StringBuilder();

        for (String w : words) {
            int wLen = TerminalUtils.visibleLength(w);
            int cLen = TerminalUtils.visibleLength(currentLine.toString());

            if (cLen + (cLen > 0 ? 1 : 0) + wLen > width) {
                if (currentLine.length() > 0) {
                    result.append(currentLine.toString()).append("\n");
                    currentLine.setLength(0);
                }
                currentLine.append(w);
            } else {
                if (currentLine.length() > 0)
                    currentLine.append(" ");
                currentLine.append(w);
            }
        }
        if (currentLine.length() > 0)
            result.append(currentLine.toString());
        return result.toString();
    }

    private String cleanExcessiveBlankLines(String text) {
        String[] parts = text.split("```", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i % 2 == 1) {
                sb.append("```").append(parts[i]).append("```");
            } else {
                String t = parts[i].replaceAll("(?m)^\\s+$", "");
                t = t.replaceAll("\n{3,}", "\n\n");
                sb.append(t);
            }
        }
        return sb.toString();
    }
}
