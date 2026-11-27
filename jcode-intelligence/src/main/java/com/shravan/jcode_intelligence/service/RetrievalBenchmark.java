package com.shravan.jcode_intelligence.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.shravan.jcode_intelligence.config.RetrievalConfig;
import com.shravan.jcode_intelligence.dto.request.BenchmarkRequest;
import com.shravan.jcode_intelligence.dto.request.ChatMode;
import com.shravan.jcode_intelligence.dto.response.BenchmarkGenerationSummaryDTO;
import com.shravan.jcode_intelligence.dto.response.BenchmarkResponse;
import com.shravan.jcode_intelligence.dto.response.BenchmarkSummaryDTO;
import com.shravan.jcode_intelligence.dto.response.QueryBenchmarkResultDTO;
import com.shravan.jcode_intelligence.dto.response.QueryBenchmarkResultDTO.QueryOperationalMetricsDTO;
import com.shravan.jcode_intelligence.dto.response.QueryBenchmarkResultDTO.QueryQualityMetricsDTO;
import com.shravan.jcode_intelligence.dto.response.QueryBenchmarkResultDTO.RetrievedChunkDTO;
import com.shravan.jcode_intelligence.exception.BenchmarkConflictException;
import com.shravan.jcode_intelligence.model.QueryIntent;
import com.shravan.jcode_intelligence.model.benchmark.BenchmarkQueryDefinition;
import com.shravan.jcode_intelligence.model.benchmark.BenchmarkSuite;
import com.shravan.jcode_intelligence.model.RetrievalStrategy;
import com.shravan.jcode_intelligence.service.evaluation.generation.GenerationBenchmarkRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Deterministic retrieval evaluation service.
 * <p>
 * Executes curated benchmark query suites loaded from external datasets against
 * indexed repositories,
 * computes standard IR quality metrics (Hit Rate, Recall, Precision, MRR, NDCG,
 * Context Precision/Recall),
 * isolates per-query failures, tracks operational performance, and persists
 * execution history.
 */
@Component
public class RetrievalBenchmark {

    private static final Logger log = LoggerFactory.getLogger(RetrievalBenchmark.class);

    private final RetrievalService retrievalService;
    private final QueryIntentClassifier intentClassifier;
    private final BenchmarkDatasetProvider datasetProvider;
    private final RetrievalEvaluator evaluator;
    private final RepositoryManagementService repositoryManagementService;
    private final ObjectMapper objectMapper;
    private final String historyDir;
    private final RetrievalConfig retrievalConfig;
    private final GenerationBenchmarkRunner generationBenchmarkRunner;
    private boolean evaluationEnabled;

    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    @Autowired
    public RetrievalBenchmark(RetrievalService retrievalService,
            QueryIntentClassifier intentClassifier,
            BenchmarkDatasetProvider datasetProvider,
            RetrievalEvaluator evaluator,
            @Autowired(required = false) RepositoryManagementService repositoryManagementService,
            @Autowired(required = false) ObjectMapper objectMapper,
            @Value("${astra.benchmark.history-dir:target/benchmark-runs/}") String historyDir,
            @Autowired(required = false) RetrievalConfig retrievalConfig,
            @Autowired(required = false) GenerationBenchmarkRunner generationBenchmarkRunner,
            @Value("${astra.evaluation.enabled:false}") boolean evaluationEnabled) {
        this.retrievalService = retrievalService;
        this.intentClassifier = intentClassifier;
        this.datasetProvider = (datasetProvider != null) ? datasetProvider : new BenchmarkDatasetProvider(objectMapper);
        this.evaluator = (evaluator != null) ? evaluator : new RetrievalEvaluator();
        this.repositoryManagementService = repositoryManagementService;
        this.objectMapper = (objectMapper != null) ? objectMapper.copy().enable(SerializationFeature.INDENT_OUTPUT)
                : new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        this.historyDir = historyDir;
        this.retrievalConfig = retrievalConfig;
        this.generationBenchmarkRunner = generationBenchmarkRunner;
        this.evaluationEnabled = evaluationEnabled;
    }

