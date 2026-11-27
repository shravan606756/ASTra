package com.shravan.jcode_intelligence.service.impl;

import com.shravan.jcode_intelligence.config.ChunkingConfig;
import com.shravan.jcode_intelligence.converter.DocumentConverter;
import com.shravan.jcode_intelligence.model.CodeChunk;
import com.shravan.jcode_intelligence.model.EmbeddedRecord;
import com.shravan.jcode_intelligence.model.IndexingStatistics;
import com.shravan.jcode_intelligence.parser.EmbeddingBudgetValidator;
import com.shravan.jcode_intelligence.parser.JavaProjectParser;
import com.shravan.jcode_intelligence.parser.PackageSummaryGenerator;
import com.shravan.jcode_intelligence.parser.IndexingStatisticsCalculator;
import com.shravan.jcode_intelligence.service.BatchEmbeddingHelper;
import com.shravan.jcode_intelligence.service.EmbeddingQueue;
import com.shravan.jcode_intelligence.service.GitService;
import com.shravan.jcode_intelligence.service.IndexingService;
import com.shravan.jcode_intelligence.service.RepositoryConcurrencyManager;
import com.shravan.jcode_intelligence.service.classification.IndexingPipeline;
import com.shravan.jcode_intelligence.service.classification.IndexingPipelineSelector;
import com.shravan.jcode_intelligence.service.classification.PipelineSelection;
import com.shravan.jcode_intelligence.service.classification.RepositoryClassificationResult;
import com.shravan.jcode_intelligence.service.classification.RepositoryClassifier;
import com.shravan.jcode_intelligence.service.streaming.StreamingIndexingService;
import com.shravan.jcode_intelligence.sparse.store.LuceneGenerationStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Service implementation for parsing, chunking, embedding, and indexing Java projects
 * into PGVector store.
 *
 * <p>Supports memory-aware classification, routing to either the whole-repository single-pass
 * (NORMAL) pipeline or the chunk-streaming (LARGE) pipeline (Issue #31).
 *
 * <p><strong>Write Fencing &amp; Persistence Semantics for LARGE Streaming:</strong>
 * <ul>
 *     <li>The streaming pipeline does not delete existing vector records; matching deterministic
 *     chunk IDs are updated via {@code ON CONFLICT (id) DO UPDATE}.</li>
 *     <li>Stale rows remain if classes or methods are removed from the repository.</li>
 *     <li>In-flight JDBC writes may finish after failure because the queue does not implement write fencing.</li>
 * </ul>
 */
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
    private final com.shravan.jcode_intelligence.service.RepositoryManagementService repositoryManagementService;
    private final RepositoryConcurrencyManager concurrencyManager;
    private final EmbeddingQueue embeddingQueue;
    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;
    private final RepositoryClassifier classifier;
    private final IndexingPipelineSelector selector;
    private final StreamingIndexingService streamingIndexingService;
    private final LuceneGenerationStore luceneGenerationStore;

    /**
     * Primary constructor with all dependencies including classification, queue persistence,
     * optional streaming service, and optional sparse Lucene generation store.
     *
     * <p>The {@link StreamingIndexingService} dependency is marked {@link Nullable} so Spring
     * treats it as optional and permits null arguments passed by delegating constructors (e.g.
     * during unit testing or when streaming is disabled).
     */
    @Autowired
    public IndexingServiceImpl(
            JavaProjectParser parser,
            DocumentConverter converter,
            VectorStore vectorStore,
            GitService gitService,
            JdbcTemplate jdbcTemplate,
            PackageSummaryGenerator packageSummaryGenerator,
            IndexingStatisticsCalculator statisticsCalculator,
            EmbeddingBudgetValidator budgetValidator,
            ChunkingConfig chunkingConfig,
            com.shravan.jcode_intelligence.service.RepositoryManagementService repositoryManagementService,
            RepositoryConcurrencyManager concurrencyManager,
            @Autowired(required = false) EmbeddingQueue embeddingQueue,
            @Autowired(required = false) ObjectProvider<EmbeddingModel> embeddingModelProvider,
            @Autowired(required = false) RepositoryClassifier classifier,
            @Autowired(required = false) IndexingPipelineSelector selector,
            @Nullable StreamingIndexingService streamingIndexingService,
            @Autowired(required = false) LuceneGenerationStore luceneGenerationStore
    ) {
        this.parser = parser;
        this.converter = converter;
        this.vectorStore = vectorStore;
        this.gitService = gitService;
        this.jdbcTemplate = jdbcTemplate;
        this.packageSummaryGenerator = packageSummaryGenerator;
        this.statisticsCalculator = statisticsCalculator;
        this.budgetValidator = budgetValidator;
        this.chunkingConfig = chunkingConfig;
        this.repositoryManagementService = repositoryManagementService;
        this.concurrencyManager = concurrencyManager != null ? concurrencyManager : new RepositoryConcurrencyManager();
        this.embeddingQueue = embeddingQueue;
        this.embeddingModelProvider = embeddingModelProvider;
        this.classifier = classifier;
        this.selector = selector;
        this.streamingIndexingService = streamingIndexingService;
        this.luceneGenerationStore = luceneGenerationStore;
    }

    /**
     * 16-argument constructor delegating with null sparse store.
     */
    public IndexingServiceImpl(
            JavaProjectParser parser,
            DocumentConverter converter,
            VectorStore vectorStore,
            GitService gitService,
            JdbcTemplate jdbcTemplate,
            PackageSummaryGenerator packageSummaryGenerator,
            IndexingStatisticsCalculator statisticsCalculator,
            EmbeddingBudgetValidator budgetValidator,
            ChunkingConfig chunkingConfig,
            com.shravan.jcode_intelligence.service.RepositoryManagementService repositoryManagementService,
            RepositoryConcurrencyManager concurrencyManager,
            EmbeddingQueue embeddingQueue,
            ObjectProvider<EmbeddingModel> embeddingModelProvider,
            RepositoryClassifier classifier,
            IndexingPipelineSelector selector,
            StreamingIndexingService streamingIndexingService
    ) {
        this(parser, converter, vectorStore, gitService, jdbcTemplate,
                packageSummaryGenerator, statisticsCalculator, budgetValidator,
                chunkingConfig, repositoryManagementService, concurrencyManager,
                embeddingQueue, embeddingModelProvider, classifier, selector,
                streamingIndexingService, null);
    }

    /**
     * 15-argument constructor delegating with null streaming service.
     */
    public IndexingServiceImpl(
            JavaProjectParser parser,
            DocumentConverter converter,
            VectorStore vectorStore,
            GitService gitService,
            JdbcTemplate jdbcTemplate,
            PackageSummaryGenerator packageSummaryGenerator,
            IndexingStatisticsCalculator statisticsCalculator,
            EmbeddingBudgetValidator budgetValidator,
            ChunkingConfig chunkingConfig,
            com.shravan.jcode_intelligence.service.RepositoryManagementService repositoryManagementService,
            RepositoryConcurrencyManager concurrencyManager,
            EmbeddingQueue embeddingQueue,
            ObjectProvider<EmbeddingModel> embeddingModelProvider,
            RepositoryClassifier classifier,
            IndexingPipelineSelector selector
    ) {
        this(parser, converter, vectorStore, gitService, jdbcTemplate,
                packageSummaryGenerator, statisticsCalculator, budgetValidator,
                chunkingConfig, repositoryManagementService, concurrencyManager,
                embeddingQueue, embeddingModelProvider, classifier, selector, null);
    }

    /**
     * 13-argument constructor delegating with null classification and streaming dependencies.
     */
    public IndexingServiceImpl(
            JavaProjectParser parser,
            DocumentConverter converter,
            VectorStore vectorStore,
            GitService gitService,
            JdbcTemplate jdbcTemplate,
            PackageSummaryGenerator packageSummaryGenerator,
            IndexingStatisticsCalculator statisticsCalculator,
            EmbeddingBudgetValidator budgetValidator,
            ChunkingConfig chunkingConfig,
            com.shravan.jcode_intelligence.service.RepositoryManagementService repositoryManagementService,
            RepositoryConcurrencyManager concurrencyManager,
            EmbeddingQueue embeddingQueue,
            ObjectProvider<EmbeddingModel> embeddingModelProvider
    ) {
        this(parser, converter, vectorStore, gitService, jdbcTemplate,
                packageSummaryGenerator, statisticsCalculator, budgetValidator,
                chunkingConfig, repositoryManagementService, concurrencyManager,
                embeddingQueue, embeddingModelProvider, null, null, null);
    }

    /**
     * Legacy constructor preserved for backwards compatibility with existing test configurations.
     */
    public IndexingServiceImpl(
            JavaProjectParser parser,
            DocumentConverter converter,
            VectorStore vectorStore,
            GitService gitService,
            JdbcTemplate jdbcTemplate,
            PackageSummaryGenerator packageSummaryGenerator,
            IndexingStatisticsCalculator statisticsCalculator,
            EmbeddingBudgetValidator budgetValidator,
            ChunkingConfig chunkingConfig,
            com.shravan.jcode_intelligence.service.RepositoryManagementService repositoryManagementService
    ) {
        this(parser, converter, vectorStore, gitService, jdbcTemplate,
                packageSummaryGenerator, statisticsCalculator, budgetValidator,
                chunkingConfig, repositoryManagementService, new RepositoryConcurrencyManager(),
                null, null, null, null, null);
    }

    @Override
    public int indexProject(String projectPath) throws IOException {
        String defaultRepoId = Path.of(projectPath).getFileName() != null
                ? Path.of(projectPath).getFileName().toString()
                : "default";
        return indexProject(projectPath, defaultRepoId);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Acquires the exclusive repository indexing lock, classifies repository memory requirements,
     * routes to STREAMING or EXISTING pipeline, and guarantees release of the lock upon completion.
     */
    @Override
    public int indexProject(String projectPath, String repositoryId) throws IOException {
        long startTime = System.currentTimeMillis();
        String safeRepoId = LuceneGenerationStore.validateRepositoryId(repositoryId);

        if (concurrencyManager != null) {
            concurrencyManager.acquireOrThrow(safeRepoId);
        }

        try {
            Path rootPath = Path.of(projectPath);

            RepositoryClassificationResult classificationResult = null;
            PipelineSelection selection = null;
            if (classifier != null && selector != null) {
                try {
                    classificationResult = classifier.classify(rootPath);
                    selection = selector.select(classificationResult, safeRepoId);
                } catch (IOException | RuntimeException e) {
                    log.warn("Classification failed for repository '{}'; selecting existing pipeline because classification failed", safeRepoId, e);
                    selection = selector.selectFailure("classification failed");
                    log.info("[STEP 1] Parsing repository source files at path: {} (classification failed fallback)", rootPath);
                    return executeExistingPipeline(rootPath, safeRepoId, null, startTime);
                }
                logClassificationInfo(safeRepoId, classificationResult, selection);
            }

            // Route to STREAMING if selected and components are available
            if (selection != null && selection.selectedPipeline() == IndexingPipeline.STREAMING) {
                EmbeddingModel embeddingModel = (embeddingModelProvider != null) ? embeddingModelProvider.getIfAvailable() : null;
                if (streamingIndexingService != null && embeddingQueue != null && embeddingModel != null) {
                    log.info("[LARGE REPO - STREAMING] Dispatching repository '{}' to STREAMING pipeline with {} eligible file(s)",
                            safeRepoId, classificationResult.eligibleFileCount());
                    return streamingIndexingService.index(safeRepoId, classificationResult.eligibleFiles());
                } else {
                    log.warn("STREAMING pipeline selected for repository '{}', but streaming components are unavailable (service={}, queue={}, model={}); falling back to existing pipeline with discovered files",
                            safeRepoId, streamingIndexingService != null, embeddingQueue != null, embeddingModel != null);
                    return executeExistingPipeline(rootPath, safeRepoId, classificationResult.eligibleFiles(), startTime);
                }
            }

            List<Path> filesToParse = (classificationResult != null) ? classificationResult.eligibleFiles() : null;
            log.info("[NORMAL REPO - ONE PASS] Dispatching repository '{}' to whole-repository single-pass pipeline ({} eligible file(s))",
                    safeRepoId, (filesToParse != null ? filesToParse.size() : "all"));
            return executeExistingPipeline(rootPath, safeRepoId, filesToParse, startTime);
        } finally {
            if (concurrencyManager != null) {
                concurrencyManager.release(safeRepoId);
            }
        }
    }

    /**
     * Executes the whole-repository single-pass pipeline (NORMAL repositories or LARGE fallback).
     *
     * <p>Batches dispatched to {@link EmbeddingQueue} are strictly capped by {@code embeddingQueue.getCapacity()}
     * to avoid queue overfill. On failure, worker threads are stopped via {@link #shutdownAndAwaitTermination}
     * <em>before</em> {@code embeddingQueue.cleanupJob} is called to ensure no in-flight retry producers
     * submit records to a cleaned-up job.
     */
    private int executeExistingPipeline(Path rootPath, String safeRepoId, List<Path> eligibleFiles, long startTime) throws IOException {
        // [STEP 1] Parse repository source files into element chunks
        List<CodeChunk> elementChunks;
        if (eligibleFiles != null) {
            log.info("[STEP 1] Parsing {} eligible source file(s) for repository '{}' using EXISTING pipeline",
                    eligibleFiles.size(), safeRepoId);
            elementChunks = parser.parse(eligibleFiles);
        } else {
            log.info("[STEP 1] Parsing repository source files at path: {}", rootPath);
            elementChunks = parser.parse(rootPath);
        }
        log.info("[STEP 1 COMPLETE] Generated {} element chunk(s)", elementChunks.size());

        // [STEP 2] Generate package summaries across the entire repository
        log.info("[STEP 2] Generating repository-wide package summaries...");
        List<CodeChunk> packageChunks = packageSummaryGenerator.generatePackageSummaries(elementChunks);
        log.info("[STEP 2 COMPLETE] Generated {} package summary chunk(s)", packageChunks.size());

        // Combine element chunks and repo-wide package summary chunks
        List<CodeChunk> allChunks = new ArrayList<>(elementChunks);
        allChunks.addAll(packageChunks);

        for (CodeChunk chunk : allChunks) {
            chunk.setRepositoryId(safeRepoId);
        }

        // [STEP 3] Convert to Spring AI Document objects
        log.info("[STEP 3] Converting {} CodeChunks to Spring AI Documents...", allChunks.size());
        List<Document> documents = converter.convert(allChunks);
        log.info("[STEP 3 COMPLETE] Converted {} Document(s)", documents.size());

        // [STEP 4] Pre-embedding budget validation and observability audit
        log.info("[STEP 4] Validating document sizes against embedding budget...");
        budgetValidator.validateAndAudit(documents, allChunks);
        log.info("[STEP 4 COMPLETE] All {} document(s) passed pre-embedding budget validation", documents.size());

        // Stage validated converted documents into sparse index before dense deletion
        LuceneGenerationStore.StagingSession sparseSession = null;
        if (luceneGenerationStore != null) {
            try {
                log.info("[SPARSE STAGING] Staging {} validated document(s) for repository '{}'...", documents.size(), safeRepoId);
                sparseSession = luceneGenerationStore.stageGeneration(safeRepoId);
                sparseSession.writeDocuments(documents);
                log.info("[SPARSE STAGING COMPLETE] Successfully staged {} document(s) in generation '{}'",
                        documents.size(), sparseSession.getGenerationName());
            } catch (Exception e) {
                log.error("[SPARSE STAGING ERROR] Failed to stage sparse generation for repository '{}': {}",
                        safeRepoId, e.getMessage(), e);
                if (sparseSession != null) {
                    luceneGenerationStore.discardGeneration(sparseSession);
                    sparseSession = null;
                }
                throw new RuntimeException("Failed to stage sparse index generation for repository " + safeRepoId, e);
            }
        }

        try {
            // [STEP 5] Delete existing vectors for this repository
            log.info("[STEP 5] Cleaning up existing vector records for repository: {}", safeRepoId);
            deleteExistingRepositoryVectors(safeRepoId);

            // [STEP 6] Embedding generation & vector storage (Queue-based staging or parallel batch fallback)
            int totalDocs = documents.size();
            int configuredBatchSize = Math.max(1, chunkingConfig.getBatchSize());
            int queueCapacity = (embeddingQueue != null) ? embeddingQueue.getCapacity() : Integer.MAX_VALUE;
            int batchSize = Math.max(1, Math.min(configuredBatchSize, queueCapacity));
            int totalBatches = (int) Math.ceil((double) totalDocs / batchSize);

            int configuredMaxWorkers = Math.max(1, chunkingConfig.getMaxParallelWorkers());
            int workerCount = Math.max(1, Math.min(totalBatches, configuredMaxWorkers));

            long embeddingStartTime = System.currentTimeMillis();
            AtomicInteger completedDocs = new AtomicInteger(0);

            EmbeddingModel embeddingModel = (embeddingModelProvider != null) ? embeddingModelProvider.getIfAvailable() : null;

            if (embeddingQueue != null && embeddingModel != null) {
                String jobId = "job-" + safeRepoId + "-" + System.currentTimeMillis();
                embeddingQueue.registerJob(jobId, safeRepoId, workerCount, batchSize, totalDocs);
                log.info(
                        "[STEP 6] Starting adaptive parallel batch embedding & queue staging for {} document(s) in {} batch(es) (batch size: {}, workers: {}, jobId: {})...",
                        totalDocs, totalBatches, batchSize, workerCount, jobId);

                Semaphore semaphore = new Semaphore(workerCount);
                AtomicReference<Throwable> firstFailureRef = new AtomicReference<>();
                ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                BatchEmbeddingHelper embeddingHelper = new BatchEmbeddingHelper(embeddingQueue, embeddingModel);
                try {
                    List<CompletableFuture<Void>> futures = new ArrayList<>(totalBatches);
                    for (int i = 0; i < totalDocs; i += batchSize) {
                        if (firstFailureRef.get() != null) {
                            break;
                        }

                        int startIdx = i;
                        int endIdx = Math.min(i + batchSize, totalDocs);
                        List<Document> batch = documents.subList(startIdx, endIdx);
                        int batchNum = (startIdx / batchSize) + 1;

                        try {
                            semaphore.acquire();
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException("Interrupted while awaiting embedding worker permit for batch " + batchNum, ie);
                        }

                        if (firstFailureRef.get() != null) {
                            semaphore.release();
                            break;
                        }

                        try {
                            futures.add(CompletableFuture.runAsync(() -> {
                                try {
                                    log.info("[BATCH {}/{}] Requesting embeddings & staging to queue for documents {}-{} of {}...",
                                            batchNum, totalBatches, startIdx + 1, endIdx, totalDocs);

                                    long batchStart = System.currentTimeMillis();

                                    embeddingHelper.embedAndStage(safeRepoId, jobId, batch);

                                    long batchDuration = System.currentTimeMillis() - batchStart;
                                    int doneDocs = completedDocs.addAndGet(batch.size());
                                    double pct = ((double) doneDocs / totalDocs) * 100.0;
                                    log.info("[BATCH {}/{} COMPLETE] Embedded & staged {} vector(s) to queue in {} ms | Progress: {}/{} ({})",
                                            batchNum, totalBatches, batch.size(), batchDuration, doneDocs, totalDocs,
                                            String.format("%.1f%%", pct));
                                } catch (Throwable t) {
                                    firstFailureRef.compareAndSet(null, t);
                                    throw t;
                                } finally {
                                    semaphore.release();
                                }
                            }, executor));
                        } catch (Throwable submitEx) {
                            semaphore.release();
                            firstFailureRef.compareAndSet(null, submitEx);
                            throw submitEx;
                        }
                    }

                    awaitAllOrCancelOnFailure(futures);

                    embeddingQueue.completeJob(jobId);
                    embeddingQueue.awaitJobCompletion(jobId, Duration.ofMinutes(15));
                } catch (Throwable t) {
                    embeddingQueue.failJob(jobId, t);
                    shutdownAndAwaitTermination(executor);
                    embeddingQueue.cleanupJob(jobId);
                    throw new RuntimeException("Embedding generation and queue staging failed during STEP 6", t);
                } finally {
                    shutdownAndAwaitTermination(executor);
                }
            } else {
                log.info(
                        "[STEP 6] Starting adaptive parallel batch vector store insertion for {} document(s) in {} batch(es) (batch size: {}, workers: {})...",
                        totalDocs, totalBatches, batchSize, workerCount);

                ExecutorService executor = Executors.newFixedThreadPool(workerCount);
                try {
                    List<CompletableFuture<Void>> futures = new ArrayList<>(totalBatches);

                    for (int i = 0; i < totalDocs; i += batchSize) {
                        int startIdx = i;
                        int endIdx = Math.min(i + batchSize, totalDocs);
                        List<Document> batch = documents.subList(startIdx, endIdx);
                        int batchNum = (startIdx / batchSize) + 1;

                        futures.add(CompletableFuture.runAsync(() -> {
                            log.info("[BATCH {}/{}] Requesting embeddings & vector storage for documents {}-{} of {}...",
                                    batchNum, totalBatches, startIdx + 1, endIdx, totalDocs);

                            long batchStart = System.currentTimeMillis();
                            vectorStore.add(batch);
                            long batchDuration = System.currentTimeMillis() - batchStart;

                            int doneDocs = completedDocs.addAndGet(batch.size());
                            double pct = ((double) doneDocs / totalDocs) * 100.0;
                            log.info("[BATCH {}/{} COMPLETE] Embedded & stored {} vector(s) in {} ms | Progress: {}/{} ({})",
                                    batchNum, totalBatches, batch.size(), batchDuration, doneDocs, totalDocs,
                                    String.format("%.1f%%", pct));
                        }, executor));
                    }

                    awaitAllOrCancelOnFailure(futures);
                } finally {
                    shutdownAndAwaitTermination(executor);
                }
            }

            long embeddingDuration = System.currentTimeMillis() - embeddingStartTime;
            log.info(
                    "[STEP 6 COMPLETE] Successfully embedded & indexed {} document(s) across {} batch(es) in {} ms using {} worker(s)",
                    totalDocs, totalBatches, embeddingDuration, workerCount);

            // Publish sparse generation after queue persistence or all fallback writes and before statistics
            if (sparseSession != null && luceneGenerationStore != null) {
                try {
                    luceneGenerationStore.publishGeneration(sparseSession);
                    log.info("[SPARSE PUBLISH COMPLETE] Published sparse generation '{}' for repository '{}'",
                            sparseSession.getGenerationName(), safeRepoId);

                    int sparseCount = luceneGenerationStore.getDocumentCount(safeRepoId);
                    if (sparseCount != documents.size()) {
                        log.warn("[SPARSE COUNT MISMATCH] Published sparse index document count ({}) does not match input document count ({}) for repository '{}'",
                                sparseCount, documents.size(), safeRepoId);
                    }
                    sparseSession = null;
                } catch (Exception e) {
                    log.error("[INDEX INCONSISTENCY] Failed to publish sparse generation for repository '{}' after dense data was indexed; stores diverged: {}",
                            safeRepoId, e.getMessage(), e);
                    luceneGenerationStore.discardGeneration(sparseSession);
                    sparseSession = null;
                    // Remove newly written dense rows so stores do not diverge with new dense live against old sparse
                    try {
                        deleteExistingRepositoryVectors(safeRepoId);
                    } catch (Exception delEx) {
                        log.error("Failed to clean up dense vectors after sparse publication failure for repository '{}': {}",
                                safeRepoId, delEx.getMessage());
                    }
                    if (repositoryManagementService != null) {
                        try {
                            repositoryManagementService.markRepositoryInconsistent(safeRepoId,
                                    "Sparse publish failed after dense storage: " + e.getMessage());
                        } catch (Exception markEx) {
                            log.error("Failed to mark repository '{}' inconsistent: {}", safeRepoId, markEx.getMessage());
                        }
                    }
                    throw new RuntimeException("Failed to publish sparse generation for repository " + safeRepoId, e);
                }
            }
        } catch (Throwable t) {
            log.error("[INDEX INCONSISTENCY] Indexing run failed for repository '{}' after sparse staging; stores may diverge: {}",
                    safeRepoId, t.getMessage());
            if (sparseSession != null && luceneGenerationStore != null) {
                try {
                    luceneGenerationStore.discardGeneration(sparseSession);
                } catch (Exception discardEx) {
                    log.debug("Error discarding sparse session on failure for {}: {}", safeRepoId, discardEx.getMessage());
                }
                sparseSession = null;
            }
            // Remove partially written dense rows on failure
            try {
                deleteExistingRepositoryVectors(safeRepoId);
            } catch (Exception cleanupEx) {
                log.error("Failed to clean up dense vectors on indexing failure for '{}': {}", safeRepoId, cleanupEx.getMessage());
            }
            if (repositoryManagementService != null) {
                try {
                    repositoryManagementService.markRepositoryInconsistent(safeRepoId,
                            "Indexing failed after sparse staging: " + t.getMessage());
                } catch (Exception statusEx) {
                    log.error("Failed to persist inconsistent repository status for '{}': {}", safeRepoId, statusEx.getMessage());
                }
            }
            throw t;
        }

        long duration = System.currentTimeMillis() - startTime;

        // [STEP 7] Compute and log structured indexing statistics
        IndexingStatistics stats = statisticsCalculator.calculate(allChunks, duration);
        log.info("\n{}", stats);

        if (repositoryManagementService != null) {
            repositoryManagementService.saveRepositoryStats(safeRepoId, stats);
        }

        return documents.size();
    }

    private void deleteExistingRepositoryVectors(String repositoryId) {
        log.info("Deleting existing vectors for repositoryId: {} via SQL", repositoryId);
        String sql = "DELETE FROM vector_store WHERE metadata->>'repositoryId' = ?";
        try {
            int rowsDeleted = jdbcTemplate.update(sql, repositoryId);
            log.info("Deleted {} existing vector record(s) for repositoryId: {}", rowsDeleted, repositoryId);
        } catch (Exception e) {
            log.error("Failed to delete existing vectors for repositoryId {}: {}", repositoryId, e.getMessage(), e);
            throw new RuntimeException("Failed to delete existing vectors for repository " + repositoryId, e);
        }
    }

    @Override
    public int indexGitRepository(String gitUrl) throws IOException {
        String repoId = extractRepoName(gitUrl);
        return indexGitRepository(gitUrl, repoId, null);
    }

    @Override
    public int indexGitRepository(String gitUrl, String repositoryId) throws IOException {
        return indexGitRepository(gitUrl, repositoryId, null);
    }

    @Override
    public int indexGitRepository(String gitUrl, String repositoryId, String branch) throws IOException {
        Path tempDir = null;
        try {
            log.info("Cloning Git repository: {}, branch: {}", gitUrl, (branch != null && !branch.isBlank() ? branch.trim() : "DEFAULT"));
            tempDir = gitService.cloneRepository(gitUrl, branch);
            return indexProject(tempDir.toString(), repositoryId);
        } finally {
            if (tempDir != null) {
                log.info("Cleaning up temporary git directory: {}", tempDir);
                gitService.cleanupRepository(tempDir);
            }
        }
    }

    private String extractRepoName(String gitUrl) {
        if (gitUrl == null) {
            return "git-repo";
        }
        String name = gitUrl.substring(gitUrl.lastIndexOf('/') + 1);
        if (name.endsWith(".git")) {
            name = name.substring(0, name.length() - 4);
        }
        return name.isEmpty() ? "git-repo" : name;
    }

    private void awaitAllOrCancelOnFailure(List<CompletableFuture<Void>> futures) {
        CompletableFuture<Void> allFutures = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
        CompletableFuture<Void> firstFailure = new CompletableFuture<>();
        for (CompletableFuture<Void> f : futures) {
            f.whenComplete((res, ex) -> {
                if (ex != null) {
                    firstFailure.completeExceptionally(ex);
                }
            });
        }
        allFutures.whenComplete((res, ex) -> {
            if (ex == null) {
                firstFailure.complete(null);
            }
        });

        try {
            firstFailure.join();
        } catch (CompletionException ce) {
            for (CompletableFuture<Void> f : futures) {
                f.cancel(true);
            }
            Throwable cause = ce.getCause() != null ? ce.getCause() : ce;
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(cause);
        }
    }

    private void shutdownAndAwaitTermination(ExecutorService executor) {
        if (executor == null) {
            return;
        }
        executor.shutdown();
        boolean interrupted = false;
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.warn("Executor workers did not terminate within bounded 10s timeout");
                }
            }
        } catch (InterruptedException e) {
            interrupted = true;
            executor.shutdownNow();
            try {
                executor.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                // bounded wait complete
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void logClassificationInfo(String repositoryId, RepositoryClassificationResult result, PipelineSelection selection) {
        long maxHeap = result.maxHeapBytes();
        long budget = result.budgetBytes();
        long need = result.estimatedNeedBytes();

        String maxHeapMb = (maxHeap == Long.MAX_VALUE) ? "unlimited" : String.format(Locale.ROOT, "%.1f MB", maxHeap / (1024.0 * 1024.0));
        String budgetMb = (budget == Long.MAX_VALUE) ? "unlimited" : String.format(Locale.ROOT, "%.1f MB", budget / (1024.0 * 1024.0));
        String needMb = (need == Long.MAX_VALUE) ? "overflow" : String.format(Locale.ROOT, "%.1f MB", need / (1024.0 * 1024.0));

        log.info("Repository classification | repo='{}' | LOC={} | eligibleFiles={} | maxHeap={} bytes ({}) | budget={} bytes ({}) | estimatedNeed={} bytes ({}) | classification={} | pipeline={} | overrideUsed={}",
                repositoryId,
                result.totalLoc(),
                result.eligibleFileCount(),
                maxHeap, maxHeapMb,
                budget, budgetMb,
                need, needMb,
                result.classification(),
                selection.selectedPipeline(),
                result.overrideUsed());
    }
}