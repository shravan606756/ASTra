package com.shravan.jcode_intelligence.service;

import com.shravan.jcode_intelligence.config.ChunkingConfig;
import com.shravan.jcode_intelligence.config.RetrievalConfig;
import com.shravan.jcode_intelligence.converter.DocumentConverter;
import com.shravan.jcode_intelligence.model.CodeChunk;
import com.shravan.jcode_intelligence.model.IndexingStatistics;
import com.shravan.jcode_intelligence.parser.EmbeddingBudgetValidator;
import com.shravan.jcode_intelligence.parser.IndexingStatisticsCalculator;
import com.shravan.jcode_intelligence.parser.JavaProjectParser;
import com.shravan.jcode_intelligence.parser.PackageSummaryGenerator;
import com.shravan.jcode_intelligence.service.impl.IndexingServiceImpl;
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
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class IndexingServiceSparseIntegrationTest {

    @TempDir
    Path tempDir;

    private JavaProjectParser parser;
    private DocumentConverter converter;
    private VectorStore vectorStore;
    private GitService gitService;
    private JdbcTemplate jdbcTemplate;
    private PackageSummaryGenerator packageSummaryGenerator;
    private IndexingStatisticsCalculator statisticsCalculator;
    private EmbeddingBudgetValidator budgetValidator;
    private ChunkingConfig chunkingConfig;
    private RepositoryManagementService repositoryManagementService;
    private RepositoryConcurrencyManager concurrencyManager;

    private RetrievalConfig retrievalConfig;
    private LuceneGenerationStore store;

    @BeforeEach
    void setUp() {
        parser = mock(JavaProjectParser.class);
        converter = new DocumentConverter();
        vectorStore = mock(VectorStore.class);
        gitService = mock(GitService.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        packageSummaryGenerator = mock(PackageSummaryGenerator.class);
        statisticsCalculator = mock(IndexingStatisticsCalculator.class);
        budgetValidator = mock(EmbeddingBudgetValidator.class);
        chunkingConfig = new ChunkingConfig();
        repositoryManagementService = mock(RepositoryManagementService.class);
        concurrencyManager = new RepositoryConcurrencyManager();

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
    @DisplayName("Stages sparse generation before dense deletion and publishes before statistics")
    void testSparseStagedBeforeDenseDeletionAndPublishedBeforeStats() throws IOException {
        String repoId = "test-repo";
        Path projectRoot = tempDir.resolve("my-proj");

        CodeChunk chunk = new CodeChunk();
        chunk.setId("c1");
        chunk.setElementName("findUser");
        chunk.setContent("public void findUser() {}");

        when(parser.parse(any(Path.class))).thenReturn(List.of(chunk));
        when(packageSummaryGenerator.generatePackageSummaries(anyList())).thenReturn(List.of());
        when(statisticsCalculator.calculate(anyList(), anyLong())).thenReturn(mock(IndexingStatistics.class));

        LuceneGenerationStore spyStore = spy(store);

        IndexingServiceImpl indexingService = new IndexingServiceImpl(
                parser, converter, vectorStore, gitService, jdbcTemplate,
                packageSummaryGenerator, statisticsCalculator, budgetValidator,
                chunkingConfig, repositoryManagementService, concurrencyManager,
                null, null, null, null, null, spyStore
        );

        int count = indexingService.indexProject(projectRoot.toString(), repoId);
        assertEquals(1, count);

        // Verify ordering: stageGeneration -> deleteExistingRepositoryVectors -> publishGeneration -> statistics
        InOrder inOrder = inOrder(spyStore, jdbcTemplate, statisticsCalculator);
        inOrder.verify(spyStore).stageGeneration(eq(repoId));
        inOrder.verify(jdbcTemplate).update(contains("DELETE FROM vector_store"), eq(repoId));
        inOrder.verify(spyStore).publishGeneration(any(LuceneGenerationStore.StagingSession.class));
        inOrder.verify(statisticsCalculator).calculate(anyList(), anyLong());

        assertTrue(spyStore.hasIndex(repoId), "Published generation must exist");
        assertEquals(1, spyStore.getDocumentCount(repoId));
    }

    @Test
    @DisplayName("Discards sparse generation on vector store failure and does not publish CURRENT")
    void testDiscardsOnDensePersistenceFailure() throws IOException {
        String repoId = "fail-repo";
        Path projectRoot = tempDir.resolve("fail-proj");

        CodeChunk chunk = new CodeChunk();
        chunk.setId("c1");
        chunk.setContent("public void test() {}");

        when(parser.parse(any(Path.class))).thenReturn(List.of(chunk));
        when(packageSummaryGenerator.generatePackageSummaries(anyList())).thenReturn(List.of());
        doThrow(new RuntimeException("Database offline")).when(vectorStore).add(anyList());

        LuceneGenerationStore spyStore = spy(store);

        IndexingServiceImpl indexingService = new IndexingServiceImpl(
                parser, converter, vectorStore, gitService, jdbcTemplate,
                packageSummaryGenerator, statisticsCalculator, budgetValidator,
                chunkingConfig, repositoryManagementService, concurrencyManager,
                null, null, null, null, null, spyStore
        );

        assertThrows(RuntimeException.class, () -> indexingService.indexProject(projectRoot.toString(), repoId));

        verify(spyStore).stageGeneration(eq(repoId));
        verify(spyStore).discardGeneration(any(LuceneGenerationStore.StagingSession.class));
        verify(spyStore, never()).publishGeneration(any());
        assertFalse(spyStore.hasIndex(repoId), "Must not have active index after failure");
    }

    @Test
    @DisplayName("Optionality: succeeds without sparse store when luceneGenerationStore is null")
    void testOptionalSparseStore() throws IOException {
        String repoId = "no-sparse-repo";
        Path projectRoot = tempDir.resolve("no-sparse");

        CodeChunk chunk = new CodeChunk();
        chunk.setId("c1");
        chunk.setContent("public void test() {}");

        when(parser.parse(any(Path.class))).thenReturn(List.of(chunk));
        when(packageSummaryGenerator.generatePackageSummaries(anyList())).thenReturn(List.of());
        when(statisticsCalculator.calculate(anyList(), anyLong())).thenReturn(mock(IndexingStatistics.class));

        IndexingServiceImpl indexingService = new IndexingServiceImpl(
                parser, converter, vectorStore, gitService, jdbcTemplate,
                packageSummaryGenerator, statisticsCalculator, budgetValidator,
                chunkingConfig, repositoryManagementService, concurrencyManager,
                null, null, null, null, null, null // null sparse store
        );

        int count = indexingService.indexProject(projectRoot.toString(), repoId);
        assertEquals(1, count);
        verify(vectorStore).add(anyList());
    }
}
