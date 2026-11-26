package com.shravan.jcode_intelligence.service.evaluation.generation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.TransientAiException;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LlmRetryHelperTest {

    @Test
    @DisplayName("Successful operation on first attempt completes with no retries")
    void testSuccessfulFirstAttempt() throws Exception {
        LlmRetryHelper helper = new LlmRetryHelper(3, 10, 2.0, 100, 0);
        AtomicInteger attempts = new AtomicInteger(0);

        String result = helper.executeWithRetry(() -> {
            attempts.incrementAndGet();
            return "Success";
        });

        assertEquals("Success", result);
        assertEquals(1, attempts.get());
    }

    @Test
    @DisplayName("Retries on transient failure (HTTP 429 rate limit or TransientAiException) and succeeds")
    void testRetryOnTransientFailure() throws Exception {
        LlmRetryHelper helper = new LlmRetryHelper(3, 10, 1.5, 100, 0);
        AtomicInteger attempts = new AtomicInteger(0);

        String result = helper.executeWithRetry(() -> {
            int attempt = attempts.incrementAndGet();
            if (attempt == 1) {
                throw new RuntimeException("HTTP 429: Rate limit exceeded; transient");
            }
            return "Recovered";
        });

        assertEquals("Recovered", result);
        assertEquals(2, attempts.get());
    }

    @Test
    @DisplayName("Non-retryable exception fails immediately without retrying")
    void testNonRetryableFailsFast() {
        LlmRetryHelper helper = new LlmRetryHelper(3, 10, 2.0, 100, 0);
        AtomicInteger attempts = new AtomicInteger(0);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () ->
                helper.executeWithRetry(() -> {
                    attempts.incrementAndGet();
                    throw new IllegalArgumentException("Invalid argument format");
                })
        );

        assertEquals("Invalid argument format", thrown.getMessage());
        assertEquals(1, attempts.get(), "Non-retryable exception must not trigger retries");
    }

    @Test
    @DisplayName("Exhausted retries throw the last retryable exception")
    void testExhaustRetriesThrows() {
        LlmRetryHelper helper = new LlmRetryHelper(2, 10, 2.0, 100, 0);
        AtomicInteger attempts = new AtomicInteger(0);

        RuntimeException thrown = assertThrows(RuntimeException.class, () ->
                helper.executeWithRetry(() -> {
                    attempts.incrementAndGet();
                    throw new RuntimeException("HTTP 503: Service Unavailable timeout");
                })
        );

        assertTrue(thrown.getMessage().contains("503"));
        assertEquals(2, attempts.get(), "Must attempt exactly maxAttempts (2) times");
    }
}
