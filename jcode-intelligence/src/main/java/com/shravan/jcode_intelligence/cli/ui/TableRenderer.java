package com.shravan.jcode_intelligence.cli.ui;

import com.shravan.jcode_intelligence.cli.util.TerminalUtils;

import java.util.ArrayList;
import java.util.List;

public class TableRenderer {
    private final String[] headers;
    private final List<String[]> rows;
    
    public TableRenderer(String... headers) {
        this.headers = headers;
        this.rows = new ArrayList<>();
    }
    
    public void addRow(String... row) {
        rows.add(row);
    }
    
    public String render() {
        int[] colWidths = new int[headers.length];
        for (int i = 0; i < headers.length; i++) {
            colWidths[i] = headers[i] != null ? headers[i].length() : 0;
        }
        for (String[] row : rows) {
            for (int i = 0; i < row.length && i < colWidths.length; i++) {
                if (row[i] != null && row[i].length() > colWidths[i]) {
                    colWidths[i] = row[i].length();
                }
            }
        }
        
        StringBuilder sb = new StringBuilder();
        // Top border
        sb.append("┌");
        for (int i = 0; i < colWidths.length; i++) {
            sb.append(TerminalUtils.repeat('─', colWidths[i] + 2));
            if (i < colWidths.length - 1) sb.append("┬");
        }
        sb.append("┐\n");
        
        // Headers
        sb.append("│");
        for (int i = 0; i < headers.length; i++) {
            String h = headers[i] != null ? headers[i] : "";
            sb.append(" ").append(padRight(h, colWidths[i])).append(" │");
        }
        sb.append("\n");
        
        // Header separator
        sb.append("├");
        for (int i = 0; i < colWidths.length; i++) {
            sb.append(TerminalUtils.repeat('─', colWidths[i] + 2));
            if (i < colWidths.length - 1) sb.append("┼");
        }
        sb.append("┤\n");
        
        // Rows
        for (String[] row : rows) {
            sb.append("│");
            for (int i = 0; i < colWidths.length; i++) {
                String val = (i < row.length && row[i] != null) ? row[i] : "";
                sb.append(" ").append(padRight(val, colWidths[i])).append(" │");
            }
            sb.append("\n");
        }
        
        // Bottom border
        sb.append("└");
        for (int i = 0; i < colWidths.length; i++) {
            sb.append(TerminalUtils.repeat('─', colWidths[i] + 2));
            if (i < colWidths.length - 1) sb.append("┴");
        }
        sb.append("┘");
        
        return sb.toString();
    }
    
    private String padRight(String s, int n) {
        if (s.length() >= n) return s;
        return s + TerminalUtils.repeat(' ', n - s.length());
    }
}
