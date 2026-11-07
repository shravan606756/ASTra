package com.shravan.jcode_intelligence.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shravan.jcode_intelligence.model.benchmark.BenchmarkSuite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Discovers, validates, and loads curated ground-truth benchmark suites from externalized JSON files.
 */
@Component
public class BenchmarkDatasetProvider {

    private static final Logger log = LoggerFactory.getLogger(BenchmarkDatasetProvider.class);

    private final ObjectMapper objectMapper;
    private final ResourcePatternResolver resourceResolver;
    private final Map<String, BenchmarkSuite> loadedSuites = new ConcurrentHashMap<>();

    public BenchmarkDatasetProvider() {
        this(null);
    }

    @Autowired
    public BenchmarkDatasetProvider(@Autowired(required = false) ObjectMapper objectMapper) {
        this.objectMapper = (objectMapper != null) ? objectMapper : new ObjectMapper();
        this.resourceResolver = new PathMatchingResourcePatternResolver();
    }

    @PostConstruct
    public void init() {
        loadClasspathSuites();
    }

    /**
     * Loads all benchmark suites found under classpath:benchmarks/*.json
     */
    public synchronized void loadClasspathSuites() {
        try {
            Resource[] resources = resourceResolver.getResources("classpath*:benchmarks/*.json");
            for (Resource resource : resources) {
                try (InputStream is = resource.getInputStream()) {
                    BenchmarkSuite suite = objectMapper.readValue(is, BenchmarkSuite.class);
                    if (suite.getSuiteName() != null && !suite.getSuiteName().isBlank()) {
                        loadedSuites.put(suite.getSuiteName().toLowerCase(Locale.ROOT), suite);
                        // Also register with filename base (e.g. "default-benchmark" from "default-benchmark.json")
                        String filename = resource.getFilename();
                        if (filename != null && filename.endsWith(".json")) {
                            String baseName = filename.substring(0, filename.length() - 5).toLowerCase(Locale.ROOT);
                            loadedSuites.putIfAbsent(baseName, suite);
                        }
                        log.info("Loaded benchmark suite '{}' with {} queries from {}",
                                suite.getSuiteName(), suite.getQueries().size(), resource.getFilename());
                    }
                } catch (Exception e) {
                    log.warn("Failed to load benchmark resource '{}': {}", resource.getFilename(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Error discovering benchmark resources on classpath: {}", e.getMessage());
        }
    }

    /**
     * Retrieves a benchmark suite by name.
     *
     * @param suiteName the name or filename of the suite (e.g. "default")
     * @return the loaded {@link BenchmarkSuite}
     * @throws IllegalArgumentException if the suite is not found
     */
    public BenchmarkSuite getSuite(String suiteName) {
        if (suiteName == null || suiteName.isBlank()) {
            suiteName = "default";
        }
        String key = suiteName.trim().toLowerCase(Locale.ROOT);
        BenchmarkSuite suite = loadedSuites.get(key);
        if (suite == null) {
            // Try reloading once in case resources were added dynamically
            loadClasspathSuites();
            suite = loadedSuites.get(key);
        }
        if (suite == null) {
            throw new IllegalArgumentException("Benchmark suite '" + suiteName + "' not found. Available suites: " + getAvailableSuites());
        }
        return suite;
    }

    /**
     * Registers a suite directly into the provider (useful for testing).
     */
    public void registerSuite(BenchmarkSuite suite) {
        if (suite != null && suite.getSuiteName() != null) {
            loadedSuites.put(suite.getSuiteName().toLowerCase(Locale.ROOT), suite);
        }
    }

    /**
     * Returns the list of all available suite names.
     */
    public List<String> getAvailableSuites() {
        return new ArrayList<>(loadedSuites.keySet());
    }
}

