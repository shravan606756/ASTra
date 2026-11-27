package com.shravan.jcode_intelligence.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shravan.jcode_intelligence.config.IntentRetrievalConfig;
import com.shravan.jcode_intelligence.config.RetrievalConfig;
import com.shravan.jcode_intelligence.dto.request.BenchmarkRequest;
import com.shravan.jcode_intelligence.dto.response.BenchmarkResponse;
import com.shravan.jcode_intelligence.dto.response.BenchmarkSummaryDTO;
import com.shravan.jcode_intelligence.model.RetrievalStrategy;
import com.shravan.jcode_intelligence.model.benchmark.BenchmarkSuite;
import com.shravan.jcode_intelligence.service.impl.RetrievalServiceImpl;
import com.shravan.jcode_intelligence.service.reranking.CrossEncoderReranker;
import com.shravan.jcode_intelligence.sparse.fusion.ReciprocalRankFusion;
import com.shravan.jcode_intelligence.sparse.mapper.LuceneDocumentMapper;
import com.shravan.jcode_intelligence.sparse.query.LuceneQueryBuilder;
import com.shravan.jcode_intelligence.sparse.store.LuceneGenerationStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;

import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Opt-in calibration harness evaluating Cross-Encoder reranking feasibility against heuristic reranking
 * on the reference JavaParser codebase across candidate limits N = 10, 20, and 30.
 *
 * <p>Execution is strictly gated behind the system property:
 * {@code -Dastra.benchmark.cross-encoder=true}
 */
@EnabledIfSystemProperty(named = "astra.benchmark.cross-encoder", matches = "true")
class JavaParserCrossEncoderCalibrationTest {

