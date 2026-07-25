package com.shravan.jcode_intelligence.service;

import com.shravan.jcode_intelligence.dto.request.ChatMode;
import com.shravan.jcode_intelligence.model.QueryIntent;
import com.shravan.jcode_intelligence.service.impl.RetrievalServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Retrieval quality evaluation framework.
 *
 * <p>Runs a predefined set of benchmark queries through the retrieval pipeline
 * and produces structured evaluation results for each query:
 * <ul>
 *   <li>Classified intent</li>
 *   <li>Retrieved chunk types and counts</li>
 *   <li>Top element names</li>
 *   <li>Retrieval latency</li>
 *   <li>Pass/fail assessment against expected intent</li>
 * </ul>
 */
@Component
public class RetrievalBenchmark {

    private static final Logger log = LoggerFactory.getLogger(RetrievalBenchmark.class);

    private final RetrievalServiceImpl retrievalService;
    private final QueryIntentClassifier intentClassifier;

    public RetrievalBenchmark(RetrievalServiceImpl retrievalService,
                               QueryIntentClassifier intentClassifier) {
        this.retrievalService = retrievalService;
        this.intentClassifier = intentClassifier;
    }

    /**
     * Runs the full benchmark suite against the specified repository.
     *
     * @param repositoryId the target repository to benchmark against
     * @return list of benchmark results
     */
    public List<BenchmarkResult> runBenchmark(String repositoryId) {
        List<BenchmarkQuery> queries = buildBenchmarkQueries();
        List<BenchmarkResult> results = new ArrayList<>();

        log.info("Starting retrieval benchmark with {} queries against repository '{}'",
                queries.size(), repositoryId);

        for (BenchmarkQuery bq : queries) {
            BenchmarkResult result = evaluateQuery(bq, repositoryId);
            results.add(result);

            log.info("BENCHMARK | query='{}' | expectedIntent={} | actualIntent={} | pass={} " +
                     "| chunkTypes={} | topElements={} | latencyMs={}",
                    bq.query, bq.expectedIntent, result.classifiedIntent,
                    result.intentMatch, result.chunkTypeCounts,
                    result.topElementNames, result.latencyMs);
        }

        // Summary
        long passCount = results.stream().filter(r -> r.intentMatch).count();
        log.info("BENCHMARK SUMMARY | total={} | pass={} | fail={} | accuracy={}%",
                results.size(), passCount, results.size() - passCount,
                results.isEmpty() ? 0 : (passCount * 100 / results.size()));

        return results;
    }

    private BenchmarkResult evaluateQuery(BenchmarkQuery bq, String repositoryId) {
        long start = System.currentTimeMillis();

        // Classify intent
        QueryIntent classified = intentClassifier.classify(bq.query);

        // Retrieve documents
        List<Document> documents = retrievalService.retrieve(
                bq.query, 0, repositoryId, ChatMode.AUTO);

        long latency = System.currentTimeMillis() - start;

        // Compute chunk type counts
        Map<String, Integer> chunkTypeCounts = new LinkedHashMap<>();
        for (Document doc : documents) {
            String type = String.valueOf(doc.getMetadata().getOrDefault("type", "UNKNOWN"));
            chunkTypeCounts.merge(type, 1, Integer::sum);
        }

        // Extract top element names
        List<String> topElements = new ArrayList<>();
        for (Document doc : documents) {
            String element = String.valueOf(doc.getMetadata().getOrDefault("elementName", ""));
            if (!element.isBlank() && topElements.size() < 10) {
                topElements.add(element);
            }
        }

        // Determine top chunk type
        String topChunkType = chunkTypeCounts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("NONE");

        return new BenchmarkResult(
                bq.query,
                bq.expectedIntent.name(),
                classified.name(),
                classified == bq.expectedIntent,
                chunkTypeCounts,
                topElements,
                topChunkType,
                documents.size(),
                latency
        );
    }

    // ── Benchmark Query Definitions ───────────────────────────

    private List<BenchmarkQuery> buildBenchmarkQueries() {
        return List.of(
                new BenchmarkQuery("Explain Node", QueryIntent.CLASS),
                new BenchmarkQuery("Explain findAll()", QueryIntent.METHOD),
                new BenchmarkQuery("Where is LexicalPreservingPrinter?", QueryIntent.SEARCH),
                new BenchmarkQuery("Explain package structure", QueryIntent.PACKAGE),
                new BenchmarkQuery("Architecture overview", QueryIntent.ARCHITECTURE),
                new BenchmarkQuery("How does JavaParser parse a Java source file?", QueryIntent.WORKFLOW),
                new BenchmarkQuery("Where is BackupExecutionService?", QueryIntent.SEARCH),
                new BenchmarkQuery("Which classes use NodeList?", QueryIntent.RELATIONSHIP)
        );
    }

    // ── Data Classes ──────────────────────────────────────────

    private record BenchmarkQuery(String query, QueryIntent expectedIntent) {}

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
