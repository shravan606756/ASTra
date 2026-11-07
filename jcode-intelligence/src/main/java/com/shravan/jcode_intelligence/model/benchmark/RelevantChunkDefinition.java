package com.shravan.jcode_intelligence.model.benchmark;

/**
 * Defines a ground-truth relevant code chunk for benchmark evaluation.
 */
public class RelevantChunkDefinition {

    private String filePath;
    private String symbol;
    private Integer startLine;
    private Integer endLine;
    private int relevance = 2; // 0: irrelevant, 1: partially related, 2: relevant, 3: highly relevant

    public RelevantChunkDefinition() {
    }

    public RelevantChunkDefinition(String filePath, String symbol, Integer startLine, Integer endLine, int relevance) {
        this.filePath = filePath;
        this.symbol = symbol;
        this.startLine = startLine;
        this.endLine = endLine;
        this.relevance = relevance;
    }

    public RelevantChunkDefinition(String filePath, String symbol, int relevance) {
        this(filePath, symbol, null, null, relevance);
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public Integer getStartLine() {
        return startLine;
    }

    public void setStartLine(Integer startLine) {
        this.startLine = startLine;
    }

    public Integer getEndLine() {
        return endLine;
    }

    public void setEndLine(Integer endLine) {
        this.endLine = endLine;
    }

    public int getRelevance() {
        return relevance;
    }

    public void setRelevance(int relevance) {
        this.relevance = relevance;
    }
}

