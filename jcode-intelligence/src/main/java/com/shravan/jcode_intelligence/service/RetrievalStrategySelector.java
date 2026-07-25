package com.shravan.jcode_intelligence.service;

import com.shravan.jcode_intelligence.config.IntentRetrievalConfig;
import com.shravan.jcode_intelligence.model.QueryIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Selects and executes the retrieval strategy for a given {@link QueryIntent}.
 *
 * <p>Each intent maps to a dedicated retrieval method that orchestrates:
 * <ul>
 *   <li>Metadata SQL lookups (exact symbol, type, package)</li>
 *   <li>Vector similarity search (cosine distance via PgVector)</li>
 *   <li>Context enrichment (parent class summaries)</li>
 * </ul>
 *
 * <p>This component is the second stage of the retrieval pipeline:
 * <pre>
 *   QueryIntentClassifier  →  <b>RetrievalStrategySelector</b>  →  RetrievalReranker  →  PromptBuilder
 * </pre>
 */
@Component
public class RetrievalStrategySelector {

    private static final Logger log = LoggerFactory.getLogger(RetrievalStrategySelector.class);

    private static final List<String> ARCHITECTURE_CORE_CLASSES = List.of(
            "IndexingServiceImpl", "RetrievalServiceImpl", "JavaProjectParser",
            "AstVisitor", "ChunkGenerator", "ClassSummaryBuilder",
            "MethodFragmenter", "PackageSummaryGenerator", "DocumentConverter",
            "PromptBuilder", "LLMClient", "ChatServiceImpl", "VectorStore",
            "SymbolExtractor"
    );

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;
    private final SymbolExtractor symbolExtractor;
    private final IntentRetrievalConfig config;
    private final ArchitectureContextBuilder architectureContextBuilder;

    public RetrievalStrategySelector(VectorStore vectorStore,
                                      JdbcTemplate jdbcTemplate,
                                      SymbolExtractor symbolExtractor,
                                      IntentRetrievalConfig config,
                                      ArchitectureContextBuilder architectureContextBuilder) {
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
        this.symbolExtractor = symbolExtractor;
        this.config = config;
        this.architectureContextBuilder = architectureContextBuilder;
    }

    /**
     * Executes the retrieval strategy for the given intent.
     *
     * @param query        the user query
     * @param intent       the classified query intent
     * @param repositoryId the target repository (nullable)
     * @return raw retrieved documents (not yet reranked)
     */
    public List<Document> retrieve(String query, QueryIntent intent, String repositoryId) {
        int rawTopK = config.getRawTopK(intent);

        return switch (intent) {
            case METHOD -> retrieveForMethod(query, rawTopK, repositoryId);
            case CLASS -> retrieveForClass(query, rawTopK, repositoryId);
            case PACKAGE -> retrieveForPackage(query, rawTopK, repositoryId);
            case ARCHITECTURE -> retrieveForArchitecture(query, rawTopK, repositoryId);
            case WORKFLOW -> retrieveForWorkflow(query, rawTopK, repositoryId);
            case RELATIONSHIP -> retrieveForRelationship(query, rawTopK, repositoryId);
            case SEARCH -> retrieveForSearch(query, rawTopK, repositoryId);
            case DESIGN -> retrieveForDesign(query, rawTopK, repositoryId);
            case UNKNOWN -> retrieveForUnknown(query, rawTopK, repositoryId);
        };
    }

    // ── Intent-Specific Retrieval Strategies ──────────────────

    /**
     * METHOD: Prioritize METHOD + METHOD_FRAGMENT chunks.
     * 1. Exact method symbol lookup
     * 2. Vector search
     * 3. Enrich with parent class summaries
     */
    private List<Document> retrieveForMethod(String query, int rawTopK, String repositoryId) {
        List<Document> results = new ArrayList<>();

        Optional<String> symbol = symbolExtractor.extract(query);
        if (symbol.isPresent()) {
            List<Document> exact = lookupMethodBySymbol(symbol.get(), repositoryId);
            log.info("METHOD strategy: exact lookup for '{}' returned {} doc(s)", symbol.get(), exact.size());
            results.addAll(exact);
        }

        List<Document> vectorDocs = performVectorSearch(query, rawTopK, repositoryId);
        results.addAll(vectorDocs);

        return enrichWithParentContext(deduplicate(results), repositoryId);
    }

