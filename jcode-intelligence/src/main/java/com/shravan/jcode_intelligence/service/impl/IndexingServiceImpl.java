package com.shravan.jcode_intelligence.service.impl;

import com.shravan.jcode_intelligence.config.ChunkingConfig;
import com.shravan.jcode_intelligence.converter.DocumentConverter;
import com.shravan.jcode_intelligence.model.CodeChunk;
import com.shravan.jcode_intelligence.model.IndexingStatistics;
import com.shravan.jcode_intelligence.parser.EmbeddingBudgetValidator;
import com.shravan.jcode_intelligence.parser.JavaProjectParser;
import com.shravan.jcode_intelligence.parser.PackageSummaryGenerator;
import com.shravan.jcode_intelligence.parser.IndexingStatisticsCalculator;
import com.shravan.jcode_intelligence.service.GitService;
import com.shravan.jcode_intelligence.service.IndexingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Service
public class IndexingServiceImpl implements IndexingService {

    private static final Logger log = LoggerFactory.getLogger(IndexingServiceImpl.class);

    private final JavaProjectParser parser;
    private final DocumentConverter converter;
    private final VectorStore vectorStore;
    private final GitService gitService;
    private final JdbcTemplate jdbcTemplate;
    private final PackageSummaryGenerator packageSummaryGenerator;
    private final IndexingStatisticsCalculator statisticsCalculator;
    private final EmbeddingBudgetValidator budgetValidator;
    private final ChunkingConfig chunkingConfig;

    public IndexingServiceImpl(JavaProjectParser parser,
                               DocumentConverter converter,
                               VectorStore vectorStore,
                               GitService gitService,
                               JdbcTemplate jdbcTemplate,
                               PackageSummaryGenerator packageSummaryGenerator,
                               IndexingStatisticsCalculator statisticsCalculator,
                               EmbeddingBudgetValidator budgetValidator,
                               ChunkingConfig chunkingConfig) {

        this.parser = parser;
        this.converter = converter;
        this.vectorStore = vectorStore;
        this.gitService = gitService;
        this.jdbcTemplate = jdbcTemplate;
        this.packageSummaryGenerator = packageSummaryGenerator;
        this.statisticsCalculator = statisticsCalculator;
        this.budgetValidator = budgetValidator;
        this.chunkingConfig = chunkingConfig;
    }

    @Override
    public int indexProject(String projectPath) throws IOException {
        String defaultRepoId = Path.of(projectPath).getFileName() != null
                ? Path.of(projectPath).getFileName().toString()
                : "default";
        return indexProject(projectPath, defaultRepoId);
    }

