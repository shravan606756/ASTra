package com.shravan.jcode_intelligence.controller;

import com.shravan.jcode_intelligence.service.RetrievalBenchmark;
import com.shravan.jcode_intelligence.service.RetrievalBenchmark.BenchmarkResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST endpoint for running retrieval quality benchmarks.
 *
 * <p>Usage:
 * <pre>
 *   POST /api/v1/benchmark/run
 *   Body: { "repositoryId": "javaparser" }
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/benchmark")
public class BenchmarkController {

    private final RetrievalBenchmark benchmark;

    public BenchmarkController(RetrievalBenchmark benchmark) {
        this.benchmark = benchmark;
    }

    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> runBenchmark(@RequestBody Map<String, String> request) {
        String repositoryId = request.getOrDefault("repositoryId", null);

        List<BenchmarkResult> results = benchmark.runBenchmark(repositoryId);

        long passCount = results.stream().filter(BenchmarkResult::intentMatch).count();
        double accuracy = results.isEmpty() ? 0.0 : (passCount * 100.0 / results.size());
        long avgLatency = results.isEmpty() ? 0 :
                results.stream().mapToLong(BenchmarkResult::latencyMs).sum() / results.size();

        Map<String, Object> response = Map.of(
                "totalQueries", results.size(),
                "passed", passCount,
                "failed", results.size() - passCount,
                "accuracyPercent", accuracy,
                "averageLatencyMs", avgLatency,
                "results", results
        );

        return ResponseEntity.ok(response);
    }
}
