package com.shravan.jcode_intelligence.service;

import com.shravan.jcode_intelligence.model.EmbeddedRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Helper that encapsulates per-batch embedding generation and staging to {@link EmbeddingQueue}.
 *
 * <p>Shared between whole-repository single-pass (NORMAL) and streaming (LARGE) indexing pipelines.
 * Includes progressive retries with exponential backoff to handle transient OS socket exhaustion
 * (WSAENOBUFS / port binding failures) and temporary Ollama cold-start / connection timeouts.
 */
public class BatchEmbeddingHelper {

    private static final Logger log = LoggerFactory.getLogger(BatchEmbeddingHelper.class);

    private final EmbeddingQueue embeddingQueue;
    private final EmbeddingModel embeddingModel;
    private final int maxRetries;
    private final long initialBackoffMs;

    public BatchEmbeddingHelper(EmbeddingQueue embeddingQueue, EmbeddingModel embeddingModel) {
        this(embeddingQueue, embeddingModel, 5, 2000L);
    }

    public BatchEmbeddingHelper(EmbeddingQueue embeddingQueue, EmbeddingModel embeddingModel, int maxRetries, long initialBackoffMs) {
        this.embeddingQueue = Objects.requireNonNull(embeddingQueue, "embeddingQueue must not be null");
        this.embeddingModel = Objects.requireNonNull(embeddingModel, "embeddingModel must not be null");
        this.maxRetries = Math.max(1, maxRetries);
        this.initialBackoffMs = Math.max(50L, initialBackoffMs);
    }

    /**
     * Generates embeddings for a batch of documents with progressive retry on transient socket/connection errors,
     * validates vector completeness, builds {@link EmbeddedRecord} instances with deterministic IDs, and stages
     * them to the bounded {@link EmbeddingQueue}.
     *
     * <p><strong>Validation &amp; Invariants:</strong>
     * <ul>
     *   <li>Ensures provider returns an embedding list matching the input text count 1:1. Any mismatch or null
     *       response fails fast with an {@link IllegalStateException} before staging.</li>
     *   <li>Rejects empty (0-length) or null embedding vectors to protect downstream pgvector persistence.</li>
     *   <li>Computes deterministic record IDs formatted as {@code repo:chunkId:sha256(content)}, guaranteeing
     *       idempotent persistence across re-indexing runs.</li>
     * </ul>
     *
     * @param repositoryId repository identifier
     * @param jobId        job identifier
     * @param batch        list of documents to embed and stage
     * @return the list of embedded records staged to the queue
     * @throws IllegalStateException if provider returns mismatched or empty embeddings
     * @throws RuntimeException      if thread is interrupted while awaiting queue staging capacity
     */
    public List<EmbeddedRecord> embedAndStage(String repositoryId, String jobId, List<Document> batch) {
        if (batch == null || batch.isEmpty()) {
            return List.of();
        }

        // Group chunks in this batch to request embeddings in a single model call with retry
        List<String> texts = batch.stream()
                .map(Document::getText)
                .toList();
        List<float[]> embeddings = embedWithRetry(texts, repositoryId, jobId);
        if (embeddings == null || embeddings.size() != texts.size()) {
            throw new IllegalStateException(String.format(
                    "Embedding provider returned %d embedding(s) for %d input text(s) in repo '%s' job '%s'",
                    embeddings != null ? embeddings.size() : 0, texts.size(), repositoryId, jobId));
        }

        List<EmbeddedRecord> records = new ArrayList<>(batch.size());
        for (int j = 0; j < batch.size(); j++) {
            Document doc = batch.get(j);
            String chunkId = (String) doc.getMetadata().get("chunkId");
            String recordId = (doc.getId() != null && !doc.getId().isBlank())
                    ? doc.getId()
                    : EmbeddedRecord.generateDeterministicId(repositoryId, chunkId, doc.getText());
            float[] embedding = embeddings.get(j);
            if (embedding == null || embedding.length == 0) {
                throw new IllegalStateException(String.format(
                        "Embedding provider returned empty or null vector for document %d (chunkId: %s) in repo '%s'",
                        j, chunkId, repositoryId));
            }
            records.add(new EmbeddedRecord(
                    recordId,
                    jobId,
                    repositoryId,
                    doc.getText(),
                    doc.getMetadata(),
                    embedding,
                    new CompletableFuture<>()
            ));
        }

        try {
            embeddingQueue.submitAll(records);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while staging records to embedding queue", e);
        }

        return records;
    }

