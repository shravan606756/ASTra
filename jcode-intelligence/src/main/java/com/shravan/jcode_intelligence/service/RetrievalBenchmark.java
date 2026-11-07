package com.shravan.jcode_intelligence.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.shravan.jcode_intelligence.dto.request.BenchmarkRequest;
import com.shravan.jcode_intelligence.dto.request.ChatMode;
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
 * Executes curated benchmark query suites loaded from external datasets against indexed repositories,
 * computes standard IR quality metrics (Hit Rate, Recall, Precision, MRR, NDCG, Context Precision/Recall),
 * isolates per-query failures, tracks operational performance, and persists execution history.
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

    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    @Autowired
    public RetrievalBenchmark(RetrievalService retrievalService,
                              QueryIntentClassifier intentClassifier,
                              BenchmarkDatasetProvider datasetProvider,
                              RetrievalEvaluator evaluator,
                              @Autowired(required = false) RepositoryManagementService repositoryManagementService,
                              @Autowired(required = false) ObjectMapper objectMapper,
                              @Value("${astra.benchmark.history-dir:target/benchmark-runs/}") String historyDir) {
        this.retrievalService = retrievalService;
        this.intentClassifier = intentClassifier;
        this.datasetProvider = (datasetProvider != null) ? datasetProvider : new BenchmarkDatasetProvider(objectMapper);
        this.evaluator = (evaluator != null) ? evaluator : new RetrievalEvaluator();
        this.repositoryManagementService = repositoryManagementService;
        this.objectMapper = (objectMapper != null) ? objectMapper.copy().enable(SerializationFeature.INDENT_OUTPUT)
                : new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        this.historyDir = historyDir;
    }

    public RetrievalBenchmark(RetrievalService retrievalService,
                              QueryIntentClassifier intentClassifier,
                              BenchmarkDatasetProvider datasetProvider,
                              RetrievalEvaluator evaluator) {
        this(retrievalService, intentClassifier, datasetProvider, evaluator, null, null, "target/benchmark-runs/");
    }

    public RetrievalBenchmark(RetrievalService retrievalService,
                              QueryIntentClassifier intentClassifier) {
        this(retrievalService, intentClassifier, null, null, null, null, "target/benchmark-runs/");
    }

    /**
     * Executes the benchmark suite specified in the request.
     *
     * @param request the benchmark configuration request
     * @return strongly-typed {@link BenchmarkResponse} containing summary and per-query metrics
     * @throws BenchmarkConflictException if another benchmark suite is currently executing
     * @throws IllegalArgumentException    if repositoryId is invalid or unindexed
     */
    public BenchmarkResponse runBenchmark(BenchmarkRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("BenchmarkRequest must not be null");
        }
        String requestedRepo = request.getResolvedRepositoryName();
        String resolvedRepo = resolveAndValidateRepository(requestedRepo);

        // Concurrency Guard: reject overlapping benchmark executions
        if (!isRunning.compareAndSet(false, true)) {
            log.warn("Benchmark execution rejected: a benchmark suite is already currently executing.");
            throw new BenchmarkConflictException("A benchmark suite is already currently executing. Please wait for it to complete.");
        }

        long startTime = System.currentTimeMillis();
        try {
            boolean isCustom = request.isCustomQuestionMode();
            List<BenchmarkQueryDefinition> queries;
            String suiteName;

            if (isCustom) {
                suiteName = (request.getSuiteName() != null && !request.getSuiteName().isBlank() && !"default".equalsIgnoreCase(request.getSuiteName()))
                        ? request.getSuiteName() : "custom";
                queries = buildCustomQueries(request, resolvedRepo);
            } else {
                BenchmarkSuite suite = datasetProvider.getSuite(request.getSuiteName());
                suiteName = suite.getSuiteName();
                queries = suite.getQueries();
            }

            int effectiveTopK = (request.getTopK() > 0) ? request.getTopK() : 5;
            log.info("Starting retrieval benchmark (mode={}, suite='{}') with {} queries against repo '{}' at topK={}",
                    isCustom ? "CUSTOM_QUESTIONS" : "DATASET", suiteName, queries.size(), resolvedRepo, effectiveTopK);

            List<QueryBenchmarkResultDTO> results = new ArrayList<>();
            int timeoutCount = 0;

            for (BenchmarkQueryDefinition bq : queries) {
                QueryBenchmarkResultDTO result = evaluateSingleQuery(bq, resolvedRepo, effectiveTopK);
                results.add(result);
            }

            long totalDurationMs = System.currentTimeMillis() - startTime;
            BenchmarkSummaryDTO summary = evaluator.aggregateSummary(results, totalDurationMs, timeoutCount);

            BenchmarkResponse response = new BenchmarkResponse(
                    resolvedRepo,
                    suiteName,
                    request.getRetrievalConfiguration(),
                    effectiveTopK,
                    summary,
                    results
            );
            response.setRepositoryName(resolvedRepo);
            response.setRepositoryId(resolvedRepo);
            response.setMode(isCustom ? "CUSTOM_QUESTIONS" : "DATASET");

            if (summary.isHasGroundTruth()) {
                log.info("BENCHMARK COMPLETED | repo='{}' | mode='{}' | suite='{}' | queries={} | success={} | failed={} | HR@5={}% | MRR={} | NDCG@5={} | avgLatency={}ms",
                        resolvedRepo, response.getMode(), suiteName, results.size(),
                        summary.getSuccessfulQueries(), summary.getFailedQueries(),
                        summary.getHitRateAt5() != null ? Math.round(summary.getHitRateAt5() * 100.0) : 0,
                        summary.getMrr(), summary.getNdcgAt5(), summary.getAvgTotalLatencyMs());
            } else {
                log.info("BENCHMARK COMPLETED | repo='{}' | mode='{}' | suite='{}' | queries={} | success={} | failed={} | avgLatency={}ms | avgChunks={}",
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
            for (com.shravan.jcode_intelligence.dto.request.CustomBenchmarkQuestionDTO cq : request.getCustomQuestions()) {
                if (cq.getQuery() == null || cq.getQuery().isBlank()) {
                    continue;
                }
                String qid = (cq.getQueryId() != null && !cq.getQueryId().isBlank())
                        ? cq.getQueryId() : "custom-" + counter++;
                queries.add(new BenchmarkQueryDefinition(
                        qid,
                        cq.getQuery().trim(),
                        (cq.getCategory() != null && !cq.getCategory().isBlank()) ? cq.getCategory() : "Custom",
                        repositoryName,
                        cq.getExpectedIntent(),
                        cq.getRelevantChunks() != null ? cq.getRelevantChunks() : Collections.emptyList(),
                        cq.getReferenceAnswer(),
                        Collections.emptyList()
                ));
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
                        Collections.emptyList()
                ));
            }
        }

        if (queries.isEmpty()) {
            throw new IllegalArgumentException("At least one valid question is required for custom question benchmark");
        }

        return queries;
    }

    /**
     * Evaluates a single benchmark query with full failure isolation.
     */
    private QueryBenchmarkResultDTO evaluateSingleQuery(BenchmarkQueryDefinition bq,
                                                       String repositoryId,
                                                       int topK) {
        long queryStart = System.currentTimeMillis();
        List<RetrievedChunkDTO> retrievedChunks = new ArrayList<>();
        boolean hasGroundTruth = (bq.getRelevantChunks() != null && !bq.getRelevantChunks().isEmpty());

        try {
            // Step 1: Classify intent
            QueryIntent classified = (intentClassifier != null)
                    ? intentClassifier.classify(bq.getQuery())
                    : QueryIntent.UNKNOWN;

            boolean intentMatch = false;
            if (bq.getExpectedIntent() != null) {
                intentMatch = bq.getExpectedIntent().equalsIgnoreCase(classified.name());
            }

            // Step 2: Retrieve documents with configured topK
            long retrieveStart = System.currentTimeMillis();
            List<Document> documents = retrievalService.retrieve(
                    bq.getQuery(), topK, repositoryId, ChatMode.AUTO
            );
            long retrieveLatency = System.currentTimeMillis() - retrieveStart;

            // Step 3: Compute deterministic IR quality metrics against ground truth if present
            QueryQualityMetricsDTO qualityMetrics = null;
            if (hasGroundTruth) {
                qualityMetrics = evaluator.evaluateQuery(
                        documents, bq.getRelevantChunks(), retrievedChunks
                );
            } else {
                evaluator.populateRetrievedChunks(documents, retrievedChunks);
            }

            long totalQueryLatency = System.currentTimeMillis() - queryStart;

            // Step 4: Record operational metrics
            QueryOperationalMetricsDTO operationalMetrics = new QueryOperationalMetricsDTO(
                    totalQueryLatency,
                    0L, // embedding latency if isolated
                    retrieveLatency,
                    0L, // reranking latency if isolated
                    documents != null ? documents.size() : 0
            );

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
                    operationalMetrics
            );
            result.setHasGroundTruth(hasGroundTruth);
            return result;
        } catch (Exception ex) {
            long totalQueryLatency = System.currentTimeMillis() - queryStart;
            log.warn("Benchmark query '{}' failed: {}", bq.getQueryId(), ex.getMessage());

            QueryOperationalMetricsDTO operationalMetrics = new QueryOperationalMetricsDTO(
                    totalQueryLatency, 0L, 0L, 0L, 0
            );

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
                    operationalMetrics
            );
            result.setHasGroundTruth(hasGroundTruth);
            return result;
        }
    }

    /**
     * Resolves the target repository by name, matching case-insensitively and checking for ambiguity.
     */
    private String resolveAndValidateRepository(String requestedName) {
        if (requestedName == null || requestedName.isBlank()) {
            throw new IllegalArgumentException("repositoryName is required");
        }

        if (repositoryManagementService == null) {
            return requestedName.trim();
        }

        List<String> indexedRepos = repositoryManagementService.listRepositories();
        if (indexedRepos == null || indexedRepos.isEmpty()) {
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
            throw new IllegalArgumentException("Ambiguous repository name '" + requestedName + "'. Multiple matches found: " + caseMatches);
        }

        // 3. Case-insensitive contains/prefix match
        List<String> partialMatches = indexedRepos.stream()
                .filter(r -> r.toLowerCase().contains(target.toLowerCase()))
                .toList();
        if (partialMatches.size() == 1) {
            return partialMatches.get(0);
        } else if (partialMatches.size() > 1) {
            throw new IllegalArgumentException("Ambiguous repository name '" + requestedName + "'. Multiple matches found: " + partialMatches);
        }

        // 4. Not found
        throw new IllegalArgumentException("Repository '" + requestedName + "' is not indexed. Available repositories: " + indexedRepos);
    }

    /**
     * Persists the benchmark response as structured JSON for historical regression tracking.
     */
    private void persistBenchmarkRun(BenchmarkResponse response) {
        try {
            Path targetPath = Paths.get(historyDir);
            Files.createDirectories(targetPath);

            String repoIdentifier = response.getRepositoryName() != null ? response.getRepositoryName() : response.getRepositoryId();
            String safeRepo = (repoIdentifier != null ? repoIdentifier : "unknown").replaceAll("[^a-zA-Z0-9.-]", "_");
            String safeSuite = (response.getSuiteName() != null ? response.getSuiteName() : "benchmark").replaceAll("[^a-zA-Z0-9.-]", "_");
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
     * Legacy runner method for backward compatibility with existing tests.
     * Delegates to the externalized benchmark dataset infrastructure.
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
                    qr.getOperationalMetrics() != null ? qr.getOperationalMetrics().getTotalLatencyMs() : 0L
            ));
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
            long latencyMs
    ) {}
}
