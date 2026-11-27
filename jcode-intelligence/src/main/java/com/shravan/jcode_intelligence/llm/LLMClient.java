package com.shravan.jcode_intelligence.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class LLMClient {

    private static final Logger log = LoggerFactory.getLogger(LLMClient.class);
    private final ChatModel chatModel;

    public LLMClient(@Qualifier("openAiChatModel") ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * Executes an LLM generation call with optional ChatOptions, capturing wall-clock latency,
     * provider token usage metadata, and effective model name.
     *
     * @param promptText user prompt text
     * @param options    optional ChatOptions (can be null)
     * @return structured LLMResponse containing text, tokens, latency, and model
     */
    public LLMResponse generateWithUsage(String promptText, ChatOptions options) {
        long startTime = System.currentTimeMillis();
        Prompt prompt = (options != null) ? new Prompt(promptText, options) : new Prompt(promptText);
        ChatResponse response = (chatModel != null) ? chatModel.call(prompt) : null;
        long duration = System.currentTimeMillis() - startTime;
        log.info("LLM response generated in {} ms", duration);

        String text = null;
        if (response != null && response.getResult() != null && response.getResult().getOutput() != null) {
            text = response.getResult().getOutput().getText();
        }

        Integer promptTokens = null;
        Integer completionTokens = null;
        String model = null;

        if (response != null && response.getMetadata() != null) {
            ChatResponseMetadata metadata = response.getMetadata();
            model = metadata.getModel();
            if (metadata.getUsage() != null) {
                Usage usage = metadata.getUsage();
                promptTokens = usage.getPromptTokens();
                completionTokens = usage.getCompletionTokens();
            }
        }

        return new LLMResponse(text, promptTokens, completionTokens, duration, model);
    }

    public LLMResponse generateWithUsage(String promptText) {
        return generateWithUsage(promptText, null);
    }

    public LLMResponse generate(String promptText, ChatOptions options) {
        return generateWithUsage(promptText, options);
    }

    public LLMResponse generate(String promptText) {
        return generateWithUsage(promptText, null);
    }

    /**
     * Delegates to {@link #generateWithUsage(String, ChatOptions)} without changing existing behavior.
     *
     * @param promptText user prompt text
     * @return generated answer string
     */
    public String generateAnswer(String promptText) {
        LLMResponse response = generateWithUsage(promptText, null);
        if (response != null && response.text() != null) {
            return response.text();
        }
        return "No response generated from LLM.";
    }
}
