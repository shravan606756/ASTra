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
}
