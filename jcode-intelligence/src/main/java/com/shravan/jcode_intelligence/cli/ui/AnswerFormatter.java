package com.shravan.jcode_intelligence.cli.ui;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Formats raw LLM responses into a clean, normalized CLI presentation.
 */
public class AnswerFormatter {

    public String format(String rawAnswer) {
        if (rawAnswer == null || rawAnswer.isBlank()) {
            return "";
        }

        // 1. Normalize line endings
        String text = rawAnswer.replace("\r\n", "\n").replace("\r", "\n");

        StringBuilder result = new StringBuilder();
        String[] parts = text.split("```", -1);

        for (int i = 0; i < parts.length; i++) {
            if (i % 2 == 1) {
                // 6. Preserve code blocks
                result.append("```");
                String code = parts[i];
                
                // Trim trailing newlines to avoid empty lines before the closing ```
                while (code.endsWith("\n")) {
                    code = code.substring(0, code.length() - 1);
                }
                
                String[] lines = code.split("\n", -1);
                for (int j = 0; j < lines.length; j++) {
                    result.append(lines[j].stripTrailing());
                    if (j < lines.length - 1) {
                        result.append("\n");
                    }
                }
                result.append("\n```");
            } else {
                // Text block
                String formattedText = formatTextBlock(parts[i]);
                result.append(formattedText);
            }
        }

        return cleanExcessiveBlankLines(result.toString()).trim();
    }

    private String formatTextBlock(String text) {
        String[] lines = text.split("\n", -1);
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];

            // 3. Normalize headings
            Matcher headingMatcher = Pattern.compile("^#{1,6}\\s+(.*)").matcher(line);
            if (headingMatcher.matches()) {
                String title = headingMatcher.group(1).trim();
                sb.append(title).append("\n");
                sb.append("────────────────────").append("\n");
                continue;
            }

            // 4. Improve bullet formatting
            Matcher bulletMatcher = Pattern.compile("^\\s*[*\\-•]\\s+(.*)").matcher(line);
            if (bulletMatcher.matches()) {
                sb.append("• ").append(bulletMatcher.group(1)).append("\n");
                continue;
            }

            // 5. Improve numbered lists
            Matcher numberMatcher = Pattern.compile("^\\s*(\\d+)\\.\\s*(.*)").matcher(line);
            if (numberMatcher.matches()) {
                sb.append(numberMatcher.group(1)).append(". ").append(numberMatcher.group(2)).append("\n");
                continue;
            }

            sb.append(line).append("\n");
        }

        String result = sb.toString();

        // 7. Preserve bold emphasis but don't print literal ** characters
        result = result.replaceAll("\\*\\*(.*?)\\*\\*", "$1");
        
        return result;
    }

    private String cleanExcessiveBlankLines(String text) {
        // We want to collapse excessive blank lines outside code blocks.
        // Doing this safely requires splitting by code blocks again.
        StringBuilder result = new StringBuilder();
        String[] parts = text.split("```", -1);

        for (int i = 0; i < parts.length; i++) {
            if (i % 2 == 1) {
                result.append("```").append(parts[i]).append("```");
            } else {
                // 2. Collapse excessive blank lines
                String textBlock = parts[i].replaceAll("\n{3,}", "\n\n");
                result.append(textBlock);
            }
        }
        return result.toString();
    }
}