    /**
     * CLASS: Prioritize CLASS + INTERFACE chunks.
     * 1. Exact class name lookup
     * 2. Parent package summary
     * 3. Vector search
     * 4. Enrich with parent context
     */
    private List<Document> retrieveForClass(String query, int rawTopK, String repositoryId) {
        List<Document> results = new ArrayList<>();

        Optional<String> symbol = symbolExtractor.extract(query);
        if (symbol.isPresent()) {
            String className = symbol.get();
            List<Document> exact = lookupByMetadata(className, repositoryId);
            results.addAll(exact);

            // Fetch parent package summary
            if (!exact.isEmpty()) {
                String pkg = String.valueOf(exact.get(0).getMetadata().getOrDefault("packageName", ""));
                if (!pkg.isBlank()) {
                    results.addAll(lookupByPackageName(pkg, repositoryId));
                }
            }
        }

        List<Document> vectorDocs = performVectorSearch(query, rawTopK, repositoryId);
        results.addAll(vectorDocs);

        return enrichWithParentContext(deduplicate(results), repositoryId);
    }

    /**
     * PACKAGE: Retrieve ALL PACKAGE chunks for the repository + top CLASS summaries.
     * Do NOT rely solely on vectors for package overview.
     */
    private List<Document> retrieveForPackage(String query, int rawTopK, String repositoryId) {
        List<Document> results = new ArrayList<>();

        // 1. ALL package summaries for the repository
        results.addAll(lookupByType("PACKAGE", repositoryId));

        // 2. Top-level class summaries
        results.addAll(lookupTopLevelClasses(repositoryId));

        // 3. Interfaces and records
        results.addAll(lookupByType("INTERFACE", repositoryId));
        results.addAll(lookupByType("RECORD", repositoryId));

        return deduplicate(results);
    }

    /**
     * ARCHITECTURE: Repository-centric structural retrieval pipeline.
     * Uses ArchitectureContextBuilder to extract all packages, component roles,
     * architectural interfaces, orchestration methods, and dependency graph.
     */
    private List<Document> retrieveForArchitecture(String query, int rawTopK, String repositoryId) {
        com.shravan.jcode_intelligence.model.ArchitectureContext context =
                architectureContextBuilder.buildContext(repositoryId);

        List<Document> structuralDocs = context.toFlatDocumentList();

        // Supplementary vector search for specific terms in user query (e.g. "security", "jwt", "kafka")
        if (query != null && !query.isBlank()) {
            List<Document> vectorDocs = performVectorSearch(query, Math.min(rawTopK, 5), repositoryId);
            for (Document vdoc : vectorDocs) {
                String type = String.valueOf(vdoc.getMetadata().getOrDefault("type", ""));
                if (!"FIELD".equalsIgnoreCase(type)) { // Zero fields!
                    structuralDocs.add(vdoc);
                }
            }
        }

        return deduplicate(structuralDocs);
    }

    /**
     * WORKFLOW: Broader context — METHODS + CLASSES + PACKAGE summaries.
     * Connects multiple components for end-to-end flow explanation.
     */
    private List<Document> retrieveForWorkflow(String query, int rawTopK, String repositoryId) {
        List<Document> results = new ArrayList<>();

        // 1. Package summaries for context
        results.addAll(lookupByType("PACKAGE", repositoryId));

        // 2. Symbol lookup if present
        Optional<String> symbol = symbolExtractor.extract(query);
        if (symbol.isPresent()) {
            results.addAll(lookupByMetadata(symbol.get(), repositoryId));
        }

        // 3. Broader vector search (workflow needs more diverse results)
        List<Document> vectorDocs = performVectorSearch(query, rawTopK, repositoryId);
        results.addAll(vectorDocs);

        return enrichWithParentContext(deduplicate(results), repositoryId);
    }

    /**
     * RELATIONSHIP: CLASS + METHOD + INTERFACE with wider Top-K.
     * Focus on metadata relationships.
     */
    private List<Document> retrieveForRelationship(String query, int rawTopK, String repositoryId) {
        List<Document> results = new ArrayList<>();

        Optional<String> symbol = symbolExtractor.extract(query);
        if (symbol.isPresent()) {
            List<Document> exact = lookupByMetadata(symbol.get(), repositoryId);
            results.addAll(exact);
            log.info("RELATIONSHIP strategy: symbol '{}' returned {} doc(s)", symbol.get(), exact.size());
        }

        // Wider vector search for relationship context
        List<Document> vectorDocs = performVectorSearch(query, rawTopK, repositoryId);
        results.addAll(vectorDocs);

        // Interfaces for relationship context
        results.addAll(lookupByType("INTERFACE", repositoryId));

        return enrichWithParentContext(deduplicate(results), repositoryId);
    }

