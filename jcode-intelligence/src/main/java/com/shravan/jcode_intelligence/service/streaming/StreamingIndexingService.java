package com.shravan.jcode_intelligence.service.streaming;

import com.shravan.jcode_intelligence.config.ChunkingConfig;
import com.shravan.jcode_intelligence.config.LargeRepoIndexingConfig;
import com.shravan.jcode_intelligence.config.MemoryClassificationConfig;
import com.shravan.jcode_intelligence.converter.DocumentConverter;
import com.shravan.jcode_intelligence.model.CodeChunk;
import com.shravan.jcode_intelligence.model.IndexingStatistics;
import com.shravan.jcode_intelligence.parser.EmbeddingBudgetValidator;
import com.shravan.jcode_intelligence.parser.IndexingStatisticsAccumulator;
import com.shravan.jcode_intelligence.parser.JavaProjectParser;
import com.shravan.jcode_intelligence.parser.PackageSummaryGenerator;
import com.shravan.jcode_intelligence.service.BatchEmbeddingHelper;
import com.shravan.jcode_intelligence.service.EmbeddingQueue;
import com.shravan.jcode_intelligence.service.RepositoryManagementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Spring service implementing the LARGE repository streaming indexing pipeline
 * (Issue #31).
 *
 * <p>
 * Processes repositories classified as LARGE by reading pre-discovered eligible
 * files
 * in lexical order, parsing files individually, and buffering chunks until a
 * configured
 * threshold is reached. Threshold-sized chunk slices are drained, validated,
 * converted,
 * partitioned into sub-batches bounded by queue capacity, and submitted to the
 * shared
 * {@link EmbeddingQueue} via virtual-thread embedding workers.
 *
 * <p>
 * <strong>Write Fencing &amp; Persistence Semantics:</strong>
 * <ul>
 * <li><strong>No Vector Deletions:</strong> Unlike the whole-repository
 * pipeline, the streaming
 * pipeline does NOT perform a pre-indexing vector wipe (e.g.
 * {@code DELETE FROM vector_store}).
 * Existing records with matching deterministic chunk IDs are updated via
 * {@code ON CONFLICT (id) DO UPDATE}.</li>
 * <li><strong>Stale Rows:</strong> If a class, method, or file is deleted or
 * renamed in the repository,
 * its previous vector embeddings remain in PostgreSQL unless pruned
 * out-of-band.</li>
 * <li><strong>No Write Fencing on Failure:</strong> If a pipeline failure
 * occurs after batches have
 * been staged to the {@link EmbeddingQueue}, in-flight persistence writer
 * transactions may
 * commit writes to PostgreSQL even after the job has been marked failed and the
 * indexing call
 * has returned an error, because the queue and database layer currently lack
 * fencing tokens.</li>
 * </ul>
 *
 * <p>
 * <strong>Architecture Guidelines for Future Agents &amp; Contributors:</strong>
 * <ul>
 * <li><strong>Non-Destructive Upserting:</strong> Issue #31 strictly mandates that streaming pipelines
 * NEVER delete existing vector records. All writes are upserted via PostgreSQL {@code ON CONFLICT (id) DO UPDATE}
 * using deterministic IDs. Do NOT introduce pre-indexing deletions or post-run sweeps in this pipeline without
 * an explicit architectural review.</li>
 * <li><strong>Dynamic Wave Pass Sizing:</strong> Chunk accumulation thresholds are calculated dynamically
 * via {@link #computeDynamicPassBatchSize(int)}. On machines with large unallocated heaps, waves scale up
 * to process tens of thousands of chunks per pass; on constrained environments (or tests with low bases),
 * the configured {@code batchSize} floor is strictly preserved.</li>
 * <li><strong>Real-Time Memory Relief Valve:</strong> {@link #shouldFlushOnMemoryPressure} evaluates
 * unallocated heap after each parsed file. If free heap dips below {@code safetyHeadroomFraction} (default: 25%),
 * any non-empty chunk buffer is flushed early to relieve heap pressure before an OOM can occur.</li>
 * <li><strong>Bounded Worker Dispatching:</strong> Sub-batches passed to virtual thread workers are strictly capped
 * by {@code Math.min(chunkingConfig.getBatchSize(), embeddingQueue.getCapacity())} to avoid queue overflow.</li>
 * <li><strong>Permit Reclaim &amp; Persistence Deadlines:</strong> Worker permits must be reclaimed with a 15-minute
 * timeout (preventing indefinite hangs), followed by {@code embeddingQueue.awaitJobCompletion(15 min)}.</li>
 * <li><strong>Clean Producer Shutdown:</strong> On failure, the virtual-thread worker executor must be terminated
 * via {@link #shutdownAndAwaitTermination} <em>before</em> {@code embeddingQueue.cleanupJob} is called,
 * preventing in-flight or backoff retry threads from reviving state on a cleaned-up job.</li>
 * </ul>
 */
@Service
public class StreamingIndexingService {

    private static final Logger log = LoggerFactory.getLogger(StreamingIndexingService.class);

    private final JavaProjectParser parser;
    private final DocumentConverter converter;
    private final EmbeddingBudgetValidator budgetValidator;
    private final PackageSummaryGenerator packageSummaryGenerator;
    private final LargeRepoIndexingConfig largeRepoConfig;
    private final ChunkingConfig chunkingConfig;
    private final EmbeddingQueue embeddingQueue;
    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;
    private final RepositoryManagementService repositoryManagementService;
    private final BatchEmbeddingHelper batchEmbeddingHelper;
    private final MemoryClassificationConfig memoryClassificationConfig;

    @Autowired
    public StreamingIndexingService(
            JavaProjectParser parser,
            DocumentConverter converter,
            EmbeddingBudgetValidator budgetValidator,
            PackageSummaryGenerator packageSummaryGenerator,
            LargeRepoIndexingConfig largeRepoConfig,
            ChunkingConfig chunkingConfig,
            @Autowired(required = false) EmbeddingQueue embeddingQueue,
            @Autowired(required = false) ObjectProvider<EmbeddingModel> embeddingModelProvider,
            RepositoryManagementService repositoryManagementService,
            @Autowired(required = false) BatchEmbeddingHelper batchEmbeddingHelper,
            @Autowired(required = false) MemoryClassificationConfig memoryClassificationConfig) {
        this.parser = Objects.requireNonNull(parser, "parser must not be null");
        this.converter = Objects.requireNonNull(converter, "converter must not be null");
        this.budgetValidator = Objects.requireNonNull(budgetValidator, "budgetValidator must not be null");
        this.packageSummaryGenerator = Objects.requireNonNull(packageSummaryGenerator,
                "packageSummaryGenerator must not be null");
        this.largeRepoConfig = largeRepoConfig != null ? largeRepoConfig : new LargeRepoIndexingConfig();
        this.chunkingConfig = chunkingConfig != null ? chunkingConfig : new ChunkingConfig();
        this.embeddingQueue = embeddingQueue;
        this.embeddingModelProvider = embeddingModelProvider;
        this.repositoryManagementService = repositoryManagementService;
        this.batchEmbeddingHelper = batchEmbeddingHelper;
        this.memoryClassificationConfig = memoryClassificationConfig;
    }

    public StreamingIndexingService(
            JavaProjectParser parser,
            DocumentConverter converter,
            EmbeddingBudgetValidator budgetValidator,
            PackageSummaryGenerator packageSummaryGenerator,
            LargeRepoIndexingConfig largeRepoConfig,
            ChunkingConfig chunkingConfig,
            EmbeddingQueue embeddingQueue,
            ObjectProvider<EmbeddingModel> embeddingModelProvider,
            RepositoryManagementService repositoryManagementService,
            BatchEmbeddingHelper batchEmbeddingHelper) {
        this(parser, converter, budgetValidator, packageSummaryGenerator,
                largeRepoConfig, chunkingConfig, embeddingQueue, embeddingModelProvider,
                repositoryManagementService, batchEmbeddingHelper, null);
    }

    /**
     * Indexes a large repository by streaming AST parsing across eligible files.
     *
     * @param safeRepoId    repository identifier
     * @param eligibleFiles ordered list of eligible Java source files
     * @return total count of submitted documents
     * @throws IOException if source parsing or streaming fails
     */
    public int index(String safeRepoId, List<Path> eligibleFiles) throws IOException {
        long startTime = System.currentTimeMillis();
        String repoId = (safeRepoId != null && !safeRepoId.isBlank()) ? safeRepoId : "default";

        EmbeddingModel embeddingModel = (embeddingModelProvider != null) ? embeddingModelProvider.getIfAvailable()
                : null;
        if (embeddingQueue == null || embeddingModel == null) {
            throw new IllegalStateException(
                    "EmbeddingQueue and EmbeddingModel must be available for STREAMING pipeline");
        }

        BatchEmbeddingHelper helper = (this.batchEmbeddingHelper != null)
                ? this.batchEmbeddingHelper
                : new BatchEmbeddingHelper(embeddingQueue, embeddingModel);

        int configuredWorkers = Math.max(1, chunkingConfig.getMaxParallelWorkers());
        int queueCapacity = embeddingQueue.getCapacity();
        int maxSubBatchSize = Math.max(1, Math.min(chunkingConfig.getBatchSize(), queueCapacity));
        int threshold = computeDynamicPassBatchSize();

        String jobId = "job-streaming-" + repoId + "-" + System.currentTimeMillis();

        // Register job with worker and sub-batch hints and an unbounded total hint
        embeddingQueue.registerJob(jobId, repoId, configuredWorkers, maxSubBatchSize, Integer.MAX_VALUE);
        log.info(
                "[LARGE REPO - STREAMING STEP 1] Registered streaming job '{}' for repository '{}' (workers: {}, subBatchSize: {}, pass threshold: {})",
                jobId, repoId, configuredWorkers, maxSubBatchSize, threshold);

        Semaphore semaphore = new Semaphore(configuredWorkers);
        AtomicReference<Throwable> firstFailureRef = new AtomicReference<>();
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        Set<CompletableFuture<Void>> inFlightFutures = ConcurrentHashMap.newKeySet();
        AtomicInteger totalSubmittedDocs = new AtomicInteger(0);
        AtomicInteger totalBatchesDispatched = new AtomicInteger(0);

        PackageSummaryGenerator.Accumulator packageAccumulator = packageSummaryGenerator.createAccumulator();
        IndexingStatisticsAccumulator statsAccumulator = new IndexingStatisticsAccumulator();
        List<CodeChunk> chunkBuffer = new ArrayList<>();
        int totalFiles = (eligibleFiles != null) ? eligibleFiles.size() : 0;
        int processedFiles = 0;
        int passCounter = 0;

        try {
            if (eligibleFiles != null) {
                for (Path file : eligibleFiles) {
                    checkFailure(firstFailureRef);

                    List<CodeChunk> fileChunks = parser.parse(List.of(file));
                    processedFiles++;
                    if (fileChunks != null && !fileChunks.isEmpty()) {
                        packageAccumulator.accumulate(fileChunks);
                        statsAccumulator.accumulate(fileChunks);
                        chunkBuffer.addAll(fileChunks);
                    }

                    // Drain threshold-sized slices while buffer reaches dynamic threshold or memory pressure occurs
                    while (chunkBuffer.size() >= threshold || shouldFlushOnMemoryPressure(chunkBuffer, largeRepoConfig.getBatchSize())) {
                        checkFailure(firstFailureRef);
                        passCounter++;
                        int sliceSize = Math.min(chunkBuffer.size(), threshold);
                        List<CodeChunk> slice = new ArrayList<>(chunkBuffer.subList(0, sliceSize));
                        chunkBuffer.subList(0, sliceSize).clear();
                        double percent = totalFiles > 0 ? (processedFiles * 100.0 / totalFiles) : 100.0;
                        log.info(
                                "[STREAMING PASS {}] Accumulated {} chunk(s) (files parsed: {}/{} [{}], buffer remaining: {}). Flushing pass {} to workers...",
                                passCounter, slice.size(), processedFiles, totalFiles, String.format("%.1f%%", percent), chunkBuffer.size(),
                                passCounter);
                        flushChunkSlice(slice, repoId, jobId, maxSubBatchSize, semaphore, executor,
                                inFlightFutures, firstFailureRef, helper, totalSubmittedDocs, totalBatchesDispatched,
                                passCounter);
                    }
                }
            }

            // Append package summaries after all files have been parsed
            checkFailure(firstFailureRef);
            List<CodeChunk> packageSummaries = packageAccumulator.generateSummaries();
            if (packageSummaries != null && !packageSummaries.isEmpty()) {
                statsAccumulator.accumulate(packageSummaries);
                chunkBuffer.addAll(packageSummaries);
            }

            // Flush final partial batch
            while (!chunkBuffer.isEmpty()) {
                checkFailure(firstFailureRef);
                passCounter++;
                int sliceSize = Math.min(chunkBuffer.size(), threshold);
                List<CodeChunk> slice = new ArrayList<>(chunkBuffer.subList(0, sliceSize));
                chunkBuffer.subList(0, sliceSize).clear();
                log.info(
                        "[STREAMING FINAL PASS {}] Completed all {} files. Package summaries: {}. Flushing final {} chunk(s) (buffer remaining: {})...",
                        passCounter, totalFiles, (packageSummaries != null ? packageSummaries.size() : 0), slice.size(),
                        chunkBuffer.size());
                flushChunkSlice(slice, repoId, jobId, maxSubBatchSize, semaphore, executor,
                        inFlightFutures, firstFailureRef, helper, totalSubmittedDocs, totalBatchesDispatched,
                        passCounter);
            }

            // Reclaim all permits to ensure all in-flight worker tasks have finished execution
            if (!semaphore.tryAcquire(configuredWorkers, 15, TimeUnit.MINUTES)) {
                throw new TimeoutException("Timed out after 15 minutes waiting for worker permits to be released");
            }
            semaphore.release(configuredWorkers);

            // Await completion of any remaining in-flight future handles
            CompletableFuture.allOf(inFlightFutures.toArray(new CompletableFuture[0])).join();

            checkFailure(firstFailureRef);

            // Complete job and await persistence
            embeddingQueue.completeJob(jobId);
            embeddingQueue.awaitJobCompletion(jobId, Duration.ofMinutes(15));

            long duration = System.currentTimeMillis() - startTime;
            IndexingStatistics stats = statsAccumulator.build(duration);
            log.info("\n{}", stats);

            if (repositoryManagementService != null) {
                repositoryManagementService.saveRepositoryStats(repoId, stats);
            }

            log.info(
                    "[LARGE REPO - STREAMING SUCCESS] Successfully indexed repository '{}' via STREAMING pipeline: {} document(s) in {} batch(es) (duration: {} ms)",
                    repoId, totalSubmittedDocs.get(), totalBatchesDispatched.get(), duration);

            return totalSubmittedDocs.get();
        } catch (Throwable t) {
            firstFailureRef.compareAndSet(null, t);
            embeddingQueue.failJob(jobId, firstFailureRef.get());

            for (CompletableFuture<Void> f : inFlightFutures) {
                f.cancel(true);
            }

            // Wait for producers before cleanup
            shutdownAndAwaitTermination(executor);

            embeddingQueue.cleanupJob(jobId);

            log.error(
                    "[STREAMING FAILURE] Large repository streaming failed for '{}' after {} batch(es) and {} document(s): {}",
                    repoId, totalBatchesDispatched.get(), totalSubmittedDocs.get(), t.getMessage());

            if (t instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            if (t instanceof RuntimeException re) {
                throw re;
            }
            if (t instanceof IOException ioe) {
                throw ioe;
            }
            throw new RuntimeException("Large repository streaming pipeline failed", t);
        } finally {
            shutdownAndAwaitTermination(executor);
        }
    }

    /**
     * Drains, converts, audits, partitions, and stages a chunk slice to {@link EmbeddingQueue}.
     *
     * <p>Sub-batches are bounded by {@code maxSubBatchSize} (capped by queue capacity) and dispatched
     * onto virtual threads using a concurrency semaphore.
     *
     * @param slice                  the list of CodeChunks in this wave (cleared on completion)
     * @param repoId                 repository identifier
     * @param jobId                  job identifier
     * @param maxSubBatchSize        maximum allowed documents per sub-batch (capped by queue capacity)
     * @param semaphore              worker concurrency limiter
     * @param executor               virtual thread task executor
     * @param inFlightFutures        tracker for outstanding worker futures
     * @param firstFailureRef        atomic holder for the first producer failure
     * @param helper                 batch embedding helper with progressive retry
     * @param totalSubmittedDocs     atomic counter tracking submitted documents
     * @param totalBatchesDispatched atomic counter tracking dispatched sub-batches
     * @param passNumber             1-based streaming pass index
     */
    private void flushChunkSlice(
            List<CodeChunk> slice,
            String repoId,
            String jobId,
            int maxSubBatchSize,
            Semaphore semaphore,
            ExecutorService executor,
            Set<CompletableFuture<Void>> inFlightFutures,
            AtomicReference<Throwable> firstFailureRef,
            BatchEmbeddingHelper helper,
            AtomicInteger totalSubmittedDocs,
            AtomicInteger totalBatchesDispatched,
            int passNumber
    ) {
        if (slice == null || slice.isEmpty()) {
            return;
        }

        // Assign repository ID
        for (CodeChunk chunk : slice) {
            chunk.setRepositoryId(repoId);
        }

        // Convert and validate each flush
        List<Document> docs = converter.convert(slice);
        budgetValidator.validateAndAudit(docs, slice);

        int docCount = docs.size();
        int subBatchesInPass = (docCount + maxSubBatchSize - 1) / maxSubBatchSize;
        log.info("[STREAMING PASS {} - DISPATCH] Converted {} chunks into {} documents. Dispatching {} sub-batches (capped at {}) across virtual thread workers...",
                passNumber, slice.size(), docCount, subBatchesInPass, maxSubBatchSize);

        // Split documents into copied sub-batches capped by queue capacity
        for (int i = 0; i < docCount; i += maxSubBatchSize) {
            checkFailure(firstFailureRef);

            int end = Math.min(i + maxSubBatchSize, docCount);
            List<Document> copiedSubBatch = new ArrayList<>(docs.subList(i, end));

            try {
                semaphore.acquire();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while acquiring embedding worker permit", ie);
            }

            if (firstFailureRef.get() != null) {
                semaphore.release();
                checkFailure(firstFailureRef);
            }

            CompletableFuture<Void> taskFuture = new CompletableFuture<>();
            inFlightFutures.add(taskFuture);
            try {
                CompletableFuture.runAsync(() -> {
                    try {
                        helper.embedAndStage(repoId, jobId, copiedSubBatch);
                        totalSubmittedDocs.addAndGet(copiedSubBatch.size());
                        totalBatchesDispatched.incrementAndGet();
                        taskFuture.complete(null);
                    } catch (Throwable t) {
                        firstFailureRef.compareAndSet(null, t);
                        taskFuture.completeExceptionally(t);
                        throw t;
                    } finally {
                        inFlightFutures.remove(taskFuture);
                        semaphore.release();
                    }
                }, executor);
            } catch (Throwable submitEx) {
                semaphore.release();
                inFlightFutures.remove(taskFuture);
                firstFailureRef.compareAndSet(null, submitEx);
                throw submitEx;
            }
        }

        // Release flushed references
        slice.clear();
    }

    private void checkFailure(AtomicReference<Throwable> firstFailureRef) {
        Throwable t = firstFailureRef.get();
        if (t != null) {
            if (t instanceof RuntimeException re) {
                throw re;
            }
            if (t instanceof Error err) {
                throw err;
            }
            throw new RuntimeException("Streaming pipeline aborted due to earlier task failure", t);
        }
    }

    /**
     * Computes the dynamic streaming batch size based on real-time available JVM heap memory.
     *
     * <p>If {@code largeRepoConfig.getBatchSize()} is configured below 500 (such as in unit test environments),
     * that explicit value is respected directly. In production workloads, the available heap
     * (maxMemory - usedMemory) is factored with the configured {@code budgetFraction} to dynamically
     * size the streaming accumulation buffer to utilize available hardware efficiently.
     *
     * @return dynamic threshold in code chunks
     */
    public int computeDynamicPassBatchSize() {
        int configuredBase = Math.max(1, largeRepoConfig.getBatchSize());
        if (configuredBase < 500) {
            return configuredBase;
        }

        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        long realTimeAvailableMemory = Math.max(0, maxMemory - usedMemory);

        double budgetFraction = (memoryClassificationConfig != null)
                ? memoryClassificationConfig.getBudgetFraction()
                : 0.4;

        long dynamicBudgetBytes = (long) (realTimeAvailableMemory * budgetFraction);
        long bytesPerChunk = 20_480L; // Calibrated ~20 KB per buffered in-memory CodeChunk
        long calculatedChunks = dynamicBudgetBytes / bytesPerChunk;

        int dynamicThreshold = (int) Math.max(configuredBase, Math.min(Integer.MAX_VALUE, calculatedChunks));

        double availMb = realTimeAvailableMemory / (1024.0 * 1024.0);
        double budgetMb = dynamicBudgetBytes / (1024.0 * 1024.0);
        log.info("[STREAMING DYNAMIC HEAP SIZING] Real-time available heap: {} MB (budget: {}%, {} MB). Pass threshold dynamically sized to {} chunks (base floor: {})",
                String.format("%.1f", availMb), String.format("%.0f", budgetFraction * 100.0), String.format("%.1f", budgetMb), dynamicThreshold, configuredBase);

        return dynamicThreshold;
    }

    /**
     * Evaluates real-time JVM heap headroom against the safety relief valve threshold.
     *
     * <p>If real-time unallocated heap (free memory + unallocated heap up to max) drops below
     * {@code safetyHeadroomFraction} of max heap, accumulation is immediately aborted and any
     * non-empty buffer is flushed early to relieve memory pressure before an OutOfMemoryError occurs.
     *
     * @param chunkBuffer    the in-memory accumulated chunk buffer
     * @param configuredBase the configured base batch size
     * @return true if memory pressure requires an immediate emergency flush, false otherwise
     */
    private boolean shouldFlushOnMemoryPressure(List<CodeChunk> chunkBuffer, int configuredBase) {
        if (chunkBuffer == null || chunkBuffer.isEmpty()) {
            return false;
        }
        Runtime runtime = Runtime.getRuntime();
        long max = runtime.maxMemory();
        long free = max - (runtime.totalMemory() - runtime.freeMemory());
        double headroom = (largeRepoConfig != null) ? largeRepoConfig.getSafetyHeadroomFraction() : 0.25;
        // If real-time available heap drops below safety headroom fraction of max heap, flush early to prevent OOM
        if (free < (long) (max * headroom)) {
            log.warn("[STREAMING MEMORY PRESSURE FLUSH] Available heap dropped to {} MB (< {}% of max). Flushing {} buffered chunks early to relieve memory pressure.",
                    free / (1024 * 1024), String.format("%.0f", headroom * 100.0), chunkBuffer.size());
            return true;
        }
        return false;
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
                    log.warn("Streaming executor workers did not terminate within bounded 10s timeout");
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
