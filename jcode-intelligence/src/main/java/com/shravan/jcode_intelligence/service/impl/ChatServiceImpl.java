package com.shravan.jcode_intelligence.service.impl;

import com.shravan.jcode_intelligence.config.IntentRetrievalConfig;
import com.shravan.jcode_intelligence.dto.request.ChatRequest;
import com.shravan.jcode_intelligence.dto.response.ChatResponse;
import com.shravan.jcode_intelligence.dto.response.ChunkResponse;
import com.shravan.jcode_intelligence.dto.response.GroupedSourceResponse;
import com.shravan.jcode_intelligence.llm.LLMClient;
import com.shravan.jcode_intelligence.llm.PromptBuilder;
import com.shravan.jcode_intelligence.llm.PromptContextResult;
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

/**
 * Orchestrates the end-to-end question answering pipeline: intent classification,
 * hybrid retrieval, prompt budgeting, LLM generation, and source attribution.
 */
@Service
public class ChatServiceImpl implements ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatServiceImpl.class);

    private final RetrievalServiceImpl retrievalService;
    private final PromptBuilder promptBuilder;
    private final LLMClient llmClient;
    private final ResponseFormatter responseFormatter;
    private final IntentRetrievalConfig config;

    /**
     * Constructs a {@code ChatServiceImpl} with the required retrieval, prompt, LLM,
     * formatting, and configuration components.
     *
     * @param retrievalService  service for intent classification and document retrieval
     * @param promptBuilder     builder for intent-aware prompt context assembly
     * @param llmClient         client for LLM text generation
     * @param responseFormatter formatter for source attribution and coordinate deduplication
     * @param config            intent retrieval configuration for debug flags and thresholds
     */
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

    /**
     * Retrieves context for the request, submits a budgeted prompt to the LLM, and returns
     * its answer with deduplicated sources drawn only from the supplied context documents.
     * When intent debugging is enabled, includes retrieval latency in milliseconds and
     * retrieved, supplied, and omitted document counts before source deduplication.
     * Retrieval, prompt assembly, and model call failures propagate to the caller;
     * an LLM response without a result or output becomes the client's fallback answer.
     *
     * @param request query, retrieval limit, repository ID, and chat mode
     * @return answer and attributed sources, with optional intent diagnostics
     * @throws NullPointerException if request is null
     */
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

        // Step 3: Build intent-aware prompt with budgeted context tracking
        PromptContextResult promptResult = promptBuilder.buildPromptContext(
                request.getQuery(), documents, request.getRepositoryId(), intent);

        // Step 4: Generate LLM answer
        String answer = llmClient.generateAnswer(promptResult.promptText());

        // Step 5: Format sources using ONLY documents actually supplied within budget
        List<ChunkResponse> sources = responseFormatter.formatChunks(promptResult.suppliedDocuments());
        List<GroupedSourceResponse> groupedSources = responseFormatter.groupSourcesByFile(sources);

        // Step 6: Assemble response with debug info
        ChatResponse response = new ChatResponse(request.getQuery(), answer, sources);
        response.setGroupedSources(groupedSources);

        if (config.isDebugIntentEnabled()) {
            response.setClassifiedIntent(intent.name());
            response.setRetrievalStrategy(intent.name() + "_STRATEGY");
            response.setRetrievedChunkTypes(retrievalService.computeChunkTypeCounts(documents));
            response.setRetrievalLatencyMs(retrievalLatency);
            response.setRetrievedChunkCount(documents != null ? documents.size() : 0);
            response.setSuppliedChunkCount(promptResult.suppliedDocuments().size());
            response.setOmittedChunkCount(promptResult.omittedDocuments().size());
        }

        return response;
    }
}
