package com.shravan.jcode_intelligence.cli.ui;

import com.shravan.jcode_intelligence.cli.util.TerminalUtils;
import com.shravan.jcode_intelligence.dto.response.QueryBenchmarkResultDTO.RetrievedChunkDTO;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility for formatting and aligning benchmark result tables in the CLI.
 * Strips machine-specific temporary paths, formats repository-relative paths,
 * and intelligently truncates long paths to prevent terminal line-wrapping.
 */
public class BenchmarkTableFormatter {

    private static final Pattern TEMP_REPO_PATTERN = Pattern.compile("(?i)(?:astra-repo-[^/\\\\]+|Temp[/\\\\]astra-[^/\\\\]+)[/\\\\](.*)");
    private static final Pattern GENERIC_TEMP_PATTERN = Pattern.compile("(?i)(?:AppData[/\\\\]Local[/\\\\]Temp|/tmp)[/\\\\][^/\\\\]+[/\\\\](.*)");

    public static final int DEFAULT_MAX_FILE_WIDTH = 46;
    public static final int DEFAULT_MAX_SYMBOL_WIDTH = 22;
    public static final int DEFAULT_MAX_TYPE_WIDTH = 12;

    /**
     * Normalizes slashes and removes recognized temporary extraction prefixes and leading
     * slashes. Returns {@code "-"} for a null or blank input.
     */
    public static String cleanRepoPath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return "-";
        }

        String path = rawPath.replace('\\', '/').trim();

        // 1. Strip temporary repository extraction directories like .../astra-repo-12345/...
        Matcher matcher = TEMP_REPO_PATTERN.matcher(path);
        if (matcher.find()) {
            path = matcher.group(1);
        } else {
            Matcher genMatcher = GENERIC_TEMP_PATTERN.matcher(path);
            if (genMatcher.find()) {
                path = genMatcher.group(1);
            }
        }

        // 2. Remove leading slashes if present
        while (path.startsWith("/")) {
            path = path.substring(1);
        }

        return path;
    }

    /**
     * Cleans and truncates a path to fit within {@code maxLen}, retaining the end of
     * the filename and as many innermost parent directories as fit.
     *
     * @throws StringIndexOutOfBoundsException if truncation is needed and maxLen is less than 3
     */
    public static String truncatePath(String rawPath, int maxLen) {
        String clean = cleanRepoPath(rawPath);
        if (clean.length() <= maxLen) {
            return clean;
        }

        String[] parts = clean.split("/");
        if (parts.length <= 1) {
            return (clean.length() > maxLen) ? "..." + clean.substring(clean.length() - maxLen + 3) : clean;
        }

        String fileName = parts[parts.length - 1];
        if (fileName.length() >= maxLen - 4) {
            return "..." + fileName.substring(fileName.length() - (maxLen - 3));
        }

        StringBuilder right = new StringBuilder("/" + fileName);
        for (int i = parts.length - 2; i >= 0; i--) {
            String candidate = "/" + parts[i] + right;
            if (candidate.length() + 3 <= maxLen) {
                right.insert(0, "/" + parts[i]);
            } else {
                break;
            }
        }

        return "..." + right;
    }

    /**
     * Trims and truncates a symbol with an ellipsis if it exceeds {@code maxLen}.
     * Returns {@code "-"} for a null or blank symbol.
     *
     * @throws StringIndexOutOfBoundsException if truncation is needed and maxLen is less than 3
     */
    public static String truncateSymbol(String symbol, int maxLen) {
        if (symbol == null || symbol.isBlank()) {
            return "-";
        }
        symbol = symbol.trim();
        if (symbol.length() <= maxLen) {
            return symbol;
        }
        return symbol.substring(0, maxLen - 3) + "...";
    }

    /**
     * Builds an aligned TableRenderer for retrieved chunks that dynamically fits
     * within the active terminal width to prevent horizontal line wrapping.
     */
    public static TableRenderer formatChunksTable(List<RetrievedChunkDTO> chunks, boolean hasGroundTruth) {
        int termWidth = TerminalUtils.getTerminalWidth();
        if (termWidth <= 0) {
            termWidth = 100;
        }
        // Subtract 2 characters margin for terminal window borders/safety
        int effectiveWidth = Math.max(76, Math.min(termWidth - 2, 120));

        boolean showRelevance = hasGroundTruth && chunks != null && chunks.stream().anyMatch(c -> c.getRelevant() != null);
        int maxFileWidth;
        int maxSymbolWidth;
        int maxTypeWidth = 7;

        if (showRelevance) {
            // Fixed chars: 19 (borders/spaces) + 4 (Rank) + 7 (Type) + 4 (Rel?) + 5 (Grade) = 39
            int available = Math.max(30, effectiveWidth - 39);
            maxFileWidth = (int) (available * 0.62);
            maxSymbolWidth = available - maxFileWidth;
        } else {
            // Fixed chars: 13 (borders/spaces) + 4 (Rank) + 7 (Type) = 24
            int available = Math.max(30, effectiveWidth - 24);
            maxFileWidth = (int) (available * 0.65);
            maxSymbolWidth = available - maxFileWidth;
        }

        return formatChunksTable(chunks, hasGroundTruth, maxFileWidth, maxSymbolWidth, maxTypeWidth);
    }

    /**
     * Builds an aligned TableRenderer for retrieved chunks with configurable column widths.
     */
    public static TableRenderer formatChunksTable(List<RetrievedChunkDTO> chunks, boolean hasGroundTruth,
                                                  int maxFileWidth, int maxSymbolWidth, int maxTypeWidth) {
        if (chunks == null || chunks.isEmpty()) {
            return new TableRenderer("No chunks retrieved");
        }

        boolean showRelevance = hasGroundTruth && chunks.stream().anyMatch(c -> c.getRelevant() != null);

        TableRenderer table;
        if (showRelevance) {
            table = new TableRenderer("Rank", "File", "Symbol", "Type", "Rel?", "Grade");
        } else {
            table = new TableRenderer("Rank", "File", "Symbol", "Type");
        }

        for (RetrievedChunkDTO chunk : chunks) {
            String rank = String.valueOf(chunk.getRank());
            String file = truncatePath(chunk.getFilePath(), maxFileWidth);
            String symbol = truncateSymbol(chunk.getElementName() != null ? chunk.getElementName() : chunk.getClassName(), maxSymbolWidth);
            String type = truncateSymbol(chunk.getType(), maxTypeWidth);

            if (showRelevance) {
                String relevant = Boolean.TRUE.equals(chunk.getRelevant()) ? "YES" : "NO";
                String grade = chunk.getRelevanceGrade() != null ? String.valueOf(chunk.getRelevanceGrade()) : "-";
                table.addRow(rank, file, symbol, type, relevant, grade);
            } else {
                table.addRow(rank, file, symbol, type);
            }
        }

        return table;
    }
}
