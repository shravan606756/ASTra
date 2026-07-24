package com.shravan.jcode_intelligence.service.impl;

import com.shravan.jcode_intelligence.dto.request.ChatRequest;
import com.shravan.jcode_intelligence.dto.response.ChatResponse;
import com.shravan.jcode_intelligence.dto.response.ChunkResponse;
import com.shravan.jcode_intelligence.llm.LLMClient;
import com.shravan.jcode_intelligence.llm.PromptBuilder;
import com.shravan.jcode_intelligence.llm.ResponseFormatter;
import com.shravan.jcode_intelligence.service.ChatService;
import com.shravan.jcode_intelligence.service.RetrievalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ChatServiceImpl implements ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatServiceImpl.class);

    private final RetrievalService retrievalService;
    private final PromptBuilder promptBuilder;
    private final LLMClient llmClient;
    private final ResponseFormatter responseFormatter;

    public ChatServiceImpl(RetrievalService retrievalService,
                           PromptBuilder promptBuilder,
                           LLMClient llmClient,
                           ResponseFormatter responseFormatter) {
        this.retrievalService = retrievalService;
        this.promptBuilder = promptBuilder;
        this.llmClient = llmClient;
        this.responseFormatter = responseFormatter;
    }

    @Override
    public ChatResponse answer(ChatRequest request) {
        List<Document> documents = retrievalService.retrieve(
                request.getQuery(),
                request.getTopK(),
                request.getRepositoryId(),
                request.getMode()
        );

        String prompt = promptBuilder.buildPrompt(request.getQuery(), documents, request.getRepositoryId(), request.getMode());
        String answer = llmClient.generateAnswer(prompt);
        List<ChunkResponse> sources = responseFormatter.formatChunks(documents);

        return new ChatResponse(request.getQuery(), answer, sources);
    }
}
