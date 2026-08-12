package com.shravan.jcode_intelligence.service;

import com.shravan.jcode_intelligence.dto.request.ChatMode;
import com.shravan.jcode_intelligence.service.RetrievalBenchmark.BenchmarkResult;
import com.shravan.jcode_intelligence.service.impl.RetrievalServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RetrievalBenchmarkTest {

    private static final int BENCHMARK_QUERY_COUNT = 8;

    @Test
    void runBenchmarkDoesNotRetainResultsFromPriorExecutions() {
        RetrievalBenchmark benchmark = new RetrievalBenchmark(
                new RepositoryLabelledRetrievalService(),
                new QueryIntentClassifier()
        );

        List<BenchmarkResult> firstRun = benchmark.runBenchmark("repository-a");
        List<BenchmarkResult> secondRun = benchmark.runBenchmark("repository-a");
        List<BenchmarkResult> thirdRun = benchmark.runBenchmark("repository-b");

        assertRunContainsOnlyRepository(firstRun, "repository-a");
        assertRunContainsOnlyRepository(secondRun, "repository-a");
        assertRunContainsOnlyRepository(thirdRun, "repository-b");
    }

    private void assertRunContainsOnlyRepository(
            List<BenchmarkResult> results,
            String repositoryId
    ) {
        assertEquals(BENCHMARK_QUERY_COUNT, results.size());

        for (BenchmarkResult result : results) {
            assertEquals(List.of(repositoryId), result.topElementNames());
            assertEquals(1, result.totalDocuments());
        }
    }

    private static class RepositoryLabelledRetrievalService extends RetrievalServiceImpl {

        RepositoryLabelledRetrievalService() {
            super(null, null, null, null, null);
        }

        @Override
        public List<Document> retrieve(
                String query,
                int topK,
                String repositoryId,
                ChatMode mode
        ) {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("type", "CLASS");
            metadata.put("elementName", repositoryId);

            return List.of(new Document("stub content for " + repositoryId, metadata));
        }
    }
}