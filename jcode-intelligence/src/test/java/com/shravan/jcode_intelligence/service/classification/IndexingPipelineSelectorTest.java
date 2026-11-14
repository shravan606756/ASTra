package com.shravan.jcode_intelligence.service.classification;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class IndexingPipelineSelectorTest {

    private final IndexingPipelineSelector selector = new IndexingPipelineSelector();

    @Test
    public void testSelectNormalClassification() {
        RepositoryClassificationResult result = new RepositoryClassificationResult(
                RepositoryClass.NORMAL,
                1000L,
                2,
                List.of(Path.of("A.java"), Path.of("B.java")),
                1000000L,
                400000L,
                200000L,
                false
        );

        PipelineSelection selection = selector.select(result, "test-repo");

        assertEquals(IndexingPipeline.EXISTING, selection.requestedPipeline());
        assertEquals(IndexingPipeline.EXISTING, selection.selectedPipeline());
        assertFalse(selection.fallback());
        assertNull(selection.fallbackReason());
    }

    @Test
    public void testSelectLargeClassificationFallsBackToExisting() {
        RepositoryClassificationResult result = new RepositoryClassificationResult(
                RepositoryClass.LARGE,
                50000L,
                50,
                List.of(Path.of("Large.java")),
                1000000L,
                400000L,
                800000L,
                false
        );

        PipelineSelection selection = selector.select(result, "large-repo");

        assertEquals(IndexingPipeline.STREAMING, selection.requestedPipeline());
        assertEquals(IndexingPipeline.EXISTING, selection.selectedPipeline());
        assertTrue(selection.fallback());
        assertNotNull(selection.fallbackReason());
        assertTrue(selection.fallbackReason().contains("#31"));
    }

    @Test
    public void testSelectWithoutRepositoryId() {
        RepositoryClassificationResult normal = new RepositoryClassificationResult(
                RepositoryClass.NORMAL, 100L, 1, List.of(), 1000L, 400L, 200L, false);
        PipelineSelection normalSel = selector.select(normal);
        assertEquals(IndexingPipeline.EXISTING, normalSel.selectedPipeline());
        assertFalse(normalSel.fallback());

        RepositoryClassificationResult large = new RepositoryClassificationResult(
                RepositoryClass.LARGE, 10000L, 1, List.of(), 1000L, 400L, 2000L, false);
        PipelineSelection largeSel = selector.select(large);
        assertEquals(IndexingPipeline.STREAMING, largeSel.requestedPipeline());
        assertEquals(IndexingPipeline.EXISTING, largeSel.selectedPipeline());
        assertTrue(largeSel.fallback());
    }

    @Test
    public void testSelectFailure() {
        PipelineSelection defaultFailure = selector.selectFailure();
        assertEquals(IndexingPipeline.EXISTING, defaultFailure.requestedPipeline());
        assertEquals(IndexingPipeline.EXISTING, defaultFailure.selectedPipeline());
        assertTrue(defaultFailure.fallback());
        assertEquals("classification failed", defaultFailure.fallbackReason());

        PipelineSelection customFailure = selector.selectFailure("custom I/O failure");
        assertEquals(IndexingPipeline.EXISTING, customFailure.requestedPipeline());
        assertEquals(IndexingPipeline.EXISTING, customFailure.selectedPipeline());
        assertTrue(customFailure.fallback());
        assertEquals("custom I/O failure", customFailure.fallbackReason());

        PipelineSelection nullFailure = selector.selectFailure(null);
        assertEquals("classification failed", nullFailure.fallbackReason());
    }
}
