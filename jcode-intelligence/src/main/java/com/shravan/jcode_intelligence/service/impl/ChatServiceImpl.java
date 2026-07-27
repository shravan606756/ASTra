package com.shravan.jcode_intelligence.service.impl;

import com.shravan.jcode_intelligence.config.IntentRetrievalConfig;
import com.shravan.jcode_intelligence.dto.request.ChatRequest;
import com.shravan.jcode_intelligence.dto.response.ChatResponse;
import com.shravan.jcode_intelligence.dto.response.ChunkResponse;
import com.shravan.jcode_intelligence.llm.LLMClient;
import com.shravan.jcode_intelligence.llm.PromptBuilder;
import com.shravan.jcode_intelligence.llm.ResponseFormatter;
import com.shravan.jcode_intelligence.model.QueryIntent;
import com.shravan.jcode_intelligence.service.ChatService;
import com.shravan.jcode_intelligence.service.RetrievalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class ChatServiceImpl implements ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatServiceImpl.class);

    private final RetrievalServiceImpl retrievalService;
    private final PromptBuilder promptBuilder;
    private final LLMClient llmClient;
    private final ResponseFormatter responseFormatter;
    private final IntentRetrievalConfig config;

    public ChatServiceImpl(RetrievalServiceImpl retrievalService,
                           PromptBuilder promptBuilder,
                           LLMClient llmClient,
                           ResponseFormatter responseFormatter,
                           IntentRetrievalConfig config) {
        this.retrievalService = retrievalService;
        this.promptBuilder = promptBuilder;
        this.llmClient = llmClient;
        this.responseFormatter = responseFormatter;
        this.config = config;
    }

    @Override
    public ChatResponse answer(ChatRequest request) {
        long retrievalStart = System.currentTimeMillis();

        // Step 1: Classify intent
        QueryIntent intent = retrievalService.classifyIntent(request.getQuery(), request.getMode());

        // Step 2: Retrieve documents
        List<Document> documents = retrievalService.retrieve(
                request.getQuery(),
                request.getTopK(),
                request.getRepositoryId(),
                request.getMode()
        );

        long retrievalLatency = System.currentTimeMillis() - retrievalStart;

        // Step 3: Build intent-aware prompt
        String prompt = promptBuilder.buildPrompt(
                request.getQuery(), documents, request.getRepositoryId(), intent);

        // Step 4: Generate LLM answer
        String answer = llmClient.generateAnswer(prompt);

        // Step 5: Format sources
        List<ChunkResponse> sources = responseFormatter.formatChunks(documents);

        // Step 6: Assemble response with debug info
        ChatResponse response = new ChatResponse(request.getQuery(), answer, sources);

        if (config.isDebugIntentEnabled()) {
            response.setClassifiedIntent(intent.name());
            response.setRetrievalStrategy(intent.name() + "_STRATEGY");
            response.setRetrievedChunkTypes(retrievalService.computeChunkTypeCounts(documents));
            response.setRetrievalLatencyMs(retrievalLatency);
        }

        return response;
    }
}