    /**
     * Executes embedding generation with progressive retry and exponential backoff on transient socket/model errors.
     *
     * <p>Handles transient OS socket pool exhaustion (such as Windows Socket error 10055 {@code WSAENOBUFS / bind failed})
     * by backing off progressively (starting at {@code initialBackoffMs} and doubling up to 30 seconds), allowing the
     * operating system kernel time to recycle sockets sitting in {@code TIME_WAIT}. Non-retryable errors (e.g. invalid
     * token lengths, authentication rejections) fail immediately on attempt 1.
     *
     * @param texts        batch of text strings to embed
     * @param repositoryId repository identifier for diagnostic logging
     * @param jobId        job identifier for diagnostic logging
     * @return generated embedding vector array list
     * @throws RuntimeException if retries are exhausted or a non-retryable error occurs
     */
    public List<float[]> embedWithRetry(List<String> texts, String repositoryId, String jobId) {
        long backoff = initialBackoffMs;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                return embeddingModel.embed(texts);
            } catch (Throwable t) {
                if (attempt >= maxRetries || !isTransientSocketOrModelError(t)) {
                    log.error("[EMBEDDING FAILURE] Exhausted {} attempt(s) or encountered non-retryable error for repo '{}' job '{}': {}",
                            attempt, repositoryId, jobId, t.getMessage());
                    if (t instanceof RuntimeException re) {
                        throw re;
                    }
                    if (t instanceof Error err) {
                        throw err;
                    }
                    throw new RuntimeException("Failed to generate embeddings after " + attempt + " attempt(s)", t);
                }
                log.warn("[OLLAMA TRANSIENT SOCKET / BUFFER BLIP] Attempt {}/{} failed for repo '{}' ({}: {}). Backing off for {} ms to allow OS TCP TIME_WAIT sockets to recycle...",
                        attempt, maxRetries, repositoryId, t.getClass().getSimpleName(), t.getMessage(), backoff);
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted while backing off for embedding retry", ie);
                }
                backoff = Math.min(backoff * 2, 30_000L);
            }
        }
        return List.of();
    }

    /**
     * Determines whether an error represents transient socket exhaustion, port binding failure, rate limit, or network blip.
     *
     * <p>Recognizes:
     * <ul>
     *   <li>Windows/Unix socket bind failures and buffer exhaustion ({@code bind: An operation on a socket...},
     *       {@code buffer space}, {@code queue was full}, {@code WSAENOBUFS}).</li>
     *   <li>Connection timeouts, connection resets, and temporary refusal ({@code Connection refused},
     *       {@code dial tcp}, {@code timeout}).</li>
     *   <li>Provider HTTP rate limits and gateway blips ({@code 429 Too Many Requests}, {@code 503 Service Unavailable}).</li>
     * </ul>
     *
     * @param t throwable to inspect
     * @return true if error is considered transient and safe to retry with backoff
     */
    public static boolean isTransientSocketOrModelError(Throwable t) {
        if (t == null) {
            return false;
        }
        String msg = t.getMessage();
        if (msg != null && !msg.isBlank()) {
            String lower = msg.toLowerCase();
            if (lower.contains("bind")
                    || lower.contains("buffer space")
                    || lower.contains("queue was full")
                    || lower.contains("dial tcp")
                    || lower.contains("connection refused")
                    || lower.contains("connectex")
                    || lower.contains("reset by peer")
                    || lower.contains("timed out")
                    || lower.contains("timeout")
                    || lower.contains("too many open files")
                    || lower.contains("503")
                    || lower.contains("502")
                    || lower.contains("504")
                    || lower.contains("429")) {
                return true;
            }
        }
        // Check cause recursively
        return isTransientSocketOrModelError(t.getCause());
    }

    /**
     * Alias for {@link #embedAndStage(String, String, List)}.
     */
    public List<EmbeddedRecord> processBatch(String repositoryId, String jobId, List<Document> batch) {
        return embedAndStage(repositoryId, jobId, batch);
    }

    public EmbeddingQueue getEmbeddingQueue() {
        return embeddingQueue;
    }

    public EmbeddingModel getEmbeddingModel() {
        return embeddingModel;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public long getInitialBackoffMs() {
        return initialBackoffMs;
    }
}