    @Override
    public int indexProject(String projectPath, String repositoryId) throws IOException {
        long startTime = System.currentTimeMillis();

        // [STEP 1] Parse repository source files into element chunks
        log.info("[STEP 1] Parsing repository source files at path: {}", projectPath);
        List<CodeChunk> elementChunks = parser.parse(Path.of(projectPath));
        log.info("[STEP 1 COMPLETE] Generated {} element chunk(s)", elementChunks.size());

        // [STEP 2] Generate package summaries across the entire repository
        log.info("[STEP 2] Generating repository-wide package summaries...");
        List<CodeChunk> packageChunks = packageSummaryGenerator.generatePackageSummaries(elementChunks);
        log.info("[STEP 2 COMPLETE] Generated {} package summary chunk(s)", packageChunks.size());

        // Combine element chunks and repo-wide package summary chunks
        List<CodeChunk> allChunks = new ArrayList<>(elementChunks);
        allChunks.addAll(packageChunks);

        if (repositoryId != null && !repositoryId.isBlank()) {
            for (CodeChunk chunk : allChunks) {
                chunk.setRepositoryId(repositoryId);
            }
        }

        // [STEP 3] Convert to Spring AI Document objects
        log.info("[STEP 3] Converting {} CodeChunks to Spring AI Documents...", allChunks.size());
        List<Document> documents = converter.convert(allChunks);
        log.info("[STEP 3 COMPLETE] Converted {} Document(s)", documents.size());

        // [STEP 4] Pre-embedding budget validation and observability audit
        log.info("[STEP 4] Validating document sizes against embedding budget...");
        budgetValidator.validateAndAudit(documents, allChunks);
        log.info("[STEP 4 COMPLETE] All {} document(s) passed pre-embedding budget validation", documents.size());

        // [STEP 5] Delete existing vectors for this repository
        if (repositoryId != null && !repositoryId.isBlank()) {
            log.info("[STEP 5] Cleaning up existing vector records for repository: {}", repositoryId);
            deleteExistingRepositoryVectors(repositoryId);
        }

        // [STEP 6] Batch vector store insertion
        int totalDocs = documents.size();
        int batchSize = Math.max(1, chunkingConfig.getBatchSize());
        int totalBatches = (int) Math.ceil((double) totalDocs / batchSize);

        log.info("[STEP 6] Starting batch vector store insertion for {} document(s) in {} batch(es) (batch size: {})...",
                totalDocs, totalBatches, batchSize);

        long embeddingStartTime = System.currentTimeMillis();

        for (int i = 0; i < totalDocs; i += batchSize) {
            int endIdx = Math.min(i + batchSize, totalDocs);
            List<Document> batch = documents.subList(i, endIdx);
            int batchNum = (i / batchSize) + 1;

            log.info("[BATCH {}/{}] Requesting embeddings & vector storage for documents {}-{} of {}...",
                    batchNum, totalBatches, i + 1, endIdx, totalDocs);

            long batchStart = System.currentTimeMillis();
            vectorStore.add(batch);
            long batchDuration = System.currentTimeMillis() - batchStart;

            double pct = ((double) endIdx / totalDocs) * 100.0;
            log.info("[BATCH {}/{} COMPLETE] Embedded & stored {} vector(s) in {} ms | Progress: {}/{} ({%.1f%%})",
                    batchNum, totalBatches, batch.size(), batchDuration, endIdx, totalDocs, pct);
        }

        long embeddingDuration = System.currentTimeMillis() - embeddingStartTime;
        log.info("[STEP 6 COMPLETE] Successfully embedded & indexed {} document(s) across {} batch(es) in {} ms",
                totalDocs, totalBatches, embeddingDuration);

        long duration = System.currentTimeMillis() - startTime;

        // [STEP 7] Compute and log structured indexing statistics
        IndexingStatistics stats = statisticsCalculator.calculate(allChunks, duration);
        log.info("\n{}", stats);

        return documents.size();
    }

    private void deleteExistingRepositoryVectors(String repositoryId) {
        try {
            log.info("Deleting existing vectors for repositoryId: {} via SQL", repositoryId);
            String sql = "DELETE FROM vector_store WHERE metadata->>'repositoryId' = ?";
            int rowsDeleted = jdbcTemplate.update(sql, repositoryId);
            log.info("Deleted {} existing vector record(s) for repositoryId: {}", rowsDeleted, repositoryId);
        } catch (Exception e) {
            log.error("Failed to delete existing vectors for repositoryId {}: {}", repositoryId, e.getMessage(), e);
        }
    }

    @Override
    public int indexGitRepository(String gitUrl) throws IOException {
        String repoId = extractRepoName(gitUrl);
        return indexGitRepository(gitUrl, repoId);
    }

    @Override
    public int indexGitRepository(String gitUrl, String repositoryId) throws IOException {
        Path tempDir = null;
        try {
            log.info("Cloning Git repository: {}", gitUrl);
            tempDir = gitService.cloneRepository(gitUrl);
            return indexProject(tempDir.toString(), repositoryId);
        } finally {
            if (tempDir != null) {
                log.info("Cleaning up temporary git directory: {}", tempDir);
                gitService.cleanupRepository(tempDir);
            }
        }
    }

    private String extractRepoName(String gitUrl) {
        if (gitUrl == null) return "git-repo";
        String name = gitUrl.substring(gitUrl.lastIndexOf('/') + 1);
        if (name.endsWith(".git")) {
            name = name.substring(0, name.length() - 4);
        }
        return name.isEmpty() ? "git-repo" : name;
    }
}