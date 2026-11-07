package com.shravan.jcode_intelligence.controller;

import com.shravan.jcode_intelligence.dto.request.BenchmarkRequest;
import com.shravan.jcode_intelligence.dto.response.BenchmarkResponse;
import com.shravan.jcode_intelligence.service.RetrievalBenchmark;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST endpoint for executing retrieval evaluation benchmarks.
 *
 * <p>Usage:
 * <pre>
 *   POST /api/v1/benchmark/run
 *   Body: {
 *     "repositoryId": "javaparser",
 *     "suiteName": "default",
 *     "topK": 5,
 *     "retrievalConfiguration": "DENSE_ONLY",
 *     "async": false
 *   }
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
    public ResponseEntity<BenchmarkResponse> runBenchmark(@Valid @RequestBody BenchmarkRequest request) {
        BenchmarkResponse response = benchmark.runBenchmark(request);
        return ResponseEntity.ok(response);
    }
}
