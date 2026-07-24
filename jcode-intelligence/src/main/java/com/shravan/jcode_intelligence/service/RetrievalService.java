package com.shravan.jcode_intelligence.service;

import com.shravan.jcode_intelligence.dto.request.ChatMode;
import org.springframework.ai.document.Document;

import java.util.List;

public interface RetrievalService {

    List<Document> retrieve(String query, int topK, String repositoryId, ChatMode mode);

    List<Document> retrieve(String query, int topK, String repositoryId);

    List<Document> retrieve(String query, int topK);

    List<Document> retrieve(String query);
}