    /**
     * SEARCH: Metadata-first strategy.
     * 1. Exact match on className, methodName, elementName, signature
     * 2. Signature partial match (ILIKE)
     * 3. Vector search as fallback
     */
    private List<Document> retrieveForSearch(String query, int rawTopK, String repositoryId) {
        List<Document> results = new ArrayList<>();

        Optional<String> symbol = symbolExtractor.extract(query);
        if (symbol.isPresent()) {
            String sym = symbol.get();

            // 1. Exact metadata match
            List<Document> exact = lookupByMetadata(sym, repositoryId);
            results.addAll(exact);

            // 2. Signature partial match
            List<Document> sigMatches = lookupBySignature(sym, repositoryId);
            results.addAll(sigMatches);

            log.info("SEARCH strategy: symbol '{}' — exact={}, signature={}", sym, exact.size(), sigMatches.size());
        }

        // 3. Vector fallback
        if (results.isEmpty()) {
            List<Document> vectorDocs = performVectorSearch(query, rawTopK, repositoryId);
            results.addAll(vectorDocs);
        }

        return enrichWithParentContext(deduplicate(results), repositoryId);
    }

    /**
     * DESIGN: CLASS + PACKAGE + INTERFACE. Avoids FIELD chunks.
     */
    private List<Document> retrieveForDesign(String query, int rawTopK, String repositoryId) {
        List<Document> results = new ArrayList<>();

        // 1. Package summaries
        results.addAll(lookupByType("PACKAGE", repositoryId));

        // 2. Interfaces (design patterns, abstractions)
        results.addAll(lookupByType("INTERFACE", repositoryId));

        // 3. Symbol lookup
        Optional<String> symbol = symbolExtractor.extract(query);
        if (symbol.isPresent()) {
            results.addAll(lookupByMetadata(symbol.get(), repositoryId));
        }

        // 4. Vector search
        List<Document> vectorDocs = performVectorSearch(query, rawTopK, repositoryId);
        results.addAll(vectorDocs);

        return deduplicate(results);
    }

    /**
     * UNKNOWN: Generic hybrid retrieval (metadata + vector).
     */
    private List<Document> retrieveForUnknown(String query, int rawTopK, String repositoryId) {
        List<Document> results = new ArrayList<>();

        Optional<String> symbol = symbolExtractor.extract(query);
        if (symbol.isPresent()) {
            List<Document> metadataResults = lookupByMetadata(symbol.get(), repositoryId);
            results.addAll(metadataResults);
            log.info("UNKNOWN strategy: symbol '{}' metadata lookup returned {} doc(s)", symbol.get(), metadataResults.size());
        }

        List<Document> vectorDocs = performVectorSearch(query, rawTopK, repositoryId);
        results.addAll(vectorDocs);

        return enrichWithParentContext(deduplicate(results), repositoryId);
    }

    // ── Vector Search ─────────────────────────────────────────

    private List<Document> performVectorSearch(String query, int limit, String repositoryId) {
        try {
            SearchRequest.Builder builder = SearchRequest.builder()
                    .query(query)
                    .topK(limit)
                    .similarityThreshold(config.getSimilarityThreshold());

            if (repositoryId != null && !repositoryId.isBlank()) {
                builder.filterExpression("repositoryId == '" + repositoryId + "'");
            }

            return vectorStore.similaritySearch(builder.build());
        } catch (Exception e) {
            log.warn("Vector similarity search failed for query '{}': {}", query, e.getMessage());
            return List.of();
        }
    }

    // ── SQL Metadata Lookups ──────────────────────────────────

    private List<Document> lookupByMetadata(String symbol, String repositoryId) {
        try {
            String sql;
            Object[] params;

            if (repositoryId != null && !repositoryId.isBlank()) {
                sql = "SELECT id, content, metadata FROM vector_store " +
                      "WHERE (metadata->>'className' = ? OR metadata->>'elementName' = ? OR metadata->>'methodName' = ? OR metadata->>'elementName' LIKE ?) " +
                      "AND metadata->>'repositoryId' = ?";
                params = new Object[]{symbol, symbol, symbol, symbol + "#%", repositoryId};
            } else {
                sql = "SELECT id, content, metadata FROM vector_store " +
                      "WHERE metadata->>'className' = ? OR metadata->>'elementName' = ? OR metadata->>'methodName' = ? OR metadata->>'elementName' LIKE ?";
                params = new Object[]{symbol, symbol, symbol, symbol + "#%"};
            }

            return executeQuery(sql, params);
        } catch (Exception e) {
            log.error("Metadata lookup failed for symbol '{}': {}", symbol, e.getMessage(), e);
            return List.of();
        }
    }

