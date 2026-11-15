package com.shravan.jcode_intelligence.service.classification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Selects an indexing pipeline strategy based on repository classification.
 *
 * <p>Encapsulates availability decisions for future pipelines (e.g. #31).
 */
@Component
public class IndexingPipelineSelector {

    private static final Logger log = LoggerFactory.getLogger(IndexingPipelineSelector.class);

    /**
     * Selects the appropriate pipeline for a classification result.
     *
     * @param result classification result
     * @return selection decision
     */
    public PipelineSelection select(RepositoryClassificationResult result) {
        return select(result, "unknown");
    }

    /**
     * Selects the appropriate pipeline for a classification result with repository context.
     *
     * @param result       classification result
     * @param repositoryId repository identifier for logging
     * @return selection decision
     */
    public PipelineSelection select(RepositoryClassificationResult result, String repositoryId) {
        Objects.requireNonNull(result, "result must not be null");
        String repoId = (repositoryId != null && !repositoryId.isBlank()) ? repositoryId : "unknown";

        if (result.classification() == RepositoryClass.NORMAL) {
            return new PipelineSelection(IndexingPipeline.EXISTING, IndexingPipeline.EXISTING, false, null);
        }

        // RepositoryClass.LARGE: requests STREAMING, but falls back to EXISTING until #31 is merged
        log.warn("Repository '{}' is classified as LARGE (requested STREAMING pipeline), but streaming pipeline (#31) is not yet available; falling back to existing pipeline", repoId);
        return new PipelineSelection(
                IndexingPipeline.STREAMING,
                IndexingPipeline.EXISTING,
                true,
                "streaming pipeline (#31) is not yet available; falling back to existing pipeline"
        );
    }

    /**
     * Returns a fallback selection when classification fails.
     *
     * @return selection decision falling back to existing pipeline
     */
    public PipelineSelection selectFailure() {
        return selectFailure("classification failed");
    }

    /**
     * Returns a fallback selection when classification fails with a custom reason.
     *
     * @param reason fallback reason
     * @return selection decision falling back to existing pipeline
     */
    public PipelineSelection selectFailure(String reason) {
        return new PipelineSelection(
                IndexingPipeline.EXISTING,
                IndexingPipeline.EXISTING,
                true,
                (reason != null && !reason.isBlank()) ? reason : "classification failed"
        );
    }
}