    public RetrievalBenchmark(RetrievalService retrievalService,
            QueryIntentClassifier intentClassifier,
            BenchmarkDatasetProvider datasetProvider,
            RetrievalEvaluator evaluator,
            RepositoryManagementService repositoryManagementService,
            ObjectMapper objectMapper,
            String historyDir,
            RetrievalConfig retrievalConfig,
            GenerationBenchmarkRunner generationBenchmarkRunner) {
        this(retrievalService, intentClassifier, datasetProvider, evaluator, repositoryManagementService, objectMapper,
                historyDir, retrievalConfig, generationBenchmarkRunner, false);
    }

    public RetrievalBenchmark(RetrievalService retrievalService,
            QueryIntentClassifier intentClassifier,
            BenchmarkDatasetProvider datasetProvider,
            RetrievalEvaluator evaluator,
            RepositoryManagementService repositoryManagementService,
            ObjectMapper objectMapper,
            String historyDir,
            RetrievalConfig retrievalConfig) {
        this(retrievalService, intentClassifier, datasetProvider, evaluator, repositoryManagementService, objectMapper,
                historyDir, retrievalConfig, null);
    }

    public RetrievalBenchmark(RetrievalService retrievalService,
            QueryIntentClassifier intentClassifier,
            BenchmarkDatasetProvider datasetProvider,
            RetrievalEvaluator evaluator,
            GenerationBenchmarkRunner generationBenchmarkRunner) {
        this(retrievalService, intentClassifier, datasetProvider, evaluator, null, null, "target/benchmark-runs/",
                null, generationBenchmarkRunner, true);
    }

    public RetrievalBenchmark(RetrievalService retrievalService,
            QueryIntentClassifier intentClassifier,
            BenchmarkDatasetProvider datasetProvider,
            RetrievalEvaluator evaluator,
            GenerationBenchmarkRunner generationBenchmarkRunner,
            boolean evaluationEnabled) {
        this(retrievalService, intentClassifier, datasetProvider, evaluator, null, null, "target/benchmark-runs/",
                null, generationBenchmarkRunner, evaluationEnabled);
    }

    public boolean isEvaluationEnabled() {
        return evaluationEnabled;
    }

    public void setEvaluationEnabled(boolean evaluationEnabled) {
        this.evaluationEnabled = evaluationEnabled;
    }

    public RetrievalBenchmark(RetrievalService retrievalService,
            QueryIntentClassifier intentClassifier,
            BenchmarkDatasetProvider datasetProvider,
            RetrievalEvaluator evaluator,
            RepositoryManagementService repositoryManagementService,
            ObjectMapper objectMapper,
            String historyDir) {
        this(retrievalService, intentClassifier, datasetProvider, evaluator, repositoryManagementService, objectMapper,
                historyDir, null);
    }

    public RetrievalBenchmark(RetrievalService retrievalService,
            QueryIntentClassifier intentClassifier,
            BenchmarkDatasetProvider datasetProvider,
            RetrievalEvaluator evaluator) {
        this(retrievalService, intentClassifier, datasetProvider, evaluator, null, null, "target/benchmark-runs/",
                null);
    }

    public RetrievalBenchmark(RetrievalService retrievalService,
            QueryIntentClassifier intentClassifier,
            BenchmarkDatasetProvider datasetProvider,
            RetrievalEvaluator evaluator,
            RepositoryManagementService repositoryManagementService) {
        this(retrievalService, intentClassifier, datasetProvider, evaluator, repositoryManagementService, null,
                "target/benchmark-runs/", null);
    }

    public RetrievalBenchmark(RetrievalService retrievalService,
            QueryIntentClassifier intentClassifier) {
        this(retrievalService, intentClassifier, null, null, null, null, "target/benchmark-runs/", null);
    }

    /**
     * Executes the requested suite or custom questions. Query exceptions are
     * recorded as
     * failed results so later queries can run. The response is also written to the
     * history
     * directory when possible; persistence failures do not prevent it from being
     * returned.
     *
     * @param request the benchmark configuration request
     * @return {@link BenchmarkResponse} containing summary and per-query metrics
     * @throws BenchmarkConflictException                  if another benchmark is
     *                                                     executing on this
     *                                                     instance
     * @throws IllegalArgumentException                    if the request or
     *                                                     repository is invalid,
     *                                                     the repository
     *                                                     is ambiguous or not
     *                                                     indexed, the suite is
     *                                                     unavailable, or custom
     *                                                     questions are blank
     * @throws org.springframework.dao.DataAccessException if repository lookup
     *                                                     encounters a data access
     *                                                     error
     */
    public BenchmarkResponse runBenchmark(BenchmarkRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("BenchmarkRequest must not be null");
        }
        if (request.isAsync()) {
            throw new IllegalArgumentException("Asynchronous benchmark execution is not yet supported");
        }
        ResolvedConfiguration resolved = resolveConfiguration(request.getRetrievalConfiguration());
        ChatMode chatMode = resolved.chatMode();
        RetrievalStrategy strategyOverride = resolved.strategyOverride();
        String requestedRepo = request.getResolvedRepositoryName();
        String resolvedRepo = resolveAndValidateRepository(requestedRepo);

        // Concurrency Guard: reject overlapping benchmark executions
        if (!isRunning.compareAndSet(false, true)) {
            log.warn("Benchmark execution rejected: a benchmark suite is already currently executing.");
            throw new BenchmarkConflictException(
                    "A benchmark suite is already currently executing. Please wait for it to complete.");
        }

        long startTime = System.currentTimeMillis();
        try {
            boolean isCustom = request.isCustomQuestionMode();
            List<BenchmarkQueryDefinition> queries;
            String suiteName;
            BenchmarkSuite suite = null;

            if (isCustom) {
                suiteName = (request.getSuiteName() != null && !request.getSuiteName().isBlank()
                        && !"default".equalsIgnoreCase(request.getSuiteName()))
                                ? request.getSuiteName()
                                : "custom";
                queries = buildCustomQueries(request, resolvedRepo);
            } else {
                suite = datasetProvider.getSuite(request.getSuiteName());
                suiteName = suite.getSuiteName();
                queries = suite.getQueries();
            }

            int effectiveTopK = (request.getTopK() > 0) ? request.getTopK() : 5;
            boolean evaluateGen = evaluationEnabled && request.isEvaluateGeneration() && (generationBenchmarkRunner != null);
            log.info("Starting retrieval benchmark (mode={}, suite='{}', evaluateGen={}, evaluationEnabled={}) with {} queries against repo '{}' at topK={}",
                    isCustom ? "CUSTOM_QUESTIONS" : "DATASET", suiteName, evaluateGen, evaluationEnabled, queries.size(), resolvedRepo, effectiveTopK);

            List<QueryBenchmarkResultDTO> results = new ArrayList<>();
            List<GenerationBenchmarkRunner.RetainedBenchmarkQuery> retainedQueries = new ArrayList<>();
            int timeoutCount = 0;
            String rerankingStrategy = request.getRerankingStrategy();

            for (BenchmarkQueryDefinition bq : queries) {
                SingleQueryEvaluation eval = evaluateSingleQuery(bq, resolvedRepo, effectiveTopK, chatMode,
                        strategyOverride, rerankingStrategy);
                QueryBenchmarkResultDTO result = eval.result();
                if ("FAILED".equalsIgnoreCase(result.getStatus()) && isTimeout(result.getErrorMessage())) {
                    timeoutCount++;
                }
                results.add(result);

                if (evaluateGen && "SUCCESS".equalsIgnoreCase(result.getStatus())) {
                    retainedQueries.add(new GenerationBenchmarkRunner.RetainedBenchmarkQuery(
                            bq, result, eval.topKDocuments(), eval.intent(), resolvedRepo
                    ));
                }
            }

            long totalDurationMs = System.currentTimeMillis() - startTime;
            BenchmarkSummaryDTO summary = evaluator.aggregateSummary(results, totalDurationMs, timeoutCount);

            if (evaluateGen && !retainedQueries.isEmpty()) {
                String suiteSourceRevision = (!isCustom && suite != null) ? suite.getSourceRevision() : null;
                BenchmarkGenerationSummaryDTO genSummary = generationBenchmarkRunner.runGenerationEvaluation(
                        retainedQueries, request, suiteSourceRevision);
                summary.setGenerationSummary(genSummary);
            }
            if (rerankingStrategy != null && !rerankingStrategy.isBlank()) {
                summary.setRerankingStrategy(rerankingStrategy.trim());
            }

            BenchmarkResponse response = new BenchmarkResponse(
                    resolvedRepo,
                    suiteName,
                    request.getRetrievalConfiguration(),
                    effectiveTopK,
                    summary,
                    results);
            response.setRepositoryName(resolvedRepo);
            response.setRepositoryId(resolvedRepo);
            response.setMode(isCustom ? "CUSTOM_QUESTIONS" : "DATASET");
            response.setFailedQueryCount(summary.getFailedQueries());
            if (rerankingStrategy != null && !rerankingStrategy.isBlank()) {
                response.setRerankingStrategy(rerankingStrategy.trim());
            }

            RetrievalStrategy effectiveStrategy = strategyOverride != null
                    ? strategyOverride
                    : (retrievalConfig != null ? retrievalConfig.getStrategy() : RetrievalStrategy.DENSE);
            response.setEffectiveStrategy(effectiveStrategy.name());

            if (summary.isHasGroundTruth()) {
                log.info(
                        "BENCHMARK COMPLETED | repo='{}' | mode='{}' | suite='{}' | queries={} | success={} | failed={} | HR@5={}% | MRR={} | NDCG@5={} | avgLatency={}ms",
                        resolvedRepo, response.getMode(), suiteName, results.size(),
                        summary.getSuccessfulQueries(), summary.getFailedQueries(),
                        summary.getHitRateAt5() != null ? Math.round(summary.getHitRateAt5() * 100.0) : 0,
                        summary.getMrr(), summary.getNdcgAt5(), summary.getAvgTotalLatencyMs());
            } else {
                log.info(
                        "BENCHMARK COMPLETED | repo='{}' | mode='{}' | suite='{}' | queries={} | success={} | failed={} | avgLatency={}ms | avgChunks={}",
                        resolvedRepo, response.getMode(), suiteName, results.size(),
                        summary.getSuccessfulQueries(), summary.getFailedQueries(),
                        summary.getAvgTotalLatencyMs(), summary.getAvgRetrievedChunks());
            }

            // Persist run history
            persistBenchmarkRun(response);

            return response;
        } finally {
            isRunning.set(false);
        }
    }

    private List<BenchmarkQueryDefinition> buildCustomQueries(BenchmarkRequest request, String repositoryName) {
        List<BenchmarkQueryDefinition> queries = new ArrayList<>();
        int counter = 1;

        if (request.getCustomQuestions() != null) {
            for (com.shravan.jcode_intelligence.dto.request.CustomBenchmarkQuestionDTO cq : request
                    .getCustomQuestions()) {
                if (cq == null || cq.getQuery() == null || cq.getQuery().isBlank()) {
                    continue;
                }
                String qid = (cq.getQueryId() != null && !cq.getQueryId().isBlank())
                        ? cq.getQueryId()
                        : "custom-" + counter++;
                queries.add(new BenchmarkQueryDefinition(
                        qid,
                        cq.getQuery().trim(),
                        (cq.getCategory() != null && !cq.getCategory().isBlank()) ? cq.getCategory() : "Custom",
                        repositoryName,
                        cq.getExpectedIntent(),
                        cq.getRelevantChunks() != null ? cq.getRelevantChunks() : Collections.emptyList(),
                        cq.getReferenceAnswer(),
                        Collections.emptyList()));
            }
        }

        if (request.getQuestions() != null) {
            for (String q : request.getQuestions()) {
                if (q == null || q.isBlank()) {
                    continue;
                }
                queries.add(new BenchmarkQueryDefinition(
                        "custom-" + counter++,
                        q.trim(),
                        "Custom",
                        repositoryName,
                        null,
                        Collections.emptyList(),
                        null,
                        Collections.emptyList()));
            }
        }

        if (queries.isEmpty()) {
            throw new IllegalArgumentException("At least one valid question is required for custom question benchmark");
        }

        return queries;
    }

    record SingleQueryEvaluation(
            QueryBenchmarkResultDTO result,
            List<Document> topKDocuments,
            QueryIntent intent
    ) {}

    /**
     * Evaluates a single benchmark query with full failure isolation.
     */
    private SingleQueryEvaluation evaluateSingleQuery(BenchmarkQueryDefinition bq,
            String repositoryId,
            int topK,
            ChatMode chatMode,
            RetrievalStrategy strategyOverride,
            String rerankingStrategy) {
        long queryStart = System.currentTimeMillis();
        List<RetrievedChunkDTO> retrievedChunks = new ArrayList<>();
        boolean hasGroundTruth = (bq.getRelevantChunks() != null && !bq.getRelevantChunks().isEmpty());

        try {
            // Step 1: Retrieve documents with configured topK and retrieval mode using retrieveWithDiagnostics
            int retrievalTopK = hasGroundTruth ? Math.max(topK, 25) : topK;
            ChatMode effectiveChatMode = (chatMode != null) ? chatMode : ChatMode.AUTO;

            RetrievalService.RetrievalDiagnostics diagnostics = retrievalService.retrieveWithDiagnostics(
                    bq.getQuery(), retrievalTopK, repositoryId, effectiveChatMode, strategyOverride, rerankingStrategy);

            List<Document> documents = diagnostics.documents();
            QueryIntent classified = (diagnostics.intent() != null) ? diagnostics.intent() : QueryIntent.UNKNOWN;
            Integer candidateCount = (diagnostics.rawDocuments() != null) ? diagnostics.rawDocuments().size() : null;
            long candidateRetrievalLatency = diagnostics.candidateRetrievalLatencyMs();
            long rerankLatency = diagnostics.rerankingLatencyMs();

            boolean intentMatch = false;
            if (bq.getExpectedIntent() != null) {
                intentMatch = bq.getExpectedIntent().equalsIgnoreCase(classified.name());
            }

            // Step 2: Compute deterministic IR quality metrics against ground truth if present
            QueryQualityMetricsDTO qualityMetrics = null;
            if (hasGroundTruth) {
                qualityMetrics = evaluator.evaluateQuery(
                        documents, bq.getRelevantChunks(), retrievedChunks, topK);
            } else {
                evaluator.populateRetrievedChunks(documents, retrievedChunks, topK);
            }

            long totalQueryLatency = System.currentTimeMillis() - queryStart;

            // Step 3: Record operational metrics
            // vectorSearchLatencyMs preserves candidate retrieval timing semantics
            // rerankingLatencyMs records measured reranking latency
            QueryOperationalMetricsDTO operationalMetrics = new QueryOperationalMetricsDTO(
                    totalQueryLatency,
                    0L, // embedding latency if isolated
                    candidateRetrievalLatency,
                    rerankLatency,
                    retrievedChunks.size(),
                    candidateCount);

            QueryBenchmarkResultDTO result = new QueryBenchmarkResultDTO(
                    bq.getQueryId(),
                    bq.getQuery(),
                    bq.getCategory(),
                    bq.getExpectedIntent(),
                    classified.name(),
                    intentMatch,
                    "SUCCESS",
                    null,
                    retrievedChunks,
                    qualityMetrics,
                    operationalMetrics);
            result.setCandidateCount(candidateCount);
            result.setHasGroundTruth(hasGroundTruth);
            if (hasGroundTruth) {
                result.setUnmatchedGroundTruths(evaluator.findUnmatchedGroundTruths(documents, bq.getRelevantChunks()));
            }

            int prefixLimit = (documents != null && topK > 0) ? Math.min(topK, documents.size()) : 0;
            List<Document> topKPrefix = (documents != null && prefixLimit > 0)
                    ? documents.subList(0, prefixLimit)
                    : List.of();
            return new SingleQueryEvaluation(result, topKPrefix, classified);
        } catch (Exception ex) {
            long totalQueryLatency = System.currentTimeMillis() - queryStart;
            log.warn("Benchmark query '{}' failed: {}", bq.getQueryId(), ex.getMessage());

            QueryOperationalMetricsDTO operationalMetrics = new QueryOperationalMetricsDTO(
                    totalQueryLatency, 0L, 0L, 0L, 0, null);

            QueryBenchmarkResultDTO result = new QueryBenchmarkResultDTO(
                    bq.getQueryId(),
                    bq.getQuery(),
                    bq.getCategory(),
                    bq.getExpectedIntent(),
                    "UNKNOWN",
                    false,
                    "FAILED",
                    ex.getMessage(),
                    retrievedChunks,
                    null,
                    operationalMetrics);
            result.setCandidateCount(null);
            result.setHasGroundTruth(hasGroundTruth);
            return new SingleQueryEvaluation(result, List.of(), QueryIntent.UNKNOWN);
        }
    }

    /**
     * Resolves the target repository by name, matching case-insensitively and
     * checking for ambiguity.
     */
    private String resolveAndValidateRepository(String requestedName) {
        if (requestedName == null || requestedName.isBlank()) {
            throw new IllegalArgumentException("repositoryName is required");
        }

        if (repositoryManagementService == null) {
            return requestedName.trim();
        }

        List<String> indexedRepos = repositoryManagementService.listRepositories();
        if (indexedRepos == null) {
            return requestedName.trim();
        }

        String target = requestedName.trim();

        // 1. Exact match
        if (indexedRepos.contains(target)) {
            return target;
        }

        // 2. Case-insensitive exact match
        List<String> caseMatches = indexedRepos.stream()
                .filter(r -> r.equalsIgnoreCase(target))
                .toList();
        if (caseMatches.size() == 1) {
            return caseMatches.get(0);
        } else if (caseMatches.size() > 1) {
            throw new IllegalArgumentException(
                    "Ambiguous repository name '" + requestedName + "'. Multiple matches found: " + caseMatches);
        }

        // 3. Case-insensitive contains/prefix match
        List<String> partialMatches = indexedRepos.stream()
                .filter(r -> r.toLowerCase().contains(target.toLowerCase()))
                .toList();
        if (partialMatches.size() == 1) {
            return partialMatches.get(0);
        } else if (partialMatches.size() > 1) {
            throw new IllegalArgumentException(
                    "Ambiguous repository name '" + requestedName + "'. Multiple matches found: " + partialMatches);
        }

        // 4. Not found
        throw new IllegalArgumentException(
                "Repository '" + requestedName + "' is not indexed. Available repositories: " + indexedRepos);
    }

    record ResolvedConfiguration(ChatMode chatMode, RetrievalStrategy strategyOverride) {
    }

    /**
     * Resolves the requested retrieval configuration into a supported ChatMode and
     * strategy override.
     */
    private ResolvedConfiguration resolveConfiguration(String config) {
        if (config == null || config.isBlank() || "AUTO".equalsIgnoreCase(config.trim())) {
            return new ResolvedConfiguration(ChatMode.AUTO, null);
        }
        String trimmed = config.trim().toUpperCase(Locale.ROOT);
        switch (trimmed) {
            case "DENSE_ONLY":
                return new ResolvedConfiguration(ChatMode.AUTO, RetrievalStrategy.DENSE);
            case "SPARSE_ONLY":
                return new ResolvedConfiguration(ChatMode.AUTO, RetrievalStrategy.SPARSE);
            case "HYBRID_RRF":
                return new ResolvedConfiguration(ChatMode.AUTO, RetrievalStrategy.HYBRID);
            default:
                try {
                    ChatMode mode = ChatMode.valueOf(trimmed);
                    return new ResolvedConfiguration(mode, null);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("Unsupported retrievalConfiguration: '" + config
                            + "'. Valid options: DENSE_ONLY, SPARSE_ONLY, HYBRID_RRF, AUTO, or a valid ChatMode name.");
                }
        }
    }

    private boolean isTimeout(String errorMessage) {
        if (errorMessage == null) {
            return false;
        }
        String msg = errorMessage.toLowerCase(Locale.ROOT);
        return msg.contains("timeout") || msg.contains("timed out");
    }

    /**
     * Persists the benchmark response as structured JSON for historical regression
     * tracking.
     */
    private void persistBenchmarkRun(BenchmarkResponse response) {
        try {
            Path targetPath = Paths.get(historyDir);
            Files.createDirectories(targetPath);

            String repoIdentifier = response.getRepositoryName() != null ? response.getRepositoryName()
                    : response.getRepositoryId();
            String safeRepo = (repoIdentifier != null ? repoIdentifier : "unknown").replaceAll("[^a-zA-Z0-9.-]", "_");
            String safeSuite = (response.getSuiteName() != null ? response.getSuiteName() : "benchmark")
                    .replaceAll("[^a-zA-Z0-9.-]", "_");
            String timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now()).replaceAll("[:.]", "-");

            String fileName = String.format("benchmark-%s-%s-%s.json", safeRepo, safeSuite, timestamp);
            File outputFile = targetPath.resolve(fileName).toFile();

            objectMapper.writeValue(outputFile, response);
            log.info("Persisted benchmark run history to: {}", outputFile.getAbsolutePath());
        } catch (Exception e) {
            log.warn("Failed to persist benchmark run history: {}", e.getMessage());
        }
    }

    // ── Legacy Compatibility Overload ─────────────────────────

    /**
     * Runs the default suite and adapts its results to the legacy format.
     *
     * @param repositoryId repository name; null selects {@code javaparser}
     * @return legacy results for the suite's queries
     * @throws BenchmarkConflictException                  if another benchmark is
     *                                                     executing on this
     *                                                     instance
     * @throws IllegalArgumentException                    if the repository is
     *                                                     invalid, ambiguous or not
     *                                                     indexed,
     *                                                     or the default suite is
     *                                                     unavailable
     * @throws org.springframework.dao.DataAccessException if repository lookup
     *                                                     encounters a data access
     *                                                     error
     */
    public List<BenchmarkResult> runBenchmark(String repositoryId) {
        BenchmarkRequest request = new BenchmarkRequest(repositoryId != null ? repositoryId : "javaparser");
        BenchmarkResponse response = runBenchmark(request);

        List<BenchmarkResult> legacyResults = new ArrayList<>();
        for (QueryBenchmarkResultDTO qr : response.getResults()) {
            Map<String, Integer> chunkTypeCounts = new LinkedHashMap<>();
            List<String> topElements = new ArrayList<>();

            for (RetrievedChunkDTO chunk : qr.getRetrievedChunks()) {
                String type = (chunk.getType() != null && !chunk.getType().isBlank()) ? chunk.getType() : "UNKNOWN";
                chunkTypeCounts.merge(type, 1, Integer::sum);
                if (chunk.getElementName() != null && !chunk.getElementName().isBlank() && topElements.size() < 10) {
                    topElements.add(chunk.getElementName());
                }
            }

            String topChunkType = chunkTypeCounts.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse("NONE");

            legacyResults.add(new BenchmarkResult(
                    qr.getQuery(),
                    qr.getExpectedIntent() != null ? qr.getExpectedIntent() : "UNKNOWN",
                    qr.getClassifiedIntent(),
                    qr.isIntentMatch(),
                    chunkTypeCounts,
                    topElements,
                    topChunkType,
                    qr.getRetrievedChunks().size(),
                    qr.getOperationalMetrics() != null ? qr.getOperationalMetrics().getTotalLatencyMs() : 0L));
        }
        return legacyResults;
    }

    // ── Legacy Data Classes ───────────────────────────────────

    public record BenchmarkResult(
            String query,
            String expectedIntent,
            String classifiedIntent,
            boolean intentMatch,
            Map<String, Integer> chunkTypeCounts,
            List<String> topElementNames,
            String topChunkType,
            int totalDocuments,
            long latencyMs) {
    }
}
