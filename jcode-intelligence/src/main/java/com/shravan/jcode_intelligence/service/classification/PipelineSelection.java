package com.shravan.jcode_intelligence.service.classification;

/**
 * Result of pipeline selection from repository classification.
 *
 * @param requestedPipeline the pipeline requested based on classification
 * @param selectedPipeline  the actual pipeline selected to run
 * @param fallback          true if falling back from requested pipeline
 * @param fallbackReason    reason for fallback, or null if no fallback occurred
 */
public record PipelineSelection(
        IndexingPipeline requestedPipeline,
        IndexingPipeline selectedPipeline,
        boolean fallback,
        String fallbackReason
) {
}
