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

    /** Total number of candidate chunks retrieved before prompt budgeting. Populated when astra.debug.intent=true. */
    private Integer retrievedChunkCount;

    /** Total number of chunks included within the LLM prompt context budget. Populated when astra.debug.intent=true. */
    private Integer suppliedChunkCount;

    /** Total number of candidate chunks omitted due to context budget limits or intent filtering. Populated when astra.debug.intent=true. */
    private Integer omittedChunkCount;

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

    /**
     * Returns the total count of candidate chunks retrieved before prompt budgeting.
     *
     * @return retrieved chunk count, or null when debug mode is disabled
     */
    public Integer getRetrievedChunkCount() {
        return retrievedChunkCount;
    }

    /**
     * Sets the total count of candidate chunks retrieved before prompt budgeting.
     *
     * @param retrievedChunkCount total retrieved candidate count
     */
    public void setRetrievedChunkCount(Integer retrievedChunkCount) {
        this.retrievedChunkCount = retrievedChunkCount;
    }

    /**
     * Returns the count of chunks actually supplied within the LLM prompt context budget.
     *
     * @return supplied chunk count, or null when debug mode is disabled
     */
    public Integer getSuppliedChunkCount() {
        return suppliedChunkCount;
    }

    /**
     * Sets the count of chunks actually supplied within the LLM prompt context budget.
     *
     * @param suppliedChunkCount total supplied chunk count
     */
    public void setSuppliedChunkCount(Integer suppliedChunkCount) {
        this.suppliedChunkCount = suppliedChunkCount;
    }

    /**
     * Returns the count of candidate chunks omitted due to budget limits or intent filtering.
     *
     * @return omitted chunk count, or null when debug mode is disabled
     */
    public Integer getOmittedChunkCount() {
        return omittedChunkCount;
    }

    /**
     * Sets the count of candidate chunks omitted due to budget limits or intent filtering.
     *
     * @param omittedChunkCount total omitted chunk count
     */
    public void setOmittedChunkCount(Integer omittedChunkCount) {
        this.omittedChunkCount = omittedChunkCount;
    }
}
