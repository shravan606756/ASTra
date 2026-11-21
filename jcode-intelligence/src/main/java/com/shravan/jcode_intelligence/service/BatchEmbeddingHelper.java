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
     * builds {@link EmbeddedRecord} instances with deterministic IDs, and stages them to the bounded embedding queue.
     *
     * @param repositoryId repository identifier
     * @param jobId        job identifier
     * @param batch        list of documents to embed and stage
     * @return the list of embedded records staged to the queue
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
            String recordId = EmbeddedRecord.generateDeterministicId(repositoryId, chunkId, doc.getText());
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
     * @param texts        batch of text strings to embed
     * @param repositoryId repository identifier for diagnostic logging
     * @param jobId        job identifier for diagnostic logging
     * @return generated embedding vector array list
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
     * Determines whether an error is a transient socket exhaustion, port binding, rate limit, or network blip.
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
