package com.shravan.jcode_intelligence.controller;

import com.shravan.jcode_intelligence.dto.request.BenchmarkRequest;
import com.shravan.jcode_intelligence.dto.response.BenchmarkResponse;
import com.shravan.jcode_intelligence.dto.response.BenchmarkSummaryDTO;
import com.shravan.jcode_intelligence.dto.response.QueryBenchmarkResultDTO;
import com.shravan.jcode_intelligence.exception.BenchmarkConflictException;
import com.shravan.jcode_intelligence.service.RetrievalBenchmark;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class BenchmarkControllerTest {

    private BenchmarkController controller;
    private AtomicReference<BenchmarkRequest> capturedRequest;
    private boolean shouldThrowConflict = false;
    private boolean shouldThrowUnindexed = false;

    @BeforeEach
    void setUp() {
        capturedRequest = new AtomicReference<>();
        shouldThrowConflict = false;
        shouldThrowUnindexed = false;

        RetrievalBenchmark stubBenchmark = new RetrievalBenchmark(null, null) {
            @Override
            public BenchmarkResponse runBenchmark(BenchmarkRequest request) {
                capturedRequest.set(request);
                if (shouldThrowConflict) {
                    throw new BenchmarkConflictException("A benchmark suite is already currently executing.");
                }
                if (shouldThrowUnindexed) {
                    throw new IllegalArgumentException("Repository '" + request.getRepositoryId() + "' is not indexed.");
                }

                BenchmarkSummaryDTO summary = new BenchmarkSummaryDTO();
                summary.setTotalQueries(1);
                summary.setSuccessfulQueries(1);
                summary.setHitRateAt5(1.0);
                summary.setMrr(1.0);

                QueryBenchmarkResultDTO queryResult = new QueryBenchmarkResultDTO();
                queryResult.setQueryId("Q01");
                queryResult.setStatus("SUCCESS");

                return new BenchmarkResponse(
                        request.getRepositoryId(),
                        request.getSuiteName(),
                        request.getRetrievalConfiguration(),
                        request.getTopK(),
                        summary,
                        List.of(queryResult)
                );
            }
        };

        controller = new BenchmarkController(stubBenchmark);
    }

    @Test
    @DisplayName("runBenchmark returns HTTP 200 with typed BenchmarkResponse")
    void testRunBenchmarkSuccess() {
        BenchmarkRequest request = new BenchmarkRequest("test-repo", "default", 10, "DENSE_ONLY", false);

        ResponseEntity<BenchmarkResponse> response = controller.runBenchmark(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("test-repo", response.getBody().getRepositoryId());
        assertEquals("default", response.getBody().getSuiteName());
        assertEquals(10, response.getBody().getTopK());
        assertEquals(1, response.getBody().getSummary().getTotalQueries());
        assertEquals(1.0, response.getBody().getSummary().getHitRateAt5(), 0.0001);

        assertNotNull(capturedRequest.get());
        assertEquals("test-repo", capturedRequest.get().getRepositoryId());
        assertEquals(10, capturedRequest.get().getTopK());
    }

    @Test
    @DisplayName("Propagates BenchmarkConflictException when another suite is executing")
    void testRunBenchmarkConflict() {
        shouldThrowConflict = true;
        BenchmarkRequest request = new BenchmarkRequest("test-repo");

        assertThrows(BenchmarkConflictException.class, () -> controller.runBenchmark(request));
    }

    @Test
    @DisplayName("Propagates IllegalArgumentException when repository is not indexed")
    void testRunBenchmarkUnindexedRepository() {
        shouldThrowUnindexed = true;
        BenchmarkRequest request = new BenchmarkRequest("non-existent-repo");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> controller.runBenchmark(request));
        assertTrue(ex.getMessage().contains("is not indexed"));
    }
}

