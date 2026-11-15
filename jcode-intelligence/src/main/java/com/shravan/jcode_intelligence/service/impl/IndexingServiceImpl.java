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
import com.shravan.jcode_intelligence.service.EmbeddingQueue;
import com.shravan.jcode_intelligence.service.GitService;
import com.shravan.jcode_intelligence.service.IndexingService;
import com.shravan.jcode_intelligence.service.RepositoryConcurrencyManager;
import com.shravan.jcode_intelligence.service.classification.IndexingPipeline;
import com.shravan.jcode_intelligence.service.classification.IndexingPipelineSelector;
import com.shravan.jcode_intelligence.service.classification.PipelineSelection;
import com.shravan.jcode_intelligence.service.classification.RepositoryClassificationResult;
import com.shravan.jcode_intelligence.service.classification.RepositoryClassifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
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
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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

    /**
     * Constructs an {@code IndexingServiceImpl} with memory-aware classification, pipeline selection,
     * queue-based persistence, and repository concurrency isolation.
     *
     * @param parser                      Java project AST parser
     * @param converter                   code chunk to document converter
     * @param vectorStore                 vector store for embedding storage
     * @param gitService                  Git repository service
     * @param jdbcTemplate                Spring JDBC template
     * @param packageSummaryGenerator     package summary generator
     * @param statisticsCalculator        indexing metrics calculator
     * @param budgetValidator             pre-embedding token budget validator
     * @param chunkingConfig              chunking and sizing configuration
     * @param repositoryManagementService repository metadata management service
     * @param concurrencyManager          repository isolation and concurrency manager
     * @param embeddingQueue              shared bounded embedding queue
     * @param embeddingModelProvider      provider for direct Spring AI embedding generation
     * @param classifier                  memory-aware repository classifier
     * @param selector                    pipeline selection component
     */
    @Autowired
    public IndexingServiceImpl(JavaProjectParser parser,
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
            IndexingPipelineSelector selector) {

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
    }

    /**
     * 13-argument constructor delegating with null classification dependencies.
     */
    public IndexingServiceImpl(JavaProjectParser parser,
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
            ObjectProvider<EmbeddingModel> embeddingModelProvider) {
        this(parser, converter, vectorStore, gitService, jdbcTemplate,
                packageSummaryGenerator, statisticsCalculator, budgetValidator,
                chunkingConfig, repositoryManagementService, concurrencyManager,
                embeddingQueue, embeddingModelProvider, null, null);
    }

    /**
     * Legacy constructor preserved for backwards compatibility with existing test configurations.
     */
    public IndexingServiceImpl(JavaProjectParser parser,
            DocumentConverter converter,
            VectorStore vectorStore,
            GitService gitService,
            JdbcTemplate jdbcTemplate,
            PackageSummaryGenerator packageSummaryGenerator,
            IndexingStatisticsCalculator statisticsCalculator,
            EmbeddingBudgetValidator budgetValidator,
            ChunkingConfig chunkingConfig,
            com.shravan.jcode_intelligence.service.RepositoryManagementService repositoryManagementService) {
        this(parser, converter, vectorStore, gitService, jdbcTemplate,
                packageSummaryGenerator, statisticsCalculator, budgetValidator,
                chunkingConfig, repositoryManagementService, new RepositoryConcurrencyManager(), null, null, null, null);
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
     * <p>Uses {@code default} for a null or blank repository ID, attempts to delete existing vectors
     * before indexing, and saves the resulting statistics. Deletion errors are logged and do not
     * stop indexing. When both a queue and an embedding model are available, waits up to
     * 15 minutes for queued records to finish; otherwise uses the vector store directly.
     *
     * @throws com.shravan.jcode_intelligence.exception.IndexingConflictException if the
     *         repository's indexing lock is already held, including by the current thread
     * @throws RuntimeException if batch embedding, queue staging or completion, or direct
     *         vector store insertion fails
     */
    @Override
    public int indexProject(String projectPath, String repositoryId) throws IOException {
        long startTime = System.currentTimeMillis();
        String safeRepoId = (repositoryId != null && !repositoryId.isBlank()) ? repositoryId : "default";

        if (concurrencyManager != null) {
            concurrencyManager.acquireOrThrow(safeRepoId);
        }

        try {
            // [STEP 1] Parse repository source files into element chunks (with memory classification & pipeline selection)
            Path rootPath = Path.of(projectPath);
            List<CodeChunk> elementChunks = parseRepository(rootPath, safeRepoId);
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

            // [STEP 5] Delete existing vectors for this repository
            log.info("[STEP 5] Cleaning up existing vector records for repository: {}", safeRepoId);
            deleteExistingRepositoryVectors(safeRepoId);

            // [STEP 6] Embedding generation & vector storage (Queue-based staging or parallel batch fallback)
            int totalDocs = documents.size();
            int batchSize = Math.max(1, chunkingConfig.getBatchSize());
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

                                    // Group chunks in this batch to request embeddings in a single model call
                                    List<String> texts = batch.stream()
                                            .map(Document::getText)
                                            .toList();
                                    List<float[]> embeddings = embeddingModel.embed(texts);

                                    List<EmbeddedRecord> records = new ArrayList<>(batch.size());
                                    for (int j = 0; j < batch.size(); j++) {
                                        Document doc = batch.get(j);
                                        String chunkId = (String) doc.getMetadata().get("chunkId");
                                        String recordId = EmbeddedRecord.generateDeterministicId(safeRepoId, chunkId, doc.getText());
                                        float[] embedding = (embeddings != null && j < embeddings.size()) ? embeddings.get(j) : new float[0];
                                        records.add(new EmbeddedRecord(
                                                recordId,
                                                jobId,
                                                safeRepoId,
                                                doc.getText(),
                                                doc.getMetadata(),
                                                embedding,
                                                new CompletableFuture<>()
                                        ));
                                    }
                                    try {
                                        embeddingQueue.submitAll(records);
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                        throw new RuntimeException("Interrupted while staging records to embedding queue", e);
                                    }
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

            long duration = System.currentTimeMillis() - startTime;

            // [STEP 7] Compute and log structured indexing statistics
            IndexingStatistics stats = statisticsCalculator.calculate(allChunks, duration);
            log.info("\n{}", stats);

            repositoryManagementService.saveRepositoryStats(safeRepoId, stats);

            return documents.size();
        } finally {
            if (concurrencyManager != null) {
                concurrencyManager.release(safeRepoId);
            }
        }
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

    /**
     * {@inheritDoc}
     */
    @Override
    public int indexGitRepository(String gitUrl) throws IOException {
        String repoId = extractRepoName(gitUrl);
        return indexGitRepository(gitUrl, repoId, null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int indexGitRepository(String gitUrl, String repositoryId) throws IOException {
        return indexGitRepository(gitUrl, repositoryId, null);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Shallow clones the repository using {@link GitService#cloneRepository(String, String)},
     * parses and vectors the project files, and guarantees removal of temporary files upon completion.
     */
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
        if (gitUrl == null)
            return "git-repo";
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

    /**
     * Parses repository sources with memory-aware classification and pipeline selection if configured,
     * or delegates to root parsing if classifier is absent or classification fails.
     *
     * @param rootPath   source root path
     * @param safeRepoId repository identifier
     * @return parsed code chunks
     * @throws IOException if source parsing fails
     */
    private List<CodeChunk> parseRepository(Path rootPath, String safeRepoId) throws IOException {
        if (classifier == null || selector == null) {
            log.info("[STEP 1] Parsing repository source files at path: {}", rootPath);
            return parser.parse(rootPath);
        }

        RepositoryClassificationResult classificationResult;
        PipelineSelection selection;
        try {
            classificationResult = classifier.classify(rootPath);
            selection = selector.select(classificationResult, safeRepoId);
        } catch (IOException | RuntimeException e) {
            log.warn("Classification failed for repository '{}'; selecting existing pipeline because classification failed", safeRepoId, e);
            selection = selector.selectFailure("classification failed");
            log.info("[STEP 1] Parsing repository source files at path: {} (classification failed fallback)", rootPath);
            return parser.parse(rootPath);
        }

        logClassificationInfo(safeRepoId, classificationResult, selection);

        if (selection.selectedPipeline() == IndexingPipeline.EXISTING) {
            log.info("[STEP 1] Parsing {} eligible source file(s) for repository '{}' using {} pipeline",
                    classificationResult.eligibleFileCount(), safeRepoId, selection.selectedPipeline());
            return parser.parse(classificationResult.eligibleFiles());
        } else {
            // Reserved for #31 (Large-Repository Streaming Indexing Pipeline)
            throw new UnsupportedOperationException("Streaming pipeline is not yet implemented (Issue #31)");
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