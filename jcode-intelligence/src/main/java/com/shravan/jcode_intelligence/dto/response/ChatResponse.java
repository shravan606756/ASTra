package com.shravan.jcode_intelligence.dto.response;

import java.util.List;

public class ChatResponse {

    private String query;
    private String answer;
    private List<ChunkResponse> sources;

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
}

