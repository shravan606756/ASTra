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
            log.info("Repository '{}' classified as NORMAL repo (LOC={}); selecting EXISTING one-pass pipeline", repoId, result.totalLoc());
            return new PipelineSelection(IndexingPipeline.EXISTING, IndexingPipeline.EXISTING, false, null);
        }

        // RepositoryClass.LARGE: selects STREAMING without fallback
        log.info("Repository '{}' classified as LARGE repo (LOC={}); selecting STREAMING pipeline", repoId, result.totalLoc());
        return new PipelineSelection(
                IndexingPipeline.STREAMING,
                IndexingPipeline.STREAMING,
                false,
                null
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
