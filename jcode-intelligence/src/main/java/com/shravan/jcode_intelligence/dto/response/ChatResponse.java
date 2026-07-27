package com.shravan.jcode_intelligence.dto.response;

import java.util.List;
import java.util.Map;

public class ChatResponse {

    private String query;
    private String answer;
    private List<ChunkResponse> sources;

    // ── Intent-Aware Debug Fields ─────────────────────────────

    /** The classified query intent (e.g., METHOD, CLASS, ARCHITECTURE). Populated when astra.debug.intent=true. */
    private String classifiedIntent;

    /** The retrieval strategy used (e.g., METHOD_PRIORITY, PACKAGE_PLUS_CLASS). Populated when astra.debug.intent=true. */
    private String retrievalStrategy;

    /** Breakdown of retrieved chunk types and their counts. Populated when astra.debug.intent=true. */
    private Map<String, Integer> retrievedChunkTypes;

    /** Total retrieval latency in milliseconds. Populated when astra.debug.intent=true. */
    private Long retrievalLatencyMs;

    public ChatResponse() {
    }

    public ChatResponse(String query, String answer, List<ChunkResponse> sources) {
        this.query = query;
        this.answer = answer;
        this.sources = sources;
    }

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public List<ChunkResponse> getSources() {
        return sources;
    }

    public void setSources(List<ChunkResponse> sources) {
        this.sources = sources;
    }

    public String getClassifiedIntent() {
        return classifiedIntent;
    }

    public void setClassifiedIntent(String classifiedIntent) {
        this.classifiedIntent = classifiedIntent;
    }

    public String getRetrievalStrategy() {
        return retrievalStrategy;
    }

    public void setRetrievalStrategy(String retrievalStrategy) {
        this.retrievalStrategy = retrievalStrategy;
    }

    public Map<String, Integer> getRetrievedChunkTypes() {
        return retrievedChunkTypes;
    }

    public void setRetrievedChunkTypes(Map<String, Integer> retrievedChunkTypes) {
        this.retrievedChunkTypes = retrievedChunkTypes;
    }

    public Long getRetrievalLatencyMs() {
        return retrievalLatencyMs;
    }

    public void setRetrievalLatencyMs(Long retrievalLatencyMs) {
        this.retrievalLatencyMs = retrievalLatencyMs;
    }
}
