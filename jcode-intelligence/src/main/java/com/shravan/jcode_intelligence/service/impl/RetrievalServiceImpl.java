package com.shravan.jcode_intelligence.service.impl;

import com.shravan.jcode_intelligence.dto.request.ChatMode;
import com.shravan.jcode_intelligence.service.RetrievalService;
import com.shravan.jcode_intelligence.service.SymbolExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class RetrievalServiceImpl implements RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalServiceImpl.class);

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;
    private final SymbolExtractor symbolExtractor;

    @Value("${astra.retrieval.similarity-threshold:0.65}")
    private double similarityThreshold;

    @Value("${astra.retrieval.raw-top-k:20}")
    private int rawTopK;

    @Value("${astra.retrieval.default-top-k:5}")
    private int defaultTopK;

    @Value("${astra.retrieval.architecture-top-k:20}")
    private int architectureTopK;

    @Value("${astra.retrieval.summary-top-k:50}")
    private int summaryTopK;

    public RetrievalServiceImpl(VectorStore vectorStore,
                                JdbcTemplate jdbcTemplate,
                                SymbolExtractor symbolExtractor) {
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
        this.symbolExtractor = symbolExtractor;
    }

    @Override
    public List<Document> retrieve(String query, int topK, String repositoryId) {
        return retrieve(query, topK, repositoryId, ChatMode.AUTO);
    }

    @Override
    public List<Document> retrieve(String query, int topK, String repositoryId, ChatMode mode) {
        if (mode == ChatMode.PROJECT_SUMMARY) {
            return retrieveProjectSummary(repositoryId);
        }

        if (query == null || query.isBlank()) {
            return List.of();
        }

        int effectiveTopK = (mode == ChatMode.ARCHITECTURE) ? architectureTopK : ((topK > 0) ? topK : defaultTopK);

        long startTime = System.currentTimeMillis();
        int candidateLimit = Math.max(effectiveTopK, rawTopK);

        // Path A: Metadata-first lookup for symbol queries
        Optional<String> symbol = symbolExtractor.extract(query);
        List<Document> metadataResults = List.of();
        if (symbol.isPresent()) {
            metadataResults = lookupByMetadata(symbol.get(), repositoryId);
            log.info("Symbol '{}' metadata lookup returned {} document(s)", symbol.get(), metadataResults.size());
        }

        // Path B: Vector similarity search (always executed)
        SearchRequest.Builder builder = SearchRequest.builder()
                .query(query)
                .topK(candidateLimit)
                .similarityThreshold(similarityThreshold);

        if (repositoryId != null && !repositoryId.isBlank()) {
            builder.filterExpression("repositoryId == '" + repositoryId + "'");
        }

        List<Document> vectorResults = vectorStore.similaritySearch(builder.build());
        log.info("Vector similarity search returned {} document(s)", vectorResults.size());

        // Merge: metadata results first (exact matches), then vector results
        List<Document> merged = merge(metadataResults, vectorResults);
        List<Document> deduplicated = deduplicate(merged);

        List<Document> finalResults = deduplicated.stream()
                .limit(effectiveTopK)
                .toList();

        long duration = System.currentTimeMillis() - startTime;
        log.info("Retrieved {} final chunk(s) (metadata: {}, vector: {}, merged: {}) in {} ms for query: '{}' (Mode: {})",
                finalResults.size(), metadataResults.size(), vectorResults.size(),
                deduplicated.size(), duration, query, mode);

        return finalResults;
    }

    private List<Document> retrieveProjectSummary(String repositoryId) {
        long startTime = System.currentTimeMillis();
        List<Document> merged = new ArrayList<>();
        String[] types = {"CLASS", "INTERFACE", "ENUM", "METHOD"};
        
        for (String type : types) {
            if (merged.size() >= summaryTopK) {
                break;
            }
            
            SearchRequest.Builder builder = SearchRequest.builder()
                    .query("")
                    .topK(summaryTopK);
                    
            if (repositoryId != null && !repositoryId.isBlank()) {
                builder.filterExpression("repositoryId == '" + repositoryId + "' && type == '" + type + "'");
            } else {
                builder.filterExpression("type == '" + type + "'");
            }
            
            try {
                List<Document> results = vectorStore.similaritySearch(builder.build());
                merged.addAll(results);
            } catch (Exception e) {
                log.warn("Failed to retrieve {} chunks for summary: {}", type, e.getMessage());
            }
        }
        
        List<Document> deduplicated = deduplicate(merged);
        List<Document> finalResults = deduplicated.stream()
                .limit(summaryTopK)
                .toList();
                
        long duration = System.currentTimeMillis() - startTime;
        log.info("Retrieved {} project summary chunk(s) in {} ms", finalResults.size(), duration);
        
        return finalResults;
    }

    /**
     * Performs a deterministic metadata lookup against the vector_store table
     * using the JSONB metadata column. Finds all chunks where className or
     * elementName matches the extracted symbol.
     */
    private List<Document> lookupByMetadata(String symbol, String repositoryId) {
        try {
            String sql;
            Object[] params;

            if (repositoryId != null && !repositoryId.isBlank()) {
                sql = "SELECT id, content, metadata FROM vector_store " +
                      "WHERE (metadata->>'className' = ? OR metadata->>'elementName' = ?) " +
                      "AND metadata->>'repositoryId' = ?";
                params = new Object[]{symbol, symbol, repositoryId};
            } else {
                sql = "SELECT id, content, metadata FROM vector_store " +
                      "WHERE metadata->>'className' = ? OR metadata->>'elementName' = ?";
                params = new Object[]{symbol, symbol};
            }

            return jdbcTemplate.query(sql, params, (rs, rowNum) -> {
                String content = rs.getString("content");
                String metadataJson = rs.getString("metadata");
                Map<String, Object> metadata = parseMetadataJson(metadataJson);
                return new Document(content, metadata);
            });
        } catch (Exception e) {
            log.error("Metadata lookup failed for symbol '{}': {}", symbol, e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * Parses the JSONB metadata string from PostgreSQL into a Map.
     * Uses a simple JSON parser to avoid adding external dependencies.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMetadataJson(String json) {
        if (json == null || json.isBlank()) {
            return new HashMap<>();
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(json, Map.class);
        } catch (Exception e) {
            log.warn("Failed to parse metadata JSON: {}", e.getMessage());
            return new HashMap<>();
        }
    }

    /**
     * Merges metadata results (exact symbol matches) with vector results
     * (semantic matches). Metadata results appear first to guarantee the
     * named symbol is included in the final context.
     */
    private List<Document> merge(List<Document> metadataResults, List<Document> vectorResults) {
        List<Document> merged = new ArrayList<>();
        merged.addAll(metadataResults);
        merged.addAll(vectorResults);
        return merged;
    }

    /**
     * Removes duplicate documents using a composite key derived from metadata.
     * Preserves insertion order (metadata results first, then vector results).
     */
    private List<Document> deduplicate(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }

        Set<String> seenKeys = new HashSet<>();
        List<Document> deduplicated = new ArrayList<>();

        for (Document doc : documents) {
            Map<String, Object> meta = doc.getMetadata();
            String repoId = String.valueOf(meta.getOrDefault("repositoryId", "default"));
            String filePath = String.valueOf(meta.getOrDefault("filePath", ""));
            String startLine = String.valueOf(meta.getOrDefault("startLine", "0"));
            String endLine = String.valueOf(meta.getOrDefault("endLine", "0"));
            String type = String.valueOf(meta.getOrDefault("type", ""));

            String uniqueKey = String.format("%s:%s:%s:%s:%s", repoId, filePath, startLine, endLine, type);

            if (seenKeys.add(uniqueKey)) {
                deduplicated.add(doc);
            }
        }

        return deduplicated;
    }

    @Override
    public List<Document> retrieve(String query, int topK) {
        return retrieve(query, topK, null);
    }

    @Override
    public List<Document> retrieve(String query) {
        return retrieve(query, 5, null);
    }
}
