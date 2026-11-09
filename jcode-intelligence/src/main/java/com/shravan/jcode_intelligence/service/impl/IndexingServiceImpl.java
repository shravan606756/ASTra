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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.MetadataMode;
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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
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

    /**
     * Constructs an {@code IndexingServiceImpl} with queue-based persistence and repository concurrency isolation.
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
            ObjectProvider<EmbeddingModel> embeddingModelProvider) {

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
                chunkingConfig, repositoryManagementService, new RepositoryConcurrencyManager(), null, null);
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
     * <p>For a nonblank repository ID, attempts to delete existing vectors before
     * indexing and saves the resulting statistics. Deletion errors are logged and do not
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
                embeddingQueue.registerJob(jobId, safeRepoId);
                log.info(
                        "[STEP 6] Starting adaptive parallel batch embedding & queue staging for {} document(s) in {} batch(es) (batch size: {}, workers: {}, jobId: {})...",
                        totalDocs, totalBatches, batchSize, workerCount, jobId);

                ExecutorService executor = Executors.newFixedThreadPool(workerCount);
                Semaphore preSubmissionPermits = new Semaphore(workerCount * 2);
                List<FutureTask<Void>> taskHandles = Collections.synchronizedList(new ArrayList<>(totalBatches));
                AtomicReference<Throwable> firstFailure = new AtomicReference<>();
                try {
                    for (int i = 0; i < totalDocs; i += batchSize) {
                        if (firstFailure.get() != null) {
                            break;
                        }

                        // Throttle submission via pre-submission permits; wake up immediately if a prior task fails
                        while (!preSubmissionPermits.tryAcquire(50, TimeUnit.MILLISECONDS)) {
                            if (firstFailure.get() != null) {
                                break;
                            }
                        }
                        if (firstFailure.get() != null) {
                            break;
                        }

                        int startIdx = i;
                        int endIdx = Math.min(i + batchSize, totalDocs);
                        List<Document> batch = documents.subList(startIdx, endIdx);
                        int batchNum = (startIdx / batchSize) + 1;

                        AtomicBoolean permitReleased = new AtomicBoolean(false);
                        Runnable releasePermitOnce = () -> {
                            if (permitReleased.compareAndSet(false, true)) {
                                preSubmissionPermits.release();
                            }
                        };

                        FutureTask<Void> task = new FutureTask<>(() -> {
                            try {
                                if (Thread.currentThread().isInterrupted() || firstFailure.get() != null) {
                                    return null;
                                }
                                log.info("[BATCH {}/{}] Requesting embeddings & staging to queue for documents {}-{} of {}...",
                                        batchNum, totalBatches, startIdx + 1, endIdx, totalDocs);

                                long batchStart = System.currentTimeMillis();

                                // Group chunks in this batch to request embeddings in a single model call
                                List<String> texts = batch.stream()
                                        .map(doc -> doc.getFormattedContent(MetadataMode.EMBED))
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
                                embeddingQueue.submitAll(records);
                                long batchDuration = System.currentTimeMillis() - batchStart;
                                int doneDocs = completedDocs.addAndGet(batch.size());
                                double pct = ((double) doneDocs / totalDocs) * 100.0;
                                log.info("[BATCH {}/{} COMPLETE] Embedded & staged {} vector(s) to queue in {} ms | Progress: {}/{} ({})",
                                        batchNum, totalBatches, batch.size(), batchDuration, doneDocs, totalDocs,
                                        String.format("%.1f%%", pct));
                                return null;
                            } catch (Throwable t) {
                                if (firstFailure.compareAndSet(null, t)) {
                                    log.error("[BATCH {}/{}] Failed with error: {}", batchNum, totalBatches, t.getMessage(), t);
                                    embeddingQueue.closeAdmission();
                                    embeddingQueue.failJob(jobId, t);
                                    synchronized (taskHandles) {
                                        for (FutureTask<Void> handle : taskHandles) {
                                            handle.cancel(true);
                                        }
                                    }
                                }
                                throw (t instanceof Exception e) ? e : new RuntimeException(t);
                            }
                        }) {
                            @Override
                            protected void done() {
                                releasePermitOnce.run();
                            }
                        };

                        try {
                            executor.execute(task);
                            taskHandles.add(task);
                        } catch (RejectedExecutionException ree) {
                            releasePermitOnce.run();
                            if (firstFailure.compareAndSet(null, ree)) {
                                embeddingQueue.closeAdmission();
                                embeddingQueue.failJob(jobId, ree);
                                synchronized (taskHandles) {
                                    for (FutureTask<Void> handle : taskHandles) {
                                        handle.cancel(true);
                                    }
                                }
                            }
                            throw ree;
                        }
                    }

                    awaitTasksOrCancelOnFailure(taskHandles, firstFailure);

                    embeddingQueue.completeJob(jobId);
                    embeddingQueue.awaitJobCompletion(jobId, Duration.ofMinutes(15));
                } catch (Throwable t) {
                    synchronized (taskHandles) {
                        for (FutureTask<Void> handle : taskHandles) {
                            handle.cancel(true);
                        }
                    }
                    shutdownAndAwaitTermination(executor);
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
                List<FutureTask<Void>> taskHandles = Collections.synchronizedList(new ArrayList<>(totalBatches));
                AtomicReference<Throwable> firstFailure = new AtomicReference<>();
                try {
                    for (int i = 0; i < totalDocs; i += batchSize) {
                        if (firstFailure.get() != null) {
                            break;
                        }
                        int startIdx = i;
                        int endIdx = Math.min(i + batchSize, totalDocs);
                        List<Document> batch = documents.subList(startIdx, endIdx);
                        int batchNum = (startIdx / batchSize) + 1;

                        FutureTask<Void> task = new FutureTask<>(() -> {
                            try {
                                if (Thread.currentThread().isInterrupted() || firstFailure.get() != null) {
                                    return null;
                                }
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
                                return null;
                            } catch (Throwable t) {
                                if (firstFailure.compareAndSet(null, t)) {
                                    synchronized (taskHandles) {
                                        for (FutureTask<Void> handle : taskHandles) {
                                            handle.cancel(true);
                                        }
                                    }
                                }
                                throw (t instanceof Exception e) ? e : new RuntimeException(t);
                            }
                        });

                        executor.execute(task);
                        taskHandles.add(task);
                    }

                    awaitTasksOrCancelOnFailure(taskHandles, firstFailure);
                } catch (Throwable t) {
                    synchronized (taskHandles) {
                        for (FutureTask<Void> handle : taskHandles) {
                            handle.cancel(true);
                        }
                    }
                    shutdownAndAwaitTermination(executor);
                    throw new RuntimeException("Batch vector store insertion failed during STEP 6", t);
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

    /**
     * Awaits completion of all scheduled task handles, cancelling remaining tasks immediately
     * upon detecting an exceptional completion.
     *
     * @param taskHandles  list of cancellable task handles
     * @param firstFailure shared reference tracking the first encountered failure
     */
    private void awaitTasksOrCancelOnFailure(List<FutureTask<Void>> taskHandles, AtomicReference<Throwable> firstFailure) {
        for (FutureTask<Void> task : taskHandles) {
            if (firstFailure.get() != null) {
                break;
            }
            try {
                task.get();
            } catch (CancellationException e) {
                // Task was cancelled, continue
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                firstFailure.compareAndSet(null, cause);
                break;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                firstFailure.compareAndSet(null, e);
                break;
            }
        }

        Throwable failure = firstFailure.get();
        if (failure != null) {
            synchronized (taskHandles) {
                for (FutureTask<Void> task : taskHandles) {
                    task.cancel(true);
                }
            }
            if (failure instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(failure);
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
}