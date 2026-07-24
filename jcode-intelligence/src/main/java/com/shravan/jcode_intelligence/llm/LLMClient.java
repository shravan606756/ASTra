package com.shravan.jcode_intelligence.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
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

    public String generateAnswer(String promptText) {
        long startTime = System.currentTimeMillis();
        Prompt prompt = new Prompt(promptText);
        ChatResponse response = chatModel.call(prompt);
        long duration = System.currentTimeMillis() - startTime;
        log.info("LLM response generated in {} ms", duration);

        if (response != null && response.getResult() != null && response.getResult().getOutput() != null) {
            return response.getResult().getOutput().getText();
        }
        return "No response generated from LLM.";
    }
}


