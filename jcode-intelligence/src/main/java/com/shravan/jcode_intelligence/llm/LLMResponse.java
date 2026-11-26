package com.shravan.jcode_intelligence.llm;

/**
 * Result of an LLM generation call, encapsulating generated text, provider token usage,
 * execution latency, and effective model name.
 *
 * @param text             generated text content (nullable if generation failed)
 * @param promptTokens     prompt token count reported by provider (nullable)
 * @param completionTokens completion token count reported by provider (nullable)
 * @param latencyMs        wall-clock call latency in milliseconds
 * @param model            model identifier reported by provider (nullable)
 */
public record LLMResponse(
        String text,
        Integer promptTokens,
        Integer completionTokens,
        Long latencyMs,
        String model
) {
    public boolean hasUsage() {
        return promptTokens != null || completionTokens != null;
    }

    public Integer totalTokens() {
        if (promptTokens == null && completionTokens == null) {
            return null;
        }
        return (promptTokens != null ? promptTokens : 0) + (completionTokens != null ? completionTokens : 0);
    }
}
