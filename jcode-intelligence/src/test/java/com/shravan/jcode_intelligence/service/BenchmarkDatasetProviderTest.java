package com.shravan.jcode_intelligence.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shravan.jcode_intelligence.model.benchmark.BenchmarkQueryDefinition;
import com.shravan.jcode_intelligence.model.benchmark.BenchmarkSuite;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class BenchmarkDatasetProviderTest {

    private BenchmarkDatasetProvider provider;

    @BeforeEach
    void setUp() {
        provider = new BenchmarkDatasetProvider(new ObjectMapper());
        provider.init();
    }

    @Test
    @DisplayName("Loads default benchmark suite with 50 questions across 7 core categories")
    void testLoadDefaultSuite() {
        BenchmarkSuite suite = provider.getSuite("default");
        assertNotNull(suite, "Default suite should be discovered and loaded");
        assertEquals("default", suite.getSuiteName());
        assertEquals(50, suite.getQueries().size(), "Should contain exactly 50 benchmark queries");

        // Verify all 7 required query categories are represented
        Set<String> categories = suite.getQueries().stream()
                .map(BenchmarkQueryDefinition::getCategory)
                .collect(Collectors.toSet());

        Set<String> expectedCategories = Set.of(
                "Class identification",
                "Method implementation",
                "Multi-class execution flows",
                "Dependencies and caller relationships",
                "Exception handling",
                "Business logic",
                "Configuration and initialization"
        );

        assertTrue(categories.containsAll(expectedCategories),
                "Dataset must cover all 7 required categories: " + categories);

        // Verify ground truth annotations exist for every query
        for (BenchmarkQueryDefinition query : suite.getQueries()) {
            assertNotNull(query.getQueryId(), "queryId must not be null");
            assertNotNull(query.getQuery(), "query must not be null");
            assertNotNull(query.getCategory(), "category must not be null");
            assertNotNull(query.getExpectedIntent(), "expectedIntent must not be null");
            assertFalse(query.getRelevantChunks().isEmpty(),
                    "query " + query.getQueryId() + " must have at least one relevant ground-truth chunk");
        }
    }

    @Test
    @DisplayName("Throws IllegalArgumentException when requesting nonexistent suite")
    void testNonexistentSuiteThrows() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> provider.getSuite("non-existent-suite-404"));

        assertTrue(ex.getMessage().contains("Benchmark suite 'non-existent-suite-404' not found"));
    }

    @Test
    @DisplayName("Supports registering custom benchmark suites dynamically")
    void testRegisterCustomSuite() {
        BenchmarkSuite custom = new BenchmarkSuite(
                "custom-test-suite",
                "Custom test description",
                "2.0.0",
                "test-repo",
                List.of(new BenchmarkQueryDefinition("Q1", "Test query?", "Class identification", "test-repo", "CLASS", List.of(), "ans", List.of()))
        );

        provider.registerSuite(custom);

        BenchmarkSuite retrieved = provider.getSuite("custom-test-suite");
        assertNotNull(retrieved);
        assertEquals("custom-test-suite", retrieved.getSuiteName());
        assertEquals(1, retrieved.getQueries().size());
    }
}

