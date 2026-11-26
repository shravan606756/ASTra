package com.shravan.jcode_intelligence.service.evaluation.generation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.Locale;
import java.util.concurrent.Callable;

/**
 * Executes LLM operations with bounded exponential backoff retry for transient failures.
 *
 * <p>Retries on {@link org.springframework.ai.retry.TransientAiException}, HTTP 429 (rate limits),
 * and HTTP 5xx server errors. Fails fast on non-retryable exceptions.
 * Applies a configurable inter-call delay between distinct LLM invocations.
 */
@Component
public class LlmRetryHelper {

    private static final Logger log = LoggerFactory.getLogger(LlmRetryHelper.class);

    private final int maxAttempts;
    private final long initialDelayMs;
    private final double backoffMultiplier;
    private final long maxDelayMs;
    private final long delayBetweenCallsMs;

    public LlmRetryHelper(
            @Value("${astra.evaluation.retry.max-attempts:3}") int maxAttempts,
            @Value("${astra.evaluation.retry.initial-delay-ms:1000}") long initialDelayMs,
            @Value("${astra.evaluation.retry.multiplier:2.0}") double backoffMultiplier,
            @Value("${astra.evaluation.retry.max-delay-ms:10000}") long maxDelayMs,
            @Value("${astra.evaluation.delay-between-calls-ms:0}") long delayBetweenCallsMs
    ) {
        this.maxAttempts = Math.max(1, maxAttempts);
        this.initialDelayMs = Math.max(0, initialDelayMs);
        this.backoffMultiplier = Math.max(1.0, backoffMultiplier);
        this.maxDelayMs = Math.max(initialDelayMs, maxDelayMs);
        this.delayBetweenCallsMs = Math.max(0, delayBetweenCallsMs);
    }

    public LlmRetryHelper() {
        this(3, 1000, 2.0, 10000, 0);
    }

    /**
     * Executes the given callable with exponential backoff retry.
     *
     * @param operation LLM callable operation
     * @param <T>       result type
     * @return operation result
     * @throws Exception if all retries are exhausted or a non-retryable error occurs
     */
    public <T> T executeWithRetry(Callable<T> operation) throws Exception {
        if (delayBetweenCallsMs > 0) {
            try {
                Thread.sleep(delayBetweenCallsMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Interrupted during inter-call delay: " + e.getMessage());
            }
        }

        long currentDelay = initialDelayMs;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return operation.call();
            } catch (Exception e) {
                if (!isRetryable(e)) {
                    throw e;
                }

                if (attempt >= maxAttempts) {
                    log.warn("LLM operation failed after {} attempts: {}", maxAttempts, e.getMessage());
                    throw e;
                }

                long delayToApply = Math.min(currentDelay, maxDelayMs);
                log.warn("LLM operation failed on attempt {}/{}: {}. Retrying in {} ms...",
                        attempt, maxAttempts, e.getMessage(), delayToApply);

                try {
                    Thread.sleep(delayToApply);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("Interrupted during retry backoff: " + ie.getMessage());
                }

                currentDelay = (long) (currentDelay * backoffMultiplier);
            }
        }
        throw new IllegalStateException("Retry loop exited unexpectedly");
    }

    /**
     * Inspects throwable and cause chain for TransientAiException, HTTP 429, or HTTP 5xx.
     */
    public boolean isRetryable(Throwable throwable) {
        if (throwable == null) {
            return false;
        }

        Throwable current = throwable;
        while (current != null) {
            if (current instanceof org.springframework.ai.retry.TransientAiException) {
                return true;
            }
            if (current instanceof SocketTimeoutException
                    || current instanceof ConnectException
                    || current instanceof InterruptedIOException) {
                return true;
            }

            String msg = (current.getMessage() != null) ? current.getMessage().toLowerCase(Locale.ROOT) : "";
            if (msg.contains("429") || msg.contains("rate limit") || msg.contains("too many requests") || msg.contains("quota exceeded")) {
                return true;
            }
            if (msg.contains("500") || msg.contains("502") || msg.contains("503") || msg.contains("504")
                    || msg.contains("service unavailable") || msg.contains("bad gateway") || msg.contains("gateway timeout")) {
                return true;
            }

            current = current.getCause();
        }
        return false;
    }

    public int getMaxAttempts() { return maxAttempts; }
    public long getInitialDelayMs() { return initialDelayMs; }
    public double getBackoffMultiplier() { return backoffMultiplier; }
    public long getMaxDelayMs() { return maxDelayMs; }
    public long getDelayBetweenCallsMs() { return delayBetweenCallsMs; }
}