    private List<Document> lookupMethodBySymbol(String methodName, String repositoryId) {
        try {
            String sql;
            Object[] params;

            if (repositoryId != null && !repositoryId.isBlank()) {
                sql = "SELECT id, content, metadata FROM vector_store " +
                      "WHERE (metadata->>'elementName' = ? OR metadata->>'methodName' = ? OR metadata->>'elementName' LIKE ?) " +
                      "AND metadata->>'repositoryId' = ?";
                params = new Object[]{methodName, methodName, methodName + "#%", repositoryId};
            } else {
                sql = "SELECT id, content, metadata FROM vector_store " +
                      "WHERE metadata->>'elementName' = ? OR metadata->>'methodName' = ? OR metadata->>'elementName' LIKE ?";
                params = new Object[]{methodName, methodName, methodName + "#%"};
            }

            return executeQuery(sql, params);
        } catch (Exception e) {
            log.error("Method lookup failed for symbol '{}': {}", methodName, e.getMessage(), e);
            return List.of();
        }
    }

    private List<Document> lookupByType(String type, String repositoryId) {
        try {
            String sql;
            Object[] params;

            if (repositoryId != null && !repositoryId.isBlank()) {
                sql = "SELECT id, content, metadata FROM vector_store " +
                      "WHERE metadata->>'type' = ? AND metadata->>'repositoryId' = ?";
                params = new Object[]{type, repositoryId};
            } else {
                sql = "SELECT id, content, metadata FROM vector_store WHERE metadata->>'type' = ?";
                params = new Object[]{type};
            }

            return executeQuery(sql, params);
        } catch (Exception e) {
            log.error("Type lookup failed for type '{}': {}", type, e.getMessage(), e);
            return List.of();
        }
    }

    private List<Document> lookupTopLevelClasses(String repositoryId) {
        try {
            String sql;
            Object[] params;

            if (repositoryId != null && !repositoryId.isBlank()) {
                sql = "SELECT id, content, metadata FROM vector_store " +
                      "WHERE metadata->>'type' = 'CLASS' AND (metadata->>'nestingDepth' = '0' OR metadata->>'nestingDepth' IS NULL) " +
                      "AND metadata->>'repositoryId' = ?";
                params = new Object[]{repositoryId};
            } else {
                sql = "SELECT id, content, metadata FROM vector_store " +
                      "WHERE metadata->>'type' = 'CLASS' AND (metadata->>'nestingDepth' = '0' OR metadata->>'nestingDepth' IS NULL)";
                params = new Object[]{};
            }

            return executeQuery(sql, params);
        } catch (Exception e) {
            log.error("Top level class lookup failed: {}", e.getMessage(), e);
            return List.of();
        }
    }

    private List<Document> lookupByPackageName(String packageName, String repositoryId) {
        try {
            String sql;
            Object[] params;

            if (repositoryId != null && !repositoryId.isBlank()) {
                sql = "SELECT id, content, metadata FROM vector_store " +
                      "WHERE metadata->>'type' = 'PACKAGE' AND metadata->>'packageName' = ? AND metadata->>'repositoryId' = ?";
                params = new Object[]{packageName, repositoryId};
            } else {
                sql = "SELECT id, content, metadata FROM vector_store " +
                      "WHERE metadata->>'type' = 'PACKAGE' AND metadata->>'packageName' = ?";
                params = new Object[]{packageName};
            }

            return executeQuery(sql, params);
        } catch (Exception e) {
            log.error("Package lookup failed for package '{}': {}", packageName, e.getMessage(), e);
            return List.of();
        }
    }

