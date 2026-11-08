package com.shravan.jcode_intelligence.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.shravan.jcode_intelligence.config.IntentRetrievalConfig;
import com.shravan.jcode_intelligence.model.QueryIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final ObjectReader metadataReader;

    /**
     * Constructs a {@code RetrievalStrategySelector} with the given dependencies and an optional
     * shared {@link ObjectMapper}. When provided, an immutable, thread-safe {@link ObjectReader}
     * is pre-configured to avoid per-row allocations during metadata deserialization.
     *
     * @param vectorStore                underlying vector store for semantic similarity lookups
     * @param jdbcTemplate               JDBC template for direct metadata SQL queries
     * @param symbolExtractor            extractor for isolating method and class symbols
     * @param config                     intent retrieval and similarity threshold configuration
     * @param architectureContextBuilder builder for architectural context
     * @param objectMapper               shared Jackson {@link ObjectMapper} (optional, defaults to a new instance)
     */
    @Autowired
    public RetrievalStrategySelector(VectorStore vectorStore,
                                      JdbcTemplate jdbcTemplate,
                                      SymbolExtractor symbolExtractor,
                                      IntentRetrievalConfig config,
                                      ArchitectureContextBuilder architectureContextBuilder,
                                      @Autowired(required = false) ObjectMapper objectMapper) {
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
        this.symbolExtractor = symbolExtractor;
        this.config = config;
        this.architectureContextBuilder = architectureContextBuilder;
        ObjectMapper mapper = (objectMapper != null) ? objectMapper : new ObjectMapper();
        this.metadataReader = mapper.readerFor(Map.class);
    }

    /**
     * Backward-compatible constructor for testing and callers without an injected {@link ObjectMapper}.
     * Initializes a default Jackson {@link ObjectReader} for thread-safe metadata parsing.
     *
     * @param vectorStore                underlying vector store for semantic similarity lookups
     * @param jdbcTemplate               JDBC template for direct metadata SQL queries
     * @param symbolExtractor            extractor for isolating method and class symbols
     * @param config                     intent retrieval and similarity threshold configuration
     * @param architectureContextBuilder builder for architectural context
     */
    public RetrievalStrategySelector(VectorStore vectorStore,
                                      JdbcTemplate jdbcTemplate,
                                      SymbolExtractor symbolExtractor,
                                      IntentRetrievalConfig config,
                                      ArchitectureContextBuilder architectureContextBuilder) {
        this(vectorStore, jdbcTemplate, symbolExtractor, config, architectureContextBuilder, null);
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
                sql = "SELECT content, metadata FROM vector_store " +
                      "WHERE (metadata->>'className' = ? OR metadata->>'elementName' = ? OR metadata->>'methodName' = ? OR metadata->>'elementName' LIKE ?) " +
                      "AND metadata->>'repositoryId' = ?";
                params = new Object[]{symbol, symbol, symbol, symbol + "#%", repositoryId};
            } else {
                sql = "SELECT content, metadata FROM vector_store " +
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
                sql = "SELECT content, metadata FROM vector_store " +
                      "WHERE (metadata->>'elementName' = ? OR metadata->>'methodName' = ? OR metadata->>'elementName' LIKE ?) " +
                      "AND metadata->>'repositoryId' = ?";
                params = new Object[]{methodName, methodName, methodName + "#%", repositoryId};
            } else {
                sql = "SELECT content, metadata FROM vector_store " +
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
                sql = "SELECT content, metadata FROM vector_store " +
                      "WHERE metadata->>'type' = ? AND metadata->>'repositoryId' = ?";
                params = new Object[]{type, repositoryId};
            } else {
                sql = "SELECT content, metadata FROM vector_store WHERE metadata->>'type' = ?";
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
                sql = "SELECT content, metadata FROM vector_store " +
                      "WHERE metadata->>'type' = 'CLASS' AND (metadata->>'nestingDepth' = '0' OR metadata->>'nestingDepth' IS NULL) " +
                      "AND metadata->>'repositoryId' = ?";
                params = new Object[]{repositoryId};
            } else {
                sql = "SELECT content, metadata FROM vector_store " +
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
                sql = "SELECT content, metadata FROM vector_store " +
                      "WHERE metadata->>'type' = 'PACKAGE' AND metadata->>'packageName' = ? AND metadata->>'repositoryId' = ?";
                params = new Object[]{packageName, repositoryId};
            } else {
                sql = "SELECT content, metadata FROM vector_store " +
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
                sql = "SELECT content, metadata FROM vector_store " +
                      "WHERE metadata->>'signature' ILIKE ? AND metadata->>'repositoryId' = ?";
                params = new Object[]{"%" + symbol + "%", repositoryId};
            } else {
                sql = "SELECT content, metadata FROM vector_store " +
                      "WHERE metadata->>'signature' ILIKE ?";
                params = new Object[]{"%" + symbol + "%"};
            }

            return executeQuery(sql, params);
        } catch (Exception e) {
            log.error("Signature lookup failed for symbol '{}': {}", symbol, e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * Look up parent chunks by a collection of chunk IDs in a single batch query.
     * Executes an SQL {@code IN (...)} query to prevent N+1 database round-trips.
     *
     * @param chunkIds     collection of unique parent chunk IDs
     * @param repositoryId target repository ID (optional, nullable)
     * @return retrieved parent documents
     */
    private List<Document> lookupByChunkIds(Collection<String> chunkIds, String repositoryId) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return List.of();
        }
        try {
            String placeholders = String.join(",", Collections.nCopies(chunkIds.size(), "?"));
            String sql;
            Object[] params;

            if (repositoryId != null && !repositoryId.isBlank()) {
                sql = "SELECT content, metadata FROM vector_store WHERE metadata->>'chunkId' IN (" + placeholders + ") AND metadata->>'repositoryId' = ?";
                params = new Object[chunkIds.size() + 1];
                int i = 0;
                for (String chunkId : chunkIds) {
                    params[i++] = chunkId;
                }
                params[i] = repositoryId;
            } else {
                sql = "SELECT content, metadata FROM vector_store WHERE metadata->>'chunkId' IN (" + placeholders + ")";
                params = chunkIds.toArray(new Object[0]);
            }

            return executeQuery(sql, params);
        } catch (Exception e) {
            log.warn("ChunkIds batch lookup failed for {} chunk(s): {}", chunkIds.size(), e.getMessage());
            return List.of();
        }
    }

    /**
     * Look up a single chunk by chunk ID, delegating to the batched {@link #lookupByChunkIds(Collection, String)}.
     *
     * @param chunkId      chunk identifier
     * @param repositoryId target repository ID (optional, nullable)
     * @return retrieved documents
     */
    private List<Document> lookupByChunkId(String chunkId, String repositoryId) {
        if (chunkId == null || chunkId.isBlank()) {
            return List.of();
        }
        return lookupByChunkIds(List.of(chunkId), repositoryId);
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

    /**
     * Deserializes a metadata JSON string using the shared, thread-safe {@link ObjectReader}.
     * Returns an empty map on null, blank, or malformed inputs without throwing exceptions.
     *
     * @param json raw metadata JSON string
     * @return deserialized metadata map
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMetadataJson(String json) {
        if (json == null || json.isBlank()) {
            return new HashMap<>();
        }
        try {
            Map<String, Object> result = metadataReader.readValue(json);
            return (result != null) ? result : new HashMap<>();
        } catch (Exception e) {
            log.warn("Failed to parse metadata JSON: {}", e.getMessage());
            return new HashMap<>();
        }
    }

    // ── Context Enrichment ────────────────────────────────────

    /**
     * Enriches retrieved member documents (methods, constructors, fields) with enclosing class summaries.
     * Performs a single batch query for all distinct unenriched parent chunk IDs to eliminate N+1 round-trips,
     * short-circuiting to zero database queries if no eligible parent chunks exist.
     *
     * @param documents    retrieved candidate documents
     * @param repositoryId target repository ID (optional, nullable)
     * @return documents prepended with newly discovered parent summaries
     */
    private List<Document> enrichWithParentContext(List<Document> documents, String repositoryId) {
        if (documents == null || documents.isEmpty()) {
            return documents;
        }

        Set<String> existingKeys = new HashSet<>();
        for (Document doc : documents) {
            existingKeys.add(getDeduplicationKey(doc));
        }

        Set<String> candidateParentIds = new LinkedHashSet<>();
        for (Document doc : documents) {
            Map<String, Object> meta = doc.getMetadata();
            String type = String.valueOf(meta.getOrDefault("type", ""));
            String parentChunkId = meta.get("parentChunkId") != null
                    ? String.valueOf(meta.get("parentChunkId")).trim() : null;

            if (parentChunkId != null && !parentChunkId.isEmpty() && isMemberType(type)) {
                candidateParentIds.add(parentChunkId);
            }
        }

        if (candidateParentIds.isEmpty()) {
            return documents;
        }

        List<Document> fetchedParents = lookupByChunkIds(candidateParentIds, repositoryId);
        if (fetchedParents.isEmpty()) {
            return documents;
        }

        Map<String, List<Document>> parentsByChunkId = new HashMap<>();
        for (Document parent : fetchedParents) {
            Object cId = parent.getMetadata() != null ? parent.getMetadata().get("chunkId") : null;
            if (cId != null) {
                parentsByChunkId.computeIfAbsent(String.valueOf(cId), k -> new ArrayList<>()).add(parent);
            }
        }

        List<Document> parentDocuments = new ArrayList<>();
        for (String parentId : candidateParentIds) {
            List<Document> parents = parentsByChunkId.get(parentId);
            if (parents != null) {
                for (Document parent : parents) {
                    String parentKey = getDeduplicationKey(parent);
                    if (existingKeys.add(parentKey)) {
                        parentDocuments.add(parent);
                    }
                }
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