    @Test
    @DisplayName("Calibrate Cross-Encoder Cascade Feasibility against Heuristic Baseline (N = 10, 20, 30)")
    void calibrateCrossEncoderCascade(@TempDir Path tempDir) throws Exception {
        // 1. Setup BenchmarkDatasetProvider and load the JavaParser default suite
        ObjectMapper objectMapper = new ObjectMapper();
        BenchmarkDatasetProvider datasetProvider = new BenchmarkDatasetProvider(objectMapper);
        datasetProvider.init();
        BenchmarkSuite suite = datasetProvider.getSuite("default");
        assertNotNull(suite);
        assertEquals(50, suite.getQueries().size());

        // 2. Hardware telemetry
        String osName = System.getProperty("os.name");
        String osArch = System.getProperty("os.arch");
        int cpus = Runtime.getRuntime().availableProcessors();
        String jvmVersion = System.getProperty("java.version");
        long maxHeapMb = Runtime.getRuntime().maxMemory() / (1024 * 1024);

        System.out.println("================================================================================");
        System.out.println("ASTra Cross-Encoder Reranking Feasibility & Calibration Telemetry");
        System.out.println("================================================================================");
        System.out.println("Hardware & Runtime Environment:");
        System.out.printf("  OS: %s (%s)%n", osName, osArch);
        System.out.printf("  Available Processors (vCPUs): %d%n", cpus);
        System.out.printf("  JVM: %s (%s)%n", jvmVersion, System.getProperty("java.vm.name"));
        System.out.printf("  Max Heap (-Xmx): %d MB%n", maxHeapMb);
        System.out.println("Repository: javaparser (pinned commit: d8c5f6e80b435b6dfaa79148db9d6d34bbf37c68, tag: javaparser-parent-3.28.2)");
        System.out.println("Suite: default (50 curated ground-truth queries, topK=5)");
        System.out.println("Candidate Limits Evaluated: N = 10, 20, 30 (fetching at least 30 candidates)");
        System.out.println("Primary Candidate Model: ms-marco-MiniLM-L-6-v2 (ONNX)");
        System.out.println("Optional Comparison Model: bge-reranker-base (ONNX)");
        System.out.println("--------------------------------------------------------------------------------");

        // 3. Setup Lucene configuration and store
        RetrievalConfig retrievalConfig = new RetrievalConfig();
        retrievalConfig.setIndexDir(tempDir.toString());
        retrievalConfig.setK1(1.2f);
        retrievalConfig.setB(0.75f);
        retrievalConfig.setFuzzyMinTermLength(4);
        retrievalConfig.setFuzzyMaxEdits(1);
        retrievalConfig.setRrfK(60);
        retrievalConfig.setDefaultDenseWeight(0.5);
        retrievalConfig.setDefaultSparseWeight(0.5);

        LuceneDocumentMapper docMapper = new LuceneDocumentMapper();
        LuceneQueryBuilder queryBuilder = new LuceneQueryBuilder(retrievalConfig);

        try (LuceneGenerationStore luceneStore = new LuceneGenerationStore(retrievalConfig, docMapper, queryBuilder)) {
            // Build JavaParser corpus (ensuring >= 30 candidates are available)
            List<Document> corpus = JavaParserRetrievalStrategyBenchmarkTest.buildJavaParserCorpus(suite);
            assertTrue(corpus.size() >= 30, "Corpus must have at least 30 candidate documents");

            try (LuceneGenerationStore.StagingSession session = luceneStore.stageGeneration("javaparser")) {
                session.writeDocuments(corpus);
                luceneStore.publishGeneration(session);
            }

            JavaParserRetrievalStrategyBenchmarkTest.BenchmarkVectorStore vectorStore =
                    new JavaParserRetrievalStrategyBenchmarkTest.BenchmarkVectorStore(corpus);
            JavaParserRetrievalStrategyBenchmarkTest.CorpusJdbcTemplate jdbcTemplate =
                    new JavaParserRetrievalStrategyBenchmarkTest.CorpusJdbcTemplate(corpus);

            IntentRetrievalConfig intentConfig = new IntentRetrievalConfig();
            ArchitectureContextBuilder archBuilder = new ArchitectureContextBuilder(jdbcTemplate);
            QueryIntentClassifier intentClassifier = new QueryIntentClassifier();
            ReciprocalRankFusion rrf = new ReciprocalRankFusion(retrievalConfig);
            RetrievalReranker heuristicReranker = new RetrievalReranker(intentConfig);
            SymbolExtractor symbolExtractor = new SymbolExtractor();

            RetrievalStrategySelector selector = new RetrievalStrategySelector(
                    vectorStore,
                    jdbcTemplate,
                    symbolExtractor,
                    intentConfig,
                    archBuilder,
                    objectMapper,
                    null,
                    luceneStore,
                    rrf,
                    retrievalConfig
            );

            RetrievalEvaluator evaluator = new RetrievalEvaluator();

            // Calibration targets: Baseline Heuristic, Cross-Encoder N=10, Cross-Encoder N=20, Cross-Encoder N=30
            int[] candidateLimits = new int[]{10, 20, 30};
            Map<String, BenchmarkResponse> calibrationResults = new LinkedHashMap<>();

            // 4. Baseline: Heuristic Strategy
            RetrievalServiceImpl heuristicService = new RetrievalServiceImpl(
                    intentClassifier, selector, heuristicReranker, symbolExtractor, intentConfig, retrievalConfig,
                    List.of(heuristicReranker)
            );
            RetrievalBenchmark heuristicBenchmark = new RetrievalBenchmark(
                    heuristicService, intentClassifier, datasetProvider, evaluator,
                    null, objectMapper, tempDir.resolve("heuristic-runs").toString(), retrievalConfig
            );

            // Warm-up pass
            BenchmarkRequest warmUpReq = new BenchmarkRequest("javaparser", "default", 5, "HYBRID_RRF", false);
            warmUpReq.setRerankingStrategy("heuristic");
            heuristicBenchmark.runBenchmark(warmUpReq);

            // Measured baseline pass
            BenchmarkRequest baselineReq = new BenchmarkRequest("javaparser", "default", 5, "HYBRID_RRF", false);
            baselineReq.setRerankingStrategy("heuristic");
            BenchmarkResponse baselineResp = heuristicBenchmark.runBenchmark(baselineReq);
            calibrationResults.put("Heuristic (Baseline)", baselineResp);

            // 5. Evaluate Cross-Encoder at N = 10, 20, 30
            String modelPath = System.getProperty("astra.reranker.cross-encoder.model-path", "models/ms-marco-MiniLM-L-6-v2.onnx");
            String tokenizerPath = System.getProperty("astra.reranker.cross-encoder.tokenizer-path", "models/tokenizer.json");

            for (int n : candidateLimits) {
                CrossEncoderReranker crossEncoder = new CrossEncoderReranker(
                        heuristicReranker,
                        true,
                        modelPath,
                        tokenizerPath,
                        512,
                        n
                );

                RetrievalServiceImpl ceService = new RetrievalServiceImpl(
                        intentClassifier, selector, heuristicReranker, symbolExtractor, intentConfig, retrievalConfig,
                        List.of(heuristicReranker, crossEncoder)
                );

                RetrievalBenchmark ceBenchmark = new RetrievalBenchmark(
                        ceService, intentClassifier, datasetProvider, evaluator,
                        null, objectMapper, tempDir.resolve("ce-" + n + "-runs").toString(), retrievalConfig
                );

                // Warm-up pass for ONNX session
                BenchmarkRequest ceWarmUp = new BenchmarkRequest("javaparser", "default", 5, "HYBRID_RRF", false);
                ceWarmUp.setRerankingStrategy("cross-encoder");
                ceBenchmark.runBenchmark(ceWarmUp);

                // Measured pass
                BenchmarkRequest ceReq = new BenchmarkRequest("javaparser", "default", 5, "HYBRID_RRF", false);
                ceReq.setRerankingStrategy("cross-encoder");
                BenchmarkResponse ceResp = ceBenchmark.runBenchmark(ceReq);
                calibrationResults.put("Cross-Encoder (N=" + n + ")", ceResp);

                crossEncoder.close();
            }

            // 6. Print Formatted Calibration Table
            System.out.println("--------------------------------------------------------------------------------");
            System.out.printf("%-24s | %-7s | %-7s | %-7s | %-7s | %-8s | %-8s | %-8s%n",
                    "Reranking Strategy", "HR@5", "Recall@5", "MRR", "NDCG@5", "Avg Lat(ms)", "p50(ms)", "p95(ms)");
            System.out.println("--------------------------------------------------------------------------------");

            for (Map.Entry<String, BenchmarkResponse> entry : calibrationResults.entrySet()) {
                String name = entry.getKey();
                BenchmarkSummaryDTO s = entry.getValue().getSummary();
                System.out.printf("%-24s | %6.1f%% | %6.1f%% | %7.4f | %7.4f | %9.1f | %7.1f | %7.1f%n",
                        name,
                        s.getHitRateAt5() != null ? s.getHitRateAt5() * 100.0 : 0.0,
                        s.getRecallAt5() != null ? s.getRecallAt5() * 100.0 : 0.0,
                        s.getMrr() != null ? s.getMrr() : 0.0,
                        s.getNdcgAt5() != null ? s.getNdcgAt5() : 0.0,
                        s.getAvgTotalLatencyMs(),
                        s.getP50LatencyMs(),
                        s.getP95LatencyMs());
            }
            System.out.println("================================================================================");
        }
    }
}
