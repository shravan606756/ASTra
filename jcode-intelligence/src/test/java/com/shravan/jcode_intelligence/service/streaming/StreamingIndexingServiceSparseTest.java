package com.shravan.jcode_intelligence.service.streaming;

import com.shravan.jcode_intelligence.config.ChunkingConfig;
import com.shravan.jcode_intelligence.config.LargeRepoIndexingConfig;
import com.shravan.jcode_intelligence.config.RetrievalConfig;
import com.shravan.jcode_intelligence.converter.DocumentConverter;
import com.shravan.jcode_intelligence.model.CodeChunk;
import com.shravan.jcode_intelligence.parser.EmbeddingBudgetValidator;
import com.shravan.jcode_intelligence.parser.JavaProjectParser;
import com.shravan.jcode_intelligence.parser.PackageSummaryGenerator;
import com.shravan.jcode_intelligence.service.BatchEmbeddingHelper;
import com.shravan.jcode_intelligence.service.EmbeddingQueue;
import com.shravan.jcode_intelligence.service.RepositoryManagementService;
import com.shravan.jcode_intelligence.sparse.mapper.LuceneDocumentMapper;
import com.shravan.jcode_intelligence.sparse.query.LuceneQueryBuilder;
import com.shravan.jcode_intelligence.sparse.store.LuceneGenerationStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StreamingIndexingServiceSparseTest {

    @TempDir
    Path tempDir;

    private JavaProjectParser parser;
    private DocumentConverter converter;
    private EmbeddingBudgetValidator budgetValidator;
    private PackageSummaryGenerator summaryGenerator;
    private LargeRepoIndexingConfig largeRepoConfig;
    private ChunkingConfig chunkingConfig;
    private EmbeddingQueue embeddingQueue;
    private EmbeddingModel embeddingModel;
    private ObjectProvider<EmbeddingModel> modelProvider;
    private RepositoryManagementService repoMgmtService;
    private BatchEmbeddingHelper helper;

    private RetrievalConfig retrievalConfig;
    private LuceneGenerationStore store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        parser = mock(JavaProjectParser.class);
        converter = new DocumentConverter();
        budgetValidator = mock(EmbeddingBudgetValidator.class);
        summaryGenerator = mock(PackageSummaryGenerator.class);
        largeRepoConfig = new LargeRepoIndexingConfig(10, 0.25);
        chunkingConfig = mock(ChunkingConfig.class);
        when(chunkingConfig.getBatchSize()).thenReturn(5);
        when(chunkingConfig.getMaxParallelWorkers()).thenReturn(2);

        embeddingQueue = mock(EmbeddingQueue.class);
        when(embeddingQueue.getCapacity()).thenReturn(100);

        embeddingModel = mock(EmbeddingModel.class);
        modelProvider = mock(ObjectProvider.class);
        when(modelProvider.getIfAvailable()).thenReturn(embeddingModel);

        repoMgmtService = mock(RepositoryManagementService.class);
        helper = mock(BatchEmbeddingHelper.class);

        PackageSummaryGenerator.Accumulator accumulator = mock(PackageSummaryGenerator.Accumulator.class);
        when(summaryGenerator.createAccumulator()).thenReturn(accumulator);
        when(accumulator.generateSummaries()).thenReturn(List.of());

        retrievalConfig = new RetrievalConfig();
        retrievalConfig.setIndexDir(tempDir.resolve("sparse-index").toString());
        store = new LuceneGenerationStore(retrievalConfig, new LuceneDocumentMapper(), new LuceneQueryBuilder(retrievalConfig));
    }

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.close();
        }
    }

    @Test
    @DisplayName("Stages slices and package summaries before dispatch and publishes after persistence wait")
    void testStreamingSparseStagedBeforeDispatchAndPublishedAfterWait() throws Exception {
        String repoId = "streaming-repo";
        Path file1 = tempDir.resolve("File1.java");

        CodeChunk c1 = new CodeChunk();
        c1.setId("c1");
        c1.setElementName("UserService");
        c1.setContent("public class UserService {}");

        when(parser.parse(List.of(file1))).thenReturn(List.of(c1));

        CodeChunk pkgChunk = new CodeChunk();
        pkgChunk.setId("pkg-1");
        pkgChunk.setElementName("com.example");
        pkgChunk.setContent("package com.example;");

        PackageSummaryGenerator.Accumulator accumulator = mock(PackageSummaryGenerator.Accumulator.class);
        when(summaryGenerator.createAccumulator()).thenReturn(accumulator);
        when(accumulator.generateSummaries()).thenReturn(List.of(pkgChunk));

        LuceneGenerationStore spyStore = spy(store);

        StreamingIndexingService service = new StreamingIndexingService(
                parser, converter, budgetValidator, summaryGenerator,
                largeRepoConfig, chunkingConfig, embeddingQueue, modelProvider,
                repoMgmtService, helper, null, spyStore
        );

        int count = service.index(repoId, List.of(file1));
        assertEquals(2, count, "Should submit 1 file chunk + 1 package summary chunk");

        InOrder inOrder = inOrder(spyStore, embeddingQueue);
        inOrder.verify(spyStore).stageGeneration(eq(repoId));
        inOrder.verify(embeddingQueue).completeJob(anyString());
        inOrder.verify(embeddingQueue).awaitJobCompletion(anyString(), any(Duration.class));
        inOrder.verify(spyStore).publishGeneration(any(LuceneGenerationStore.StagingSession.class));

        assertTrue(spyStore.hasIndex(repoId));
        assertEquals(2, spyStore.getDocumentCount(repoId));

        List<Document> searchResults = spyStore.search(repoId, "UserService", 5);
        assertFalse(searchResults.isEmpty());
        assertEquals("UserService", searchResults.get(0).getMetadata().get("elementName"));
    }

    @Test
    @DisplayName("Discards staging session when persistence wait fails")
    void testStreamingFailureDiscardsSparseSession() throws Exception {
        String repoId = "streaming-fail";
        Path file1 = tempDir.resolve("File1.java");

        CodeChunk c1 = new CodeChunk();
        c1.setId("c1");
        c1.setContent("public class FailService {}");

        when(parser.parse(List.of(file1))).thenReturn(List.of(c1));
        doThrow(new RuntimeException("Persistence timeout")).when(embeddingQueue).awaitJobCompletion(anyString(), any());

        LuceneGenerationStore spyStore = spy(store);

        StreamingIndexingService service = new StreamingIndexingService(
                parser, converter, budgetValidator, summaryGenerator,
                largeRepoConfig, chunkingConfig, embeddingQueue, modelProvider,
                repoMgmtService, helper, null, spyStore
        );

        assertThrows(RuntimeException.class, () -> service.index(repoId, List.of(file1)));

        verify(spyStore).stageGeneration(eq(repoId));
        verify(spyStore).discardGeneration(any(LuceneGenerationStore.StagingSession.class));
        verify(spyStore, never()).publishGeneration(any());
        assertFalse(spyStore.hasIndex(repoId));
    }

    @Test
    @DisplayName("Demonstrates stale-row asymmetry: new sparse run cleans up obsolete chunks")
    void testStaleRowAsymmetry() throws Exception {
        String repoId = "asymmetry-repo";

        // Initial run with 2 files
        Path fileA = tempDir.resolve("A.java");
        Path fileB = tempDir.resolve("B.java");

        CodeChunk chunkA = new CodeChunk();
        chunkA.setId("c-a");
        chunkA.setElementName("CustomerProfile");
        chunkA.setContent("public class CustomerProfile {}");

        CodeChunk chunkB = new CodeChunk();
        chunkB.setId("c-b");
        chunkB.setElementName("InvoiceLedger");
        chunkB.setContent("public class InvoiceLedger {}");

        when(parser.parse(List.of(fileA))).thenReturn(List.of(chunkA));
        when(parser.parse(List.of(fileB))).thenReturn(List.of(chunkB));

        StreamingIndexingService service = new StreamingIndexingService(
                parser, converter, budgetValidator, summaryGenerator,
                largeRepoConfig, chunkingConfig, embeddingQueue, modelProvider,
                repoMgmtService, helper, null, store
        );

        service.index(repoId, List.of(fileA, fileB));
        assertEquals(2, store.getDocumentCount(repoId));

        // Re-run with file B deleted (only file A indexed)
        service.index(repoId, List.of(fileA));

        // Sparse index atomically replaces generation: InvoiceLedger is immediately gone!
        assertEquals(1, store.getDocumentCount(repoId));
        assertTrue(store.search(repoId, "InvoiceLedger", 5).isEmpty(),
                "Sparse generation must exclude deleted file B without requiring manual delete");
        assertFalse(store.search(repoId, "CustomerProfile", 5).isEmpty());
    }

    @Test
    @DisplayName("Optionality: succeeds without sparse store when luceneGenerationStore is null")
    void testOptionalSparseStoreInStreaming() throws Exception {
        String repoId = "no-sparse-streaming";
        Path file1 = tempDir.resolve("File1.java");

        CodeChunk c1 = new CodeChunk();
        c1.setId("c1");
        c1.setContent("public class Simple {}");

        when(parser.parse(List.of(file1))).thenReturn(List.of(c1));

        StreamingIndexingService service = new StreamingIndexingService(
                parser, converter, budgetValidator, summaryGenerator,
                largeRepoConfig, chunkingConfig, embeddingQueue, modelProvider,
                repoMgmtService, helper, null, null // null sparse store
        );

        int count = service.index(repoId, List.of(file1));
        assertEquals(1, count);
        verify(embeddingQueue).completeJob(anyString());
    }

    @Test
    @DisplayName("Streaming failure deletes only job-scoped vector IDs and preserves pre-existing repository vectors")
    void testStreamingFailureRollsBackOnlyJobStagedVectors() {
        String repoId = "fail-scoped-repo";
        Path file1 = tempDir.resolve("FailFile.java");

        CodeChunk c1 = new CodeChunk();
        c1.setId("c-fail-1");
        c1.setContent("public class FailTest {}");

        when(parser.parse(List.of(file1))).thenReturn(List.of(c1));
        doThrow(new RuntimeException("Embedding model crashed")).when(helper).embedAndStage(anyString(), anyString(), anyList());
        when(embeddingQueue.getPersistedRecordIds(anyString())).thenReturn(Set.of("persisted-id-1"));

        StreamingIndexingService service = new StreamingIndexingService(
                parser, converter, budgetValidator, summaryGenerator,
                largeRepoConfig, chunkingConfig, embeddingQueue, modelProvider,
                repoMgmtService, helper, null, store
        );

        assertThrows(RuntimeException.class, () -> service.index(repoId, List.of(file1)));

        // Job-scoped rollback was called with persisted IDs
        verify(repoMgmtService).deleteVectorsByIds(eq(Set.of("persisted-id-1")));
        // CRITICAL: deleteRepositoryVectors must NEVER be called to preserve pre-existing vectors
        verify(repoMgmtService, never()).deleteRepositoryVectors(anyString());
        // Repository marked inconsistent
        verify(repoMgmtService).markRepositoryInconsistent(eq(repoId), anyString());
    }

    @Test
    @DisplayName("Sparse publish failure in streaming deletes only job-scoped vector IDs and preserves pre-existing vectors")
    void testStreamingSparsePublishFailureRollsBackOnlyJobStagedVectors() throws Exception {
        String repoId = "sparse-fail-scoped-repo";
        Path file1 = tempDir.resolve("SparseFailFile.java");

        CodeChunk c1 = new CodeChunk();
        c1.setId("c-sparse-fail-1");
        c1.setContent("public class SparseFailTest {}");

        when(parser.parse(List.of(file1))).thenReturn(List.of(c1));
        when(embeddingQueue.getPersistedRecordIds(anyString())).thenReturn(Set.of("persisted-id-2"));

        LuceneGenerationStore spyStore = spy(store);
        doThrow(new RuntimeException("Lucene disk write failed")).when(spyStore).publishGeneration(any());

        StreamingIndexingService service = new StreamingIndexingService(
                parser, converter, budgetValidator, summaryGenerator,
                largeRepoConfig, chunkingConfig, embeddingQueue, modelProvider,
                repoMgmtService, helper, null, spyStore
        );

        assertThrows(RuntimeException.class, () -> service.index(repoId, List.of(file1)));

        // Job-scoped rollback was called with persisted IDs
        verify(repoMgmtService).deleteVectorsByIds(eq(Set.of("persisted-id-2")));
        // CRITICAL: deleteRepositoryVectors must NEVER be called
        verify(repoMgmtService, never()).deleteRepositoryVectors(anyString());
        // Repository marked inconsistent
        verify(repoMgmtService).markRepositoryInconsistent(eq(repoId), contains("Sparse publish failed"));
    }

    @Test
    @DisplayName("Streaming re-indexing failure strictly preserves pre-existing dense vectors from previous successful run")
    void testStreamingFailurePreservesPreExistingVectorsOnReindex() {
        String repoId = "reindex-repo";
        Path file1 = tempDir.resolve("ReindexFile.java");

        CodeChunk c1 = new CodeChunk();
        c1.setId("c-reindex-1");
        c1.setContent("public class ReindexTest {}");

        when(parser.parse(List.of(file1))).thenReturn(List.of(c1));
        // Repository already had pre-existing vectors from previous run
        when(repoMgmtService.getRepositoryVectorIds(eq(repoId))).thenReturn(Set.of("old-vec-1", "old-vec-2"));
        // Current job re-persisted old-vec-1
        when(embeddingQueue.getPersistedRecordIds(anyString())).thenReturn(Set.of("old-vec-1"));
        doThrow(new RuntimeException("Embedding worker failed")).when(helper).embedAndStage(anyString(), anyString(), anyList());

        StreamingIndexingService service = new StreamingIndexingService(
                parser, converter, budgetValidator, summaryGenerator,
                largeRepoConfig, chunkingConfig, embeddingQueue, modelProvider,
                repoMgmtService, helper, null, store
        );

        assertThrows(RuntimeException.class, () -> service.index(repoId, List.of(file1)));

        // Pre-existing vectors must NOT be deleted
        verify(repoMgmtService, never()).deleteVectorsByIds(any());
        verify(repoMgmtService, never()).deleteRepositoryVectors(anyString());
        // Repository marked inconsistent
        verify(repoMgmtService).markRepositoryInconsistent(eq(repoId), anyString());
    }

    @Test
    @DisplayName("Streaming re-indexing success safely cleans up obsolete vectors from previous runs while preserving re-persisted vectors")
    void testStreamingReindexSuccessCleansUpObsoleteVectors() throws Exception {
        String repoId = "reindex-success-repo";
        Path file1 = tempDir.resolve("ReindexSuccessFile.java");

        CodeChunk c1 = new CodeChunk();
        c1.setId("c-reindex-success-1");
        c1.setContent("public class ReindexSuccessTest {}");

        when(parser.parse(List.of(file1))).thenReturn(List.of(c1));
        // Repository had 2 vectors previously: old-obsolete-vec and retained-vec
        when(repoMgmtService.getRepositoryVectorIds(eq(repoId))).thenReturn(Set.of("old-obsolete-vec", "retained-vec"));
        // Current job persisted retained-vec and newly-persisted-vec
        when(embeddingQueue.getPersistedRecordIds(anyString())).thenReturn(Set.of("retained-vec", "newly-persisted-vec"));

        LuceneGenerationStore spyStore = spy(store);

        StreamingIndexingService service = new StreamingIndexingService(
                parser, converter, budgetValidator, summaryGenerator,
                largeRepoConfig, chunkingConfig, embeddingQueue, modelProvider,
                repoMgmtService, helper, null, spyStore
        );

        int count = service.index(repoId, List.of(file1));
        assertTrue(count > 0);

        // Verification: obsolete vector from previous run must be deleted
        verify(repoMgmtService).deleteVectorsByIds(eq(Set.of("old-obsolete-vec")));
        // Retained vector and newly persisted vector must NOT be deleted
        verify(repoMgmtService, never()).deleteRepositoryVectors(anyString());
        // Repository must NOT be marked inconsistent on success
        verify(repoMgmtService, never()).markRepositoryInconsistent(eq(repoId), anyString());
    }

    @Test
    @DisplayName("Settlement timeout defers/skips vector deletion while in-flight writers may still commit")
    void testStreamingFailureSkipsDeleteWhenSettlementTimesOut() throws Exception {
        String repoId = "settle-timeout-repo";
        Path file1 = tempDir.resolve("TimeoutFile.java");

        CodeChunk c1 = new CodeChunk();
        c1.setId("c-timeout-1");
        c1.setContent("public class TimeoutTest {}");

        when(parser.parse(List.of(file1))).thenReturn(List.of(c1));
        doThrow(new RuntimeException("Worker failed")).when(helper).embedAndStage(anyString(), anyString(), anyList());
        // Settlement wait times out
        doThrow(new TimeoutException("In-flight records did not settle")).when(embeddingQueue).awaitJobSettled(anyString(), any());
        when(embeddingQueue.getPersistedRecordIds(anyString())).thenReturn(Set.of("p-1"));

        StreamingIndexingService service = new StreamingIndexingService(
                parser, converter, budgetValidator, summaryGenerator,
                largeRepoConfig, chunkingConfig, embeddingQueue, modelProvider,
                repoMgmtService, helper, null, store
        );

        assertThrows(RuntimeException.class, () -> service.index(repoId, List.of(file1)));

        // Because settlement timed out, vector deletion must be skipped to avoid race with in-flight writers
        verify(repoMgmtService, never()).deleteVectorsByIds(any());
        verify(repoMgmtService, never()).deleteRepositoryVectors(anyString());
        verify(repoMgmtService).markRepositoryInconsistent(eq(repoId), anyString());
    }

    @Test
    @DisplayName("Failed existing-vector lookup propagates error, skips dense rollback, and disposes staging session and queue resources")
    void testFailedExistingVectorLookupDisposesResourcesAndPreservesVectors() throws Exception {
        String repoId = "lookup-fail-repo";
        Path file1 = tempDir.resolve("LookupFailFile.java");

        CodeChunk c1 = new CodeChunk();
        c1.setId("c-lookup-fail-1");
        c1.setContent("public class LookupFailTest {}");

        when(parser.parse(List.of(file1))).thenReturn(List.of(c1));

        // Simulate database lookup failure
        doThrow(new org.springframework.dao.CannotAcquireLockException("Database connection timeout"))
                .when(repoMgmtService).getRepositoryVectorIds(eq(repoId));

        LuceneGenerationStore spyStore = spy(store);

        StreamingIndexingService service = new StreamingIndexingService(
                parser, converter, budgetValidator, summaryGenerator,
                largeRepoConfig, chunkingConfig, embeddingQueue, modelProvider,
                repoMgmtService, helper, null, spyStore
        );

        org.springframework.dao.CannotAcquireLockException thrown = assertThrows(
                org.springframework.dao.CannotAcquireLockException.class,
                () -> service.index(repoId, List.of(file1)),
                "Lookup failure must be propagated out of streaming index pipeline"
        );
        assertTrue(thrown.getMessage().contains("Database connection timeout"));

        // Staging session must be discarded and never published
        verify(spyStore).stageGeneration(eq(repoId));
        verify(spyStore).discardGeneration(any(LuceneGenerationStore.StagingSession.class));
        verify(spyStore, never()).publishGeneration(any());

        // Queue resources must be failed and cleaned up
        verify(embeddingQueue).failJob(anyString(), any());
        verify(embeddingQueue).cleanupJob(anyString());

        // Writers must never have been dispatched
        verify(helper, never()).embedAndStage(anyString(), anyString(), anyList());

        // Dense vectors must NEVER be rolled back / deleted
        verify(repoMgmtService, never()).deleteVectorsByIds(any());
        verify(repoMgmtService, never()).deleteRepositoryVectors(anyString());
        // Since lookup failed before writes occurred, repository must not be marked inconsistent
        verify(repoMgmtService, never()).markRepositoryInconsistent(eq(repoId), anyString());
    }
}
