package com.shravan.jcode_intelligence.service;

import org.springframework.ai.embedding.EmbeddingModel;

/**
 * Embedding worker helper alias for {@link BatchEmbeddingHelper}.
 */
public class BatchEmbeddingWorker extends BatchEmbeddingHelper {

    public BatchEmbeddingWorker(EmbeddingQueue embeddingQueue, EmbeddingModel embeddingModel) {
        super(embeddingQueue, embeddingModel);
    }
}
