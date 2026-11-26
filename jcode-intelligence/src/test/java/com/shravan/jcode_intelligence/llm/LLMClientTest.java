package com.shravan.jcode_intelligence.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LLMClientTest {

    @Test
    @DisplayName("generateWithUsage returns text, tokens, latency, and model metadata")
    void testGenerateWithUsage_capturesUsageAndMetadata() {
        ChatModel mockModel = mock(ChatModel.class);
        Generation generation = new Generation(new AssistantMessage("Hello world"));

        Usage mockUsage = mock(Usage.class);
        when(mockUsage.getPromptTokens()).thenReturn(15);
        when(mockUsage.getCompletionTokens()).thenReturn(25);

        ChatResponseMetadata mockMetadata = mock(ChatResponseMetadata.class);
        when(mockMetadata.getModel()).thenReturn("gpt-4o-mini");
        when(mockMetadata.getUsage()).thenReturn(mockUsage);

        ChatResponse mockResponse = new ChatResponse(List.of(generation), mockMetadata);
        when(mockModel.call(any(Prompt.class))).thenReturn(mockResponse);

        LLMClient client = new LLMClient(mockModel);
        LLMResponse response = client.generateWithUsage("Say hello");

        assertNotNull(response);
        assertEquals("Hello world", response.text());
        assertEquals(15, response.promptTokens());
        assertEquals(25, response.completionTokens());
        assertEquals(40, response.totalTokens());
        assertEquals("gpt-4o-mini", response.model());
        assertTrue(response.latencyMs() >= 0);
        assertTrue(response.hasUsage());
    }

    @Test
    @DisplayName("generateAnswer delegates to generateWithUsage and returns text")
    void testGenerateAnswer_delegatesCorrectly() {
        ChatModel mockModel = mock(ChatModel.class);
        Generation generation = new Generation(new AssistantMessage("Answer text"));
        ChatResponse mockResponse = new ChatResponse(List.of(generation));
        when(mockModel.call(any(Prompt.class))).thenReturn(mockResponse);

        LLMClient client = new LLMClient(mockModel);
        String answer = client.generateAnswer("Prompt");

        assertEquals("Answer text", answer);
    }

    @Test
    @DisplayName("generateAnswer returns default message when response is null")
    void testGenerateAnswer_handlesNullResponse() {
        ChatModel mockModel = mock(ChatModel.class);
        when(mockModel.call(any(Prompt.class))).thenReturn(null);

        LLMClient client = new LLMClient(mockModel);
        String answer = client.generateAnswer("Prompt");

        assertEquals("No response generated from LLM.", answer);
    }
}