    private List<Document> lookupBySignature(String symbol, String repositoryId) {
        try {
            String sql;
            Object[] params;

            if (repositoryId != null && !repositoryId.isBlank()) {
                sql = "SELECT id, content, metadata FROM vector_store " +
                      "WHERE metadata->>'signature' ILIKE ? AND metadata->>'repositoryId' = ?";
                params = new Object[]{"%" + symbol + "%", repositoryId};
            } else {
                sql = "SELECT id, content, metadata FROM vector_store " +
                      "WHERE metadata->>'signature' ILIKE ?";
                params = new Object[]{"%" + symbol + "%"};
            }

            return executeQuery(sql, params);
        } catch (Exception e) {
            log.error("Signature lookup failed for symbol '{}': {}", symbol, e.getMessage(), e);
            return List.of();
        }
    }

    private List<Document> lookupByChunkId(String chunkId, String repositoryId) {
        try {
            String sql;
            Object[] params;

            if (repositoryId != null && !repositoryId.isBlank()) {
                sql = "SELECT id, content, metadata FROM vector_store WHERE metadata->>'chunkId' = ? AND metadata->>'repositoryId' = ?";
                params = new Object[]{chunkId, repositoryId};
            } else {
                sql = "SELECT id, content, metadata FROM vector_store WHERE metadata->>'chunkId' = ?";
                params = new Object[]{chunkId};
            }

            return executeQuery(sql, params);
        } catch (Exception e) {
            log.warn("ChunkId lookup failed for chunkId '{}': {}", chunkId, e.getMessage());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Document> executeQuery(String sql, Object[] params) {
        return jdbcTemplate.query(sql, params, (rs, rowNum) -> {
            String content = rs.getString("content");
            String metadataJson = rs.getString("metadata");
            Map<String, Object> metadata = parseMetadataJson(metadataJson);
            return new Document(content, metadata);
        });
    }

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

    // ── Context Enrichment ────────────────────────────────────

    private List<Document> enrichWithParentContext(List<Document> documents, String repositoryId) {
        if (documents == null || documents.isEmpty()) {
            return documents;
        }

        Set<String> existingKeys = new HashSet<>();
        for (Document doc : documents) {
            existingKeys.add(getDeduplicationKey(doc));
        }

        List<Document> parentDocuments = new ArrayList<>();
        Set<String> fetchedParentIds = new HashSet<>();

        for (Document doc : documents) {
            Map<String, Object> meta = doc.getMetadata();
            String type = String.valueOf(meta.getOrDefault("type", ""));
            String parentChunkId = meta.get("parentChunkId") != null
                    ? String.valueOf(meta.get("parentChunkId")) : null;

            if (parentChunkId != null && !parentChunkId.isBlank()
                    && !fetchedParentIds.contains(parentChunkId)
                    && isMemberType(type)) {

                List<Document> parents = lookupByChunkId(parentChunkId, repositoryId);
                for (Document parent : parents) {
                    String parentKey = getDeduplicationKey(parent);
                    if (existingKeys.add(parentKey)) {
                        parentDocuments.add(parent);
                    }
                }
                fetchedParentIds.add(parentChunkId);
            }
        }

        if (!parentDocuments.isEmpty()) {
            log.info("Enriched retrieval with {} parent class summary document(s)", parentDocuments.size());
            List<Document> enriched = new ArrayList<>(parentDocuments);
            enriched.addAll(documents);
            return enriched;
        }

        return documents;
    }

    // ── Helpers ───────────────────────────────────────────────

    private boolean isMemberType(String type) {
        return "METHOD".equals(type) || "CONSTRUCTOR".equals(type)
                || "FIELD".equals(type) || "METHOD_FRAGMENT".equals(type);
    }

    private List<Document> deduplicate(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }

        Set<String> seenKeys = new HashSet<>();
        List<Document> deduplicated = new ArrayList<>();

        for (Document doc : documents) {
            String uniqueKey = getDeduplicationKey(doc);
            if (seenKeys.add(uniqueKey)) {
                deduplicated.add(doc);
            }
        }

        return deduplicated;
    }

    private String getDeduplicationKey(Document doc) {
        Map<String, Object> meta = doc.getMetadata();
        String repoId = String.valueOf(meta.getOrDefault("repositoryId", "default"));
        String filePath = String.valueOf(meta.getOrDefault("filePath", ""));
        String startLine = String.valueOf(meta.getOrDefault("startLine", "0"));
        String endLine = String.valueOf(meta.getOrDefault("endLine", "0"));
        String type = String.valueOf(meta.getOrDefault("type", ""));

        return String.format("%s:%s:%s:%s:%s", repoId, filePath, startLine, endLine, type);
    }
}
