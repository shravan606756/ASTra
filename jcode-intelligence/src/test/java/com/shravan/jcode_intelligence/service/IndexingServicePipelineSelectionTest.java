package com.shravan.jcode_intelligence.service;

import com.shravan.jcode_intelligence.config.ChunkingConfig;
import com.shravan.jcode_intelligence.converter.DocumentConverter;
import com.shravan.jcode_intelligence.model.CodeChunk;
import com.shravan.jcode_intelligence.model.IndexingStatistics;
import com.shravan.jcode_intelligence.parser.EmbeddingBudgetValidator;
import com.shravan.jcode_intelligence.parser.IndexingStatisticsCalculator;
import com.shravan.jcode_intelligence.parser.JavaProjectParser;
import com.shravan.jcode_intelligence.parser.PackageSummaryGenerator;
import com.shravan.jcode_intelligence.service.classification.*;
import com.shravan.jcode_intelligence.service.impl.IndexingServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class IndexingServicePipelineSelectionTest {

    private JavaProjectParser mockParser;
    private DocumentConverter mockConverter;
    private VectorStore mockVectorStore;
    private GitService mockGitService;
    private JdbcTemplate mockJdbcTemplate;
    private PackageSummaryGenerator mockSummaryGenerator;
    private IndexingStatisticsCalculator mockStatsCalculator;
    private EmbeddingBudgetValidator mockBudgetValidator;
    private ChunkingConfig chunkingConfig;
    private RepositoryManagementService mockRepoMgmtService;
    private RepositoryConcurrencyManager concurrencyManager;
    private RepositoryClassifier mockClassifier;
    private IndexingPipelineSelector mockSelector;

    @BeforeEach
    void setUp() throws IOException {
        mockParser = mock(JavaProjectParser.class);
        mockConverter = mock(DocumentConverter.class);
        mockVectorStore = mock(VectorStore.class);
        mockGitService = mock(GitService.class);
        mockJdbcTemplate = mock(JdbcTemplate.class);
        mockSummaryGenerator = mock(PackageSummaryGenerator.class);
        mockStatsCalculator = mock(IndexingStatisticsCalculator.class);
        mockBudgetValidator = mock(EmbeddingBudgetValidator.class);
        chunkingConfig = new ChunkingConfig();
        mockRepoMgmtService = mock(RepositoryManagementService.class);
        concurrencyManager = new RepositoryConcurrencyManager();
        mockClassifier = mock(RepositoryClassifier.class);
        mockSelector = mock(IndexingPipelineSelector.class);

        when(mockSummaryGenerator.generatePackageSummaries(any())).thenReturn(List.of());
        when(mockConverter.convert(anyList())).thenAnswer(inv -> {
            List<CodeChunk> inputChunks = inv.getArgument(0);
            List<Document> resultDocs = new ArrayList<>();
            for (CodeChunk c : inputChunks) {
                resultDocs.add(new Document(c.getContent() != null ? c.getContent() : "",
                        Map.of("chunkId", c.getId() != null ? c.getId() : "")));
            }
            return resultDocs;
        });
        when(mockStatsCalculator.calculate(any(), anyLong())).thenReturn(mock(IndexingStatistics.class));
    }

    private CodeChunk createChunk(String id, String content) {
        CodeChunk chunk = new CodeChunk();
        chunk.setId(id);
        chunk.setContent(content);
        chunk.setType("CLASS");
        return chunk;
    }

    @Test
    @DisplayName("NORMAL classification passes exact eligibleFiles list to parser and produces expected chunks")
    void testNormalClassificationUsesExactListParsing() throws Exception {
        List<Path> eligibleFiles = List.of(Path.of("src/User.java"), Path.of("src/UserService.java"));
        RepositoryClassificationResult result = new RepositoryClassificationResult(
                RepositoryClass.NORMAL,
                200L,
                eligibleFiles.size(),
                eligibleFiles,
                1_000_000_000L,
                400_000_000L,
                120_000L,
                false
        );

        when(mockClassifier.classify(any(Path.class))).thenReturn(result);
        when(mockSelector.select(eq(result), eq("repo-normal")))
                .thenReturn(new PipelineSelection(IndexingPipeline.EXISTING, IndexingPipeline.EXISTING, false, null));

        List<CodeChunk> chunks = List.of(createChunk("c1", "User"), createChunk("c2", "UserService"));
        when(mockParser.parse(eligibleFiles)).thenReturn(chunks);

        List<Document> docs = List.of(
                new Document("User", Map.of("chunkId", "c1")),
                new Document("UserService", Map.of("chunkId", "c2"))
        );
        when(mockConverter.convert(anyList())).thenReturn(docs);

        IndexingServiceImpl service = new IndexingServiceImpl(
                mockParser, mockConverter, mockVectorStore, mockGitService,
                mockJdbcTemplate, mockSummaryGenerator, mockStatsCalculator,
                mockBudgetValidator, chunkingConfig, mockRepoMgmtService,
                concurrencyManager, null, null,
                mockClassifier, mockSelector
        );

        int indexedCount = service.indexProject(".", "repo-normal");

        assertEquals(2, indexedCount);
        verify(mockParser).parse(eligibleFiles);
        verify(mockParser, never()).parse(any(Path.class));
        verify(mockVectorStore).add(docs);
        assertFalse(concurrencyManager.isLocked("repo-normal"), "Lock must be released");
    }

    @Test
    @DisplayName("LARGE classification requests STREAMING, falls back to EXISTING, and parses exact eligibleFiles")
    void testLargeClassificationFallsBackToExistingListParsing() throws Exception {
        List<Path> eligibleFiles = List.of(Path.of("src/Large1.java"), Path.of("src/Large2.java"));
        RepositoryClassificationResult result = new RepositoryClassificationResult(
                RepositoryClass.LARGE,
                50_000L,
                eligibleFiles.size(),
                eligibleFiles,
                100_000_000L,
                40_000_000L,
                60_000_000L,
                false
        );

        when(mockClassifier.classify(any(Path.class))).thenReturn(result);
        when(mockSelector.select(eq(result), eq("repo-large")))
                .thenReturn(new PipelineSelection(
                        IndexingPipeline.STREAMING,
                        IndexingPipeline.EXISTING,
                        true,
                        "streaming pipeline (#31) is not yet available; falling back to existing pipeline"));

        List<CodeChunk> chunks = List.of(createChunk("c1", "Large1"), createChunk("c2", "Large2"));
        when(mockParser.parse(eligibleFiles)).thenReturn(chunks);

        IndexingServiceImpl service = new IndexingServiceImpl(
                mockParser, mockConverter, mockVectorStore, mockGitService,
                mockJdbcTemplate, mockSummaryGenerator, mockStatsCalculator,
                mockBudgetValidator, chunkingConfig, mockRepoMgmtService,
                concurrencyManager, null, null,
                mockClassifier, mockSelector
        );

        int indexedCount = service.indexProject(".", "repo-large");

        assertEquals(2, indexedCount);
        verify(mockParser).parse(eligibleFiles);
        verify(mockParser, never()).parse(any(Path.class));
        assertFalse(concurrencyManager.isLocked("repo-large"), "Lock must be released");
    }

    @Test
    @DisplayName("Classification failure falls back to path parsing and allows indexing to complete")
    void testClassificationFailureFallsBackToPathParsing() throws Exception {
        when(mockClassifier.classify(any(Path.class)))
                .thenThrow(new IOException("Simulated disk error during classification"));
        when(mockSelector.selectFailure(anyString()))
                .thenReturn(new PipelineSelection(IndexingPipeline.EXISTING, IndexingPipeline.EXISTING, true, "classification failed"));

        List<CodeChunk> chunks = List.of(createChunk("c1", "FallbackChunk"));
        when(mockParser.parse(any(Path.class))).thenReturn(chunks);

        IndexingServiceImpl service = new IndexingServiceImpl(
                mockParser, mockConverter, mockVectorStore, mockGitService,
                mockJdbcTemplate, mockSummaryGenerator, mockStatsCalculator,
                mockBudgetValidator, chunkingConfig, mockRepoMgmtService,
                concurrencyManager, null, null,
                mockClassifier, mockSelector
        );

        int indexedCount = service.indexProject(".", "repo-fallback");

        assertEquals(1, indexedCount);
        verify(mockParser).parse(Path.of("."));
        verify(mockParser, never()).parse(anyList());
        verify(mockSelector).selectFailure(eq("classification failed"));
        assertFalse(concurrencyManager.isLocked("repo-fallback"), "Lock must be released");
    }

    @Test
    @DisplayName("Classification failure followed by fallback parse failure propagates and releases lock without downstream execution")
    void testClassificationAndFallbackParseFailurePropagates() throws Exception {
        when(mockClassifier.classify(any(Path.class)))
                .thenThrow(new RuntimeException("Simulated classification error"));
        when(mockSelector.selectFailure(anyString()))
                .thenReturn(new PipelineSelection(IndexingPipeline.EXISTING, IndexingPipeline.EXISTING, true, "classification failed"));
        when(mockParser.parse(any(Path.class)))
                .thenThrow(new IOException("Simulated parser disk failure"));

        IndexingServiceImpl service = new IndexingServiceImpl(
                mockParser, mockConverter, mockVectorStore, mockGitService,
                mockJdbcTemplate, mockSummaryGenerator, mockStatsCalculator,
                mockBudgetValidator, chunkingConfig, mockRepoMgmtService,
                concurrencyManager, null, null,
                mockClassifier, mockSelector
        );

        IOException ex = assertThrows(IOException.class, () -> service.indexProject(".", "repo-fail-propagate"));
        assertEquals("Simulated parser disk failure", ex.getMessage());

        assertFalse(concurrencyManager.isLocked("repo-fail-propagate"), "Lock must be released on failure");
        verify(mockSummaryGenerator, never()).generatePackageSummaries(any());
        verify(mockConverter, never()).convert(anyList());
        verify(mockVectorStore, never()).add(anyList());
        verify(mockStatsCalculator, never()).calculate(any(), anyLong());
    }

    @Test
    @DisplayName("Legacy constructors without classifier or selector call parse(Path) directly")
    void testLegacyConstructorsCallPathParsing() throws Exception {
        when(mockParser.parse(any(Path.class))).thenReturn(List.of(createChunk("legacy", "LegacyChunk")));

        // 13-argument constructor
        IndexingServiceImpl service13 = new IndexingServiceImpl(
                mockParser, mockConverter, mockVectorStore, mockGitService,
                mockJdbcTemplate, mockSummaryGenerator, mockStatsCalculator,
                mockBudgetValidator, chunkingConfig, mockRepoMgmtService,
                concurrencyManager, null, null
        );

        int count13 = service13.indexProject(".", "repo-legacy-13");
        assertEquals(1, count13);
        verify(mockParser).parse(Path.of("."));
        assertFalse(concurrencyManager.isLocked("repo-legacy-13"));

        // 10-argument constructor
        IndexingServiceImpl service10 = new IndexingServiceImpl(
                mockParser, mockConverter, mockVectorStore, mockGitService,
                mockJdbcTemplate, mockSummaryGenerator, mockStatsCalculator,
                mockBudgetValidator, chunkingConfig, mockRepoMgmtService
        );

        int count10 = service10.indexProject(".", "repo-legacy-10");
        assertEquals(1, count10);
        verify(mockParser, times(2)).parse(Path.of("."));
        verify(mockParser, never()).parse(anyList());
    }
}
