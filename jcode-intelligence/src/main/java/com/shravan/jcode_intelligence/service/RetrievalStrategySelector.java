package com.shravan.jcode_intelligence.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.shravan.jcode_intelligence.config.IntentRetrievalConfig;
import com.shravan.jcode_intelligence.config.RetrievalConfig;
import com.shravan.jcode_intelligence.model.QueryIntent;
import com.shravan.jcode_intelligence.model.RetrievalStrategy;
import com.shravan.jcode_intelligence.sparse.fusion.ReciprocalRankFusion;
import com.shravan.jcode_intelligence.sparse.store.LuceneGenerationStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private final Executor retrievalExecutor;
    private final LuceneGenerationStore luceneStore;
    private final ReciprocalRankFusion rrf;
    private final RetrievalConfig retrievalConfig;

    /**
     * Constructs a {@code RetrievalStrategySelector} with full dependencies, including
     * optional Lucene generation store and Reciprocal Rank Fusion components.
     *
     * @param vectorStore                underlying vector store for semantic similarity lookups
     * @param jdbcTemplate               JDBC template for direct metadata SQL queries
     * @param symbolExtractor            extractor for isolating method and class symbols
     * @param config                     intent retrieval and similarity threshold configuration
     * @param architectureContextBuilder builder for architectural context
     * @param objectMapper               shared Jackson {@link ObjectMapper} (optional, defaults to a new instance)
     * @param retrievalExecutor          executor for concurrent sub-retrievals (optional, defaults to bounded pool)
     * @param luceneStore                Lucene sparse generation store for BM25 retrieval (optional)
     * @param rrf                        Reciprocal Rank Fusion component for hybrid merging (optional)
     * @param retrievalConfig            sparse and retrieval configuration (optional)
     */
    @Autowired
    public RetrievalStrategySelector(VectorStore vectorStore,
                                      JdbcTemplate jdbcTemplate,
                                      SymbolExtractor symbolExtractor,
                                      IntentRetrievalConfig config,
                                      ArchitectureContextBuilder architectureContextBuilder,
                                      @Autowired(required = false) ObjectMapper objectMapper,
                                      @Autowired(required = false) @Qualifier("retrievalExecutor") Executor retrievalExecutor,
                                      @Autowired(required = false) LuceneGenerationStore luceneStore,
                                      @Autowired(required = false) ReciprocalRankFusion rrf,
                                      @Autowired(required = false) RetrievalConfig retrievalConfig) {
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
        this.symbolExtractor = symbolExtractor;
        this.config = config;
        this.architectureContextBuilder = architectureContextBuilder;
        ObjectMapper mapper = (objectMapper != null) ? objectMapper : new ObjectMapper();
        this.metadataReader = mapper.readerFor(Map.class);
        this.retrievalConfig = (retrievalConfig != null) ? retrievalConfig : new RetrievalConfig();
        int corePoolSize = this.retrievalConfig.getExecutorCorePoolSize();
        this.retrievalExecutor = (retrievalExecutor != null) ? retrievalExecutor : createDefaultExecutor(corePoolSize);
        this.luceneStore = luceneStore;
        this.rrf = (rrf != null) ? rrf : new ReciprocalRankFusion(this.retrievalConfig);
    }

    /**
     * Overload accepting custom executor and mapper without sparse components.
     */
    public RetrievalStrategySelector(VectorStore vectorStore,
                                      JdbcTemplate jdbcTemplate,
                                      SymbolExtractor symbolExtractor,
                                      IntentRetrievalConfig config,
                                      ArchitectureContextBuilder architectureContextBuilder,
                                      ObjectMapper objectMapper,
                                      Executor retrievalExecutor) {
        this(vectorStore, jdbcTemplate, symbolExtractor, config, architectureContextBuilder, objectMapper, retrievalExecutor, null, null, null);
    }

    /**
     * Overload accepting an optional {@link ObjectMapper} without a custom executor.
     */
    public RetrievalStrategySelector(VectorStore vectorStore,
                                      JdbcTemplate jdbcTemplate,
                                      SymbolExtractor symbolExtractor,
                                      IntentRetrievalConfig config,
                                      ArchitectureContextBuilder architectureContextBuilder,
                                      ObjectMapper objectMapper) {
        this(vectorStore, jdbcTemplate, symbolExtractor, config, architectureContextBuilder, objectMapper, null, null, null, null);
    }

    /**
     * Backward-compatible constructor for testing and callers without an injected {@link ObjectMapper}.
     */
    public RetrievalStrategySelector(VectorStore vectorStore,
                                      JdbcTemplate jdbcTemplate,
                                      SymbolExtractor symbolExtractor,
                                      IntentRetrievalConfig config,
                                      ArchitectureContextBuilder architectureContextBuilder) {
        this(vectorStore, jdbcTemplate, symbolExtractor, config, architectureContextBuilder, null, null, null, null, null);
    }

    private static Executor createDefaultExecutor(int corePoolSize) {
        int core = (corePoolSize > 0) ? corePoolSize : 4;
        return new ThreadPoolExecutor(
                core, Math.max(core * 2, 8), 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(100),
                r -> {
                    Thread t = new Thread(r, "retrieval-worker");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    private static Executor createDefaultExecutor() {
        return createDefaultExecutor(4);
    }

    /**
     * Executes the retrieval strategy for the given intent using the default configured strategy.
     *
     * @param query        the user query
     * @param intent       the classified query intent
     * @param repositoryId the target repository (nullable)
     * @return raw retrieved documents (not yet reranked)
     */
    public List<Document> retrieve(String query, QueryIntent intent, String repositoryId) {
        return retrieve(query, intent, repositoryId, null);
    }

    /**
     * Executes the retrieval strategy for the given intent and explicit strategy.
     *
     * @param query        the user query
     * @param intent       the classified query intent
     * @param repositoryId the target repository (nullable)
     * @param strategy     retrieval strategy (DENSE, SPARSE, HYBRID; nullable to use default)
     * @return raw retrieved documents (not yet reranked)
     */
    public List<Document> retrieve(String query, QueryIntent intent, String repositoryId, RetrievalStrategy strategy) {
        int rawTopK = config.getRawTopK(intent);
        RetrievalStrategy effectiveStrategy = resolveStrategy(strategy);

        return switch (intent) {
            case METHOD -> retrieveForMethod(query, rawTopK, repositoryId, effectiveStrategy);
            case CLASS -> retrieveForClass(query, rawTopK, repositoryId, effectiveStrategy);
            case PACKAGE -> retrieveForPackage(query, rawTopK, repositoryId);
            case ARCHITECTURE -> retrieveForArchitecture(query, rawTopK, repositoryId, effectiveStrategy);
            case WORKFLOW -> retrieveForWorkflow(query, rawTopK, repositoryId, effectiveStrategy);
            case RELATIONSHIP -> retrieveForRelationship(query, rawTopK, repositoryId, effectiveStrategy);
            case SEARCH -> retrieveForSearch(query, rawTopK, repositoryId, effectiveStrategy);
            case DESIGN -> retrieveForDesign(query, rawTopK, repositoryId, effectiveStrategy);
            case UNKNOWN -> retrieveForUnknown(query, rawTopK, repositoryId, effectiveStrategy);
        };
    }

    /**
     * Resolves the effective retrieval strategy, falling back to configuration or HYBRID.
     */
    private RetrievalStrategy resolveStrategy(RetrievalStrategy strategy) {
        if (strategy != null) {
            return strategy;
        }
        if (retrievalConfig != null && retrievalConfig.getStrategy() != null) {
            return retrievalConfig.getStrategy();
        }
        return RetrievalStrategy.HYBRID;
    }

    /**
     * Asynchronously retrieves candidates according to the given strategy (DENSE, SPARSE, or HYBRID).
     * In HYBRID mode, dense and sparse branches run independently and combine via {@link CompletableFuture#thenCombine}.
     * Branch failures are handled independently, returning an empty list on failure without throwing or calling join()
     * within executor tasks.
     *
     * @param query        natural language query
     * @param rawTopK      number of raw candidates to fetch
     * @param repositoryId target repository ID (optional, nullable)
     * @param strategy     retrieval strategy
     * @param intent       query intent for fusion weights
     * @return future completed with retrieved candidate documents
     */
    public CompletableFuture<List<Document>> fetchCandidatesAsync(
            String query, int rawTopK, String repositoryId, RetrievalStrategy strategy, QueryIntent intent) {
        RetrievalStrategy effectiveStrategy = resolveStrategy(strategy);

        return switch (effectiveStrategy) {
            case DENSE -> CompletableFuture.supplyAsync(
                    () -> performVectorSearch(query, rawTopK, repositoryId),
                    retrievalExecutor
            ).exceptionally(ex -> {
                log.warn("Vector candidate search failed: {}", ex.getMessage());
                return List.of();
            });

            case SPARSE -> CompletableFuture.supplyAsync(
                    () -> performSparseSearch(query, rawTopK, repositoryId),
                    retrievalExecutor
            ).exceptionally(ex -> {
                log.warn("Sparse candidate search failed: {}", ex.getMessage());
                return List.of();
            });

            case HYBRID -> {
                CompletableFuture<List<Document>> denseFuture = CompletableFuture.supplyAsync(
                        () -> performVectorSearch(query, rawTopK, repositoryId),
                        retrievalExecutor
                ).exceptionally(ex -> {
                    log.warn("Dense branch failed in hybrid search: {}", ex.getMessage());
                    return List.of();
                });

                CompletableFuture<List<Document>> sparseFuture = CompletableFuture.supplyAsync(
                        () -> performSparseSearch(query, rawTopK, repositoryId),
                        retrievalExecutor
                ).exceptionally(ex -> {
                    log.warn("Sparse branch failed in hybrid search: {}", ex.getMessage());
                    return List.of();
                });

                yield denseFuture.thenCombine(sparseFuture, (denseDocs, sparseDocs) ->
                        rrf.fuse(denseDocs, sparseDocs, intent, rawTopK));
            }
        };
    }

    /**
     * Synchronously retrieves candidates according to the given strategy.
     *
     * @param query        natural language query
     * @param rawTopK      number of raw candidates to fetch
     * @param repositoryId target repository ID (optional, nullable)
     * @param strategy     retrieval strategy
     * @param intent       query intent for fusion weights
     * @return retrieved candidate documents
     */
    public List<Document> fetchCandidates(
            String query, int rawTopK, String repositoryId, RetrievalStrategy strategy, QueryIntent intent) {
        try {
            return fetchCandidatesAsync(query, rawTopK, repositoryId, strategy, intent).join();
        } catch (Exception e) {
            log.warn("Candidate retrieval failed for query '{}': {}", query, e.getMessage());
            return List.of();
        }
    }

    /**
     * Performs sparse BM25 search via LuceneGenerationStore if available and enabled.
     *
     * @param query        query string
     * @param limit        maximum documents to retrieve
     * @param repositoryId repository identifier (nullable for unscoped search)
     * @return sparse matching documents
     */
    private List<Document> performSparseSearch(String query, int limit, String repositoryId) {
        if (luceneStore == null || !retrievalConfig.isSparseEnabled()) {
            return List.of();
        }
        try {
            if (repositoryId != null && !repositoryId.isBlank()) {
                return luceneStore.search(repositoryId, query, limit);
            } else {
                return luceneStore.searchUnscoped(query, limit);
            }
        } catch (Exception e) {
            log.warn("Sparse search failed for query '{}': {}", query, e.getMessage());
            return List.of();
        }
    }

    // ── Intent-Specific Retrieval Strategies ──────────────────

    /**
     * METHOD: Prioritize METHOD + METHOD_FRAGMENT chunks.
     * 1. Exact method symbol lookup (concurrent with candidate search)
     * 2. Strategy-aware candidate search (concurrent with symbol lookup)
     * 3. Enrich with parent class summaries
     */
    private List<Document> retrieveForMethod(String query, int rawTopK, String repositoryId, RetrievalStrategy strategy) {
        Optional<String> symbol = symbolExtractor.extract(query);

        CompletableFuture<List<Document>> metadataFuture = CompletableFuture.supplyAsync(
                () -> symbol.map(s -> {
                    List<Document> exact = lookupMethodBySymbol(s, repositoryId);
                    log.info("METHOD strategy: exact lookup for '{}' returned {} doc(s)", s, exact.size());
                    return exact;
                }).orElseGet(List::of),
                retrievalExecutor
        );

        CompletableFuture<List<Document>> candidateFuture = fetchCandidatesAsync(
                query, rawTopK, repositoryId, strategy, QueryIntent.METHOD);

        List<Document> results = new ArrayList<>();
        try {
            results.addAll(metadataFuture.join());
        } catch (Exception e) {
            log.warn("Concurrent metadata lookup failed in METHOD strategy: {}", e.getMessage());
        }
        try {
            results.addAll(candidateFuture.join());
        } catch (Exception e) {
            log.warn("Concurrent candidate search failed in METHOD strategy: {}", e.getMessage());
        }

        return enrichWithParentContext(deduplicate(results), repositoryId);
    }

    /**
     * CLASS: Prioritize CLASS + INTERFACE chunks.
     * 1. Exact class name lookup + parent package summary (concurrent with candidate search)
     * 2. Strategy-aware candidate search (concurrent with class lookup chain)
     * 3. Enrich with parent context
     */
    private List<Document> retrieveForClass(String query, int rawTopK, String repositoryId, RetrievalStrategy strategy) {
        Optional<String> symbol = symbolExtractor.extract(query);

        CompletableFuture<List<Document>> metadataFuture = CompletableFuture.supplyAsync(
                () -> {
                    List<Document> classDocs = new ArrayList<>();
                    if (symbol.isPresent()) {
                        String className = symbol.get();
                        List<Document> exact = lookupClassBySymbol(className, repositoryId);
                        classDocs.addAll(exact);

                        // Fetch parent package summary sequentially within the metadata chain
                        if (!exact.isEmpty()) {
                            String pkg = String.valueOf(exact.get(0).getMetadata().getOrDefault("packageName", ""));
                            if (!pkg.isBlank()) {
                                classDocs.addAll(lookupByPackageName(pkg, repositoryId));
                            }
                        }
                    }
                    return classDocs;
                },
                retrievalExecutor
        );

        CompletableFuture<List<Document>> candidateFuture = fetchCandidatesAsync(
                query, rawTopK, repositoryId, strategy, QueryIntent.CLASS);

        List<Document> results = new ArrayList<>();
        try {
            results.addAll(metadataFuture.join());
        } catch (Exception e) {
            log.warn("Concurrent metadata lookup failed in CLASS strategy: {}", e.getMessage());
        }
        try {
            results.addAll(candidateFuture.join());
        } catch (Exception e) {
            log.warn("Concurrent candidate search failed in CLASS strategy: {}", e.getMessage());
        }

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
    private List<Document> retrieveForArchitecture(String query, int rawTopK, String repositoryId, RetrievalStrategy strategy) {
        com.shravan.jcode_intelligence.model.ArchitectureContext context =
                architectureContextBuilder.buildContext(repositoryId);

        List<Document> structuralDocs = context.toFlatDocumentList();

        // Supplementary candidate search for specific terms in user query (e.g. "security", "jwt", "kafka")
        if (query != null && !query.isBlank()) {
            List<Document> candidateDocs = fetchCandidates(query, Math.min(rawTopK, 5), repositoryId, strategy, QueryIntent.ARCHITECTURE);
            for (Document cdoc : candidateDocs) {
                String type = String.valueOf(cdoc.getMetadata().getOrDefault("type", ""));
                if (!"FIELD".equalsIgnoreCase(type)) { // Zero fields!
                    structuralDocs.add(cdoc);
                }
            }
        }

        return deduplicate(structuralDocs);
    }

    /**
     * WORKFLOW: Broader context — METHODS + CLASSES + declared member methods.
     * Connects multiple components for end-to-end flow explanation.
     * Executes metadata lookups and candidate search concurrently.
     */
    private List<Document> retrieveForWorkflow(String query, int rawTopK, String repositoryId, RetrievalStrategy strategy) {
        Optional<String> symbol = symbolExtractor.extract(query);

        CompletableFuture<List<Document>> metadataFuture = CompletableFuture.supplyAsync(
                () -> {
                    List<Document> metaDocs = new ArrayList<>();
                    // Symbol lookup and declared member methods if present
                    symbol.ifPresent(s -> {
                        metaDocs.addAll(lookupByMetadata(s, repositoryId));
                        metaDocs.addAll(lookupClassMembers(s, repositoryId));
                    });
                    return metaDocs;
                },
                retrievalExecutor
        );

        CompletableFuture<List<Document>> candidateFuture = fetchCandidatesAsync(
                query, rawTopK, repositoryId, strategy, QueryIntent.WORKFLOW);

        List<Document> results = new ArrayList<>();
        try {
            results.addAll(metadataFuture.join());
        } catch (Exception e) {
            log.warn("Concurrent metadata lookup failed in WORKFLOW strategy: {}", e.getMessage());
        }
        try {
            results.addAll(candidateFuture.join());
        } catch (Exception e) {
            log.warn("Concurrent candidate search failed in WORKFLOW strategy: {}", e.getMessage());
        }

        return enrichWithParentContext(deduplicate(results), repositoryId);
    }

    /**
     * RELATIONSHIP: CLASS + METHOD + INTERFACE with wider Top-K.
     * Focus on metadata relationships.
     * Executes metadata lookups and candidate search concurrently.
     */
    private List<Document> retrieveForRelationship(String query, int rawTopK, String repositoryId, RetrievalStrategy strategy) {
        Optional<String> symbol = symbolExtractor.extract(query);

        CompletableFuture<List<Document>> metadataFuture = CompletableFuture.supplyAsync(
                () -> {
                    List<Document> metaDocs = new ArrayList<>();
                    symbol.ifPresent(s -> {
                        List<Document> exact = lookupByMetadata(s, repositoryId);
                        metaDocs.addAll(exact);
                        log.info("RELATIONSHIP strategy: symbol '{}' returned {} doc(s)", s, exact.size());
                    });
                    return metaDocs;
                },
                retrievalExecutor
        );

        CompletableFuture<List<Document>> candidateFuture = fetchCandidatesAsync(
                query, rawTopK, repositoryId, strategy, QueryIntent.RELATIONSHIP);

        List<Document> results = new ArrayList<>();
        try {
            results.addAll(metadataFuture.join());
        } catch (Exception e) {
            log.warn("Concurrent metadata lookup failed in RELATIONSHIP strategy: {}", e.getMessage());
        }
        try {
            results.addAll(candidateFuture.join());
        } catch (Exception e) {
            log.warn("Concurrent candidate search failed in RELATIONSHIP strategy: {}", e.getMessage());
        }

        return enrichWithParentContext(deduplicate(results), repositoryId);
    }

    /**
     * SEARCH: Metadata-first strategy.
     * 1. Exact match on className, methodName, elementName, signature
     * 2. Signature partial match (ILIKE)
     * 3. Strategy-aware candidate search as fallback
     */
    private List<Document> retrieveForSearch(String query, int rawTopK, String repositoryId, RetrievalStrategy strategy) {
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

        // 3. Candidate fallback
        if (results.isEmpty()) {
            List<Document> candidateDocs = fetchCandidates(query, rawTopK, repositoryId, strategy, QueryIntent.SEARCH);
            results.addAll(candidateDocs);
        }

        return enrichWithParentContext(deduplicate(results), repositoryId);
    }

    /**
     * DESIGN: CLASS + PACKAGE + INTERFACE. Avoids FIELD chunks.
     */
    private List<Document> retrieveForDesign(String query, int rawTopK, String repositoryId, RetrievalStrategy strategy) {
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

        // 4. Candidate search
        List<Document> candidateDocs = fetchCandidates(query, rawTopK, repositoryId, strategy, QueryIntent.DESIGN);
        results.addAll(candidateDocs);

        return deduplicate(results);
    }

    /**
     * UNKNOWN: Generic hybrid retrieval (metadata + candidate search).
     * Executes metadata lookup and candidate search concurrently.
     */
    private List<Document> retrieveForUnknown(String query, int rawTopK, String repositoryId, RetrievalStrategy strategy) {
        Optional<String> symbol = symbolExtractor.extract(query);

        CompletableFuture<List<Document>> metadataFuture = CompletableFuture.supplyAsync(
                () -> {
                    List<Document> metaDocs = new ArrayList<>();
                    symbol.ifPresent(s -> {
                        List<Document> metadataResults = lookupByMetadata(s, repositoryId);
                        metaDocs.addAll(metadataResults);
                        log.info("UNKNOWN strategy: symbol '{}' metadata lookup returned {} doc(s)", s, metadataResults.size());
                    });
                    return metaDocs;
                },
                retrievalExecutor
        );

        CompletableFuture<List<Document>> candidateFuture = fetchCandidatesAsync(
                query, rawTopK, repositoryId, strategy, QueryIntent.UNKNOWN);

        List<Document> results = new ArrayList<>();
        try {
            results.addAll(metadataFuture.join());
        } catch (Exception e) {
            log.warn("Concurrent metadata lookup failed in UNKNOWN strategy: {}", e.getMessage());
        }
        try {
            results.addAll(candidateFuture.join());
        } catch (Exception e) {
            log.warn("Concurrent candidate search failed in UNKNOWN strategy: {}", e.getMessage());
        }

        return enrichWithParentContext(deduplicate(results), repositoryId);
    }

    // ── Vector Search ─────────────────────────────────────────

    /**
     * Performs vector similarity search using cosine distance via the configured {@link VectorStore}.
     *
     * @param query        natural language query
     * @param limit        maximum number of documents to return
     * @param repositoryId target repository ID (optional, nullable)
     * @return list of similar documents
     */
    private List<Document> performVectorSearch(String query, int limit, String repositoryId) {
        try {
            SearchRequest.Builder builder = SearchRequest.builder()
                    .query(query)
                    .topK(limit)
                    .similarityThreshold(config.getSimilarityThreshold());

            if (repositoryId != null && !repositoryId.isBlank()) {
                builder.filterExpression("repositoryId == '" + repositoryId + "'");
            }

            List<Document> raw = vectorStore.similaritySearch(builder.build());
            if (raw == null || raw.isEmpty()) {
                return List.of();
            }

            List<Document> tagged = new ArrayList<>(raw.size());
            for (Document doc : raw) {
                if (doc == null) {
                    continue;
                }
                Map<String, Object> meta = new LinkedHashMap<>();
                if (doc.getMetadata() != null) {
                    meta.putAll(doc.getMetadata());
                }
                Double score = extractDenseScore(doc);
                if (score != null) {
                    meta.put(RetrievalConstants.METADATA_DENSE_SCORE, score);
                }
                Set<String> routes = new LinkedHashSet<>(extractRoutes(meta));
                routes.add(RetrievalConstants.ROUTE_VECTOR);
                meta.put(RetrievalConstants.METADATA_RETRIEVAL_ROUTES, routes);

                tagged.add(doc.mutate()
                        .metadata(meta)
                        .score(score != null ? score : doc.getScore())
                        .build());
            }
            return tagged;
        } catch (Exception e) {
            log.warn("Vector similarity search failed for query '{}': {}", query, e.getMessage());
            return List.of();
        }
    }

    // ── SQL Metadata Lookups ──────────────────────────────────

    /**
     * Looks up class or interface declaration documents matching the specified class name.
     * Restricts to type declarations (CLASS, INTERFACE, RECORD, ENUM) to prevent matching
     * all member chunks that share the enclosing class name.
     *
     * @param className    class or interface name
     * @param repositoryId target repository ID (optional, nullable)
     * @return matching class/interface documents
     */
    List<Document> lookupClassBySymbol(String className, String repositoryId) {
        try {
            String sql;
            Object[] params;

            if (repositoryId != null && !repositoryId.isBlank()) {
                sql = "SELECT content, metadata FROM vector_store " +
                      "WHERE metadata->>'type' IN ('CLASS', 'INTERFACE', 'RECORD', 'ENUM') " +
                      "AND (metadata->>'className' = ? OR metadata->>'elementName' = ?) " +
                      "AND metadata->>'repositoryId' = ?";
                params = new Object[]{className, className, repositoryId};
            } else {
                sql = "SELECT content, metadata FROM vector_store " +
                      "WHERE metadata->>'type' IN ('CLASS', 'INTERFACE', 'RECORD', 'ENUM') " +
                      "AND (metadata->>'className' = ? OR metadata->>'elementName' = ?)";
                params = new Object[]{className, className};
            }

            return executeQuery(sql, params);
        } catch (Exception e) {
            log.error("Class lookup failed for symbol '{}': {}", className, e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * Looks up documents matching the specified symbol as a class, element, or method name.
     *
     * @param symbol       symbol name to look up
     * @param repositoryId target repository ID (optional, nullable)
     * @return matching documents
     */
    private List<Document> lookupByMetadata(String symbol, String repositoryId) {
        try {
            String sql;
            Object[] params;

            if (repositoryId != null && !repositoryId.isBlank()) {
                sql = "SELECT content, metadata FROM vector_store " +
                      "WHERE ((metadata->>'className' = ? AND metadata->>'type' IN ('CLASS', 'INTERFACE', 'RECORD', 'ENUM')) " +
                      "OR metadata->>'elementName' = ? OR metadata->>'methodName' = ? OR metadata->>'elementName' LIKE ?) " +
                      "AND metadata->>'repositoryId' = ?";
                params = new Object[]{symbol, symbol, symbol, symbol + "#%", repositoryId};
            } else {
                sql = "SELECT content, metadata FROM vector_store " +
                      "WHERE (metadata->>'className' = ? AND metadata->>'type' IN ('CLASS', 'INTERFACE', 'RECORD', 'ENUM')) " +
                      "OR metadata->>'elementName' = ? OR metadata->>'methodName' = ? OR metadata->>'elementName' LIKE ?";
                params = new Object[]{symbol, symbol, symbol, symbol + "#%"};
            }

            return executeQuery(sql, params);
        } catch (Exception e) {
            log.error("Metadata lookup failed for symbol '{}': {}", symbol, e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * Looks up documents matching a method or constructor symbol name or fragment pattern.
     *
     * @param methodName   method name to look up
     * @param repositoryId target repository ID (optional, nullable)
     * @return matching method documents
     */
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

    /**
     * Looks up method or constructor members declared in the specified class.
     * Restricts to 20 candidate chunks to prevent result set explosion.
     *
     * @param className    class name
     * @param repositoryId target repository ID (optional, nullable)
     * @return member documents up to limit
     */
    List<Document> lookupClassMembers(String className, String repositoryId) {
        try {
            String sql;
            Object[] params;

            if (repositoryId != null && !repositoryId.isBlank()) {
                sql = "SELECT content, metadata FROM vector_store " +
                      "WHERE metadata->>'className' = ? AND metadata->>'type' IN ('METHOD', 'CONSTRUCTOR') " +
                      "AND metadata->>'repositoryId' = ? LIMIT 20";
                params = new Object[]{className, repositoryId};
            } else {
                sql = "SELECT content, metadata FROM vector_store " +
                      "WHERE metadata->>'className' = ? AND metadata->>'type' IN ('METHOD', 'CONSTRUCTOR') LIMIT 20";
                params = new Object[]{className};
            }

            return executeQuery(sql, params);
        } catch (Exception e) {
            log.warn("Member lookup failed for class '{}': {}", className, e.getMessage());
            return List.of();
        }
    }

    /**
     * Looks up documents matching a specific chunk type (e.g. CLASS, PACKAGE).
     *
     * @param type         chunk type to filter
     * @param repositoryId target repository ID (optional, nullable)
     * @return matching documents
     */
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

    /**
     * Looks up top-level class documents (nesting depth 0 or null).
     *
     * @param repositoryId target repository ID (optional, nullable)
     * @return top-level class documents
     */
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

    /**
     * Looks up package summary documents matching the given package name.
     *
     * @param packageName  package name to match
     * @param repositoryId target repository ID (optional, nullable)
     * @return matching package documents
     */
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

    /**
     * Looks up documents with method or constructor signatures containing the given symbol.
     *
     * @param symbol       symbol substring to match in signatures
     * @param repositoryId target repository ID (optional, nullable)
     * @return matching documents
     */
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
     * Binds chunk IDs as a single array parameter using PostgreSQL's {@code = ANY (?)}
     * comparison to ensure prepared statement caching and avoid dynamic SQL construction.
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
            String sql;
            Object[] params;
            String[] idArray = chunkIds.toArray(new String[0]);

            if (repositoryId != null && !repositoryId.isBlank()) {
                sql = "SELECT content, metadata FROM vector_store WHERE metadata->>'chunkId' = ANY (?) AND metadata->>'repositoryId' = ?";
                params = new Object[]{idArray, repositoryId};
            } else {
                sql = "SELECT content, metadata FROM vector_store WHERE metadata->>'chunkId' = ANY (?)";
                params = new Object[]{idArray};
            }

            return executeQuery(sql, params, RetrievalConstants.ROUTE_PARENT);
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

    /**
     * Executes a SQL query against {@code vector_store} and maps rows to {@link Document} instances,
     * defaulting to tagging with {@link RetrievalConstants#ROUTE_METADATA}.
     *
     * @param sql    SQL query string
     * @param params query parameters
     * @return list of documents
     */
    private List<Document> executeQuery(String sql, Object[] params) {
        return executeQuery(sql, params, RetrievalConstants.ROUTE_METADATA);
    }

    /**
     * Executes a SQL query against {@code vector_store} and maps rows to {@link Document} instances,
     * tagging with the specified retrieval route.
     *
     * @param sql    SQL query string
     * @param params query parameters
     * @param route  retrieval route tag to associate with mapped documents
     * @return list of documents
     */
    @SuppressWarnings("unchecked")
    private List<Document> executeQuery(String sql, Object[] params, String route) {
        return jdbcTemplate.query(sql, params, (rs, rowNum) -> {
            String content = rs.getString("content");
            String metadataJson = rs.getString("metadata");
            Map<String, Object> metadata = parseMetadataJson(metadataJson);
            if (route != null && !route.isBlank()) {
                Set<String> routes = new LinkedHashSet<>(extractRoutes(metadata));
                routes.add(route);
                metadata.put(RetrievalConstants.METADATA_RETRIEVAL_ROUTES, routes);
            }
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
     * excluding parent chunk IDs already present in the input documents and short-circuiting to zero database
     * queries if no eligible parent chunks exist.
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
        Set<String> existingChunkIds = new HashSet<>();
        for (Document doc : documents) {
            existingKeys.add(getDeduplicationKey(doc));
            if (doc.getMetadata() != null) {
                Object cId = doc.getMetadata().get("chunkId");
                if (cId != null && !String.valueOf(cId).isBlank()) {
                    existingChunkIds.add(String.valueOf(cId).trim());
                }
            }
        }

        Set<String> candidateParentIds = new LinkedHashSet<>();
        for (Document doc : documents) {
            Map<String, Object> meta = doc.getMetadata();
            if (meta == null) {
                continue;
            }
            String type = String.valueOf(meta.getOrDefault("type", ""));
            String parentChunkId = meta.get("parentChunkId") != null
                    ? String.valueOf(meta.get("parentChunkId")).trim() : null;

            if (parentChunkId != null && !parentChunkId.isEmpty()
                    && !existingChunkIds.contains(parentChunkId)
                    && isMemberType(type)) {
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

    /**
     * Determines whether the given chunk type represents a class member element
     * that is eligible for parent class context enrichment.
     *
     * @param type chunk type string
     * @return {@code true} if member type; {@code false} otherwise
     */
    private boolean isMemberType(String type) {
        return "METHOD".equals(type) || "CONSTRUCTOR".equals(type)
                || "FIELD".equals(type) || "METHOD_FRAGMENT".equals(type);
    }

    /**
     * Deduplicates a list of documents in first-wins encounter order using {@link #getDeduplicationKey(Document)}.
     * When duplicate candidates are encountered, merges the maximum dense score and route union into a mutated
     * survivor copy without mutating shared or original metadata maps. Handles null metadata maps gracefully.
     *
     * @param documents list of candidate documents
     * @return deduplicated list of documents in first-wins encounter order
     */
    private List<Document> deduplicate(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }

        Map<String, Integer> seenKeyToIndex = new HashMap<>();
        List<Document> deduplicated = new ArrayList<>();

        for (Document doc : documents) {
            if (doc == null) {
                continue;
            }
            String uniqueKey = getDeduplicationKey(doc);
            Integer existingIndex = seenKeyToIndex.get(uniqueKey);

            if (existingIndex == null) {
                Document survivorCopy = createSurvivorCopy(doc);
                seenKeyToIndex.put(uniqueKey, deduplicated.size());
                deduplicated.add(survivorCopy);
            } else {
                Document survivor = deduplicated.get(existingIndex);
                Document updatedSurvivor = mergeDuplicate(survivor, doc);
                deduplicated.set(existingIndex, updatedSurvivor);
            }
        }

        return deduplicated;
    }

    /**
     * Creates a mutated survivor copy with defensive metadata and normalized denseScore and routes.
     */
    private Document createSurvivorCopy(Document doc) {
        Map<String, Object> origMeta = doc.getMetadata();
        Map<String, Object> safeMeta = new LinkedHashMap<>();
        if (origMeta != null) {
            safeMeta.putAll(origMeta);
        }

        Double denseScore = extractDenseScore(doc);
        if (denseScore != null) {
            safeMeta.put(RetrievalConstants.METADATA_DENSE_SCORE, denseScore);
        }

        Set<String> routes = extractRoutes(safeMeta);
        safeMeta.put(RetrievalConstants.METADATA_RETRIEVAL_ROUTES, routes);

        return doc.mutate()
                .metadata(safeMeta)
                .score(denseScore != null ? denseScore : doc.getScore())
                .build();
    }

    private static final Pattern CANONICAL_PATH_PATTERN =
            Pattern.compile("(?i)(?:src/(?:main|test)/java/|proper_source/[^/]+/)(.*)");

    /**
     * Extracts canonical relative package path from a file path.
     */
    private String extractCanonicalPath(String filePath) {
        if (filePath == null || filePath.isBlank()) return "";
        String norm = filePath.replace('\\', '/').trim();
        int srcMainIdx = norm.indexOf("/src/main/java/");
        if (srcMainIdx >= 0) {
            return norm.substring(srcMainIdx + "/src/main/java/".length());
        }
        int srcTestIdx = norm.indexOf("/src/test/java/");
        if (srcTestIdx >= 0) {
            return norm.substring(srcTestIdx + "/src/test/java/".length());
        }
        for (String pkgPrefix : List.of("/com/", "/org/", "/net/", "/io/")) {
            int pkgIdx = norm.lastIndexOf(pkgPrefix);
            if (pkgIdx >= 0) {
                return norm.substring(pkgIdx + 1);
            }
        }
        Matcher m = CANONICAL_PATH_PATTERN.matcher(norm);
        if (m.find()) {
            return m.group(1);
        }
        return norm;
    }

    /**
     * Determines whether a document originates from canonical production source (/src/main/)
     * rather than test fixtures or sample repositories.
     */
    private boolean isProductionDocument(Document doc) {
        if (doc == null || doc.getMetadata() == null) return false;
        String filePath = String.valueOf(doc.getMetadata().getOrDefault("filePath", ""))
                .toLowerCase(Locale.ROOT).replace('\\', '/');
        return filePath.contains("/src/main/") && !filePath.contains("/src/test/") && !filePath.contains("test_sourcecode");
    }

    /**
     * Merges a duplicate candidate into an existing survivor copy by unioning routes and taking
     * the maximum dense score into a newly mutated survivor document without altering shared maps.
     * Prefers production document over test fixture document when merging duplicates.
     */
    private Document mergeDuplicate(Document survivor, Document duplicate) {
        Document base = survivor;
        if (!isProductionDocument(survivor) && isProductionDocument(duplicate)) {
            base = duplicate;
        }

        Double survivorDense = extractDenseScore(survivor);
        Double duplicateDense = extractDenseScore(duplicate);

        Double maxDense = null;
        if (survivorDense != null && duplicateDense != null) {
            maxDense = Math.max(survivorDense, duplicateDense);
        } else if (survivorDense != null) {
            maxDense = survivorDense;
        } else if (duplicateDense != null) {
            maxDense = duplicateDense;
        }

        Set<String> survivorRoutes = extractRoutes(survivor.getMetadata());
        Set<String> duplicateRoutes = extractRoutes(duplicate.getMetadata());
        Set<String> unionRoutes = new LinkedHashSet<>(survivorRoutes);
        unionRoutes.addAll(duplicateRoutes);

        Map<String, Object> mergedMeta = new LinkedHashMap<>();
        if (base.getMetadata() != null) {
            mergedMeta.putAll(base.getMetadata());
        }
        if (maxDense != null) {
            mergedMeta.put(RetrievalConstants.METADATA_DENSE_SCORE, maxDense);
        }
        mergedMeta.put(RetrievalConstants.METADATA_RETRIEVAL_ROUTES, unionRoutes);

        return base.mutate()
                .metadata(mergedMeta)
                .score(maxDense != null ? maxDense : base.getScore())
                .build();
    }

    /**
     * Extracts dense score from a document's metadata or score property.
     */
    private Double extractDenseScore(Document doc) {
        if (doc == null) return null;
        Map<String, Object> meta = doc.getMetadata();
        if (meta != null) {
            Object ds = meta.get(RetrievalConstants.METADATA_DENSE_SCORE);
            if (ds instanceof Number num) {
                return num.doubleValue();
            }
        }
        if (doc.getScore() != null) {
            return doc.getScore();
        }
        if (meta != null) {
            Object s = meta.get("score");
            if (s instanceof Number num) {
                return num.doubleValue();
            }
            Object dist = meta.get("distance");
            if (dist instanceof Number num) {
                return 1.0 - num.doubleValue();
            }
        }
        return null;
    }

    /**
     * Extracts retrieval routes from metadata safely without throwing on non-collections or nulls.
     */
    @SuppressWarnings("unchecked")
    private Set<String> extractRoutes(Map<String, Object> metadata) {
        if (metadata == null) {
            return new LinkedHashSet<>();
        }
        Object routesObj = metadata.get(RetrievalConstants.METADATA_RETRIEVAL_ROUTES);
        if (routesObj instanceof Collection<?> coll) {
            Set<String> set = new LinkedHashSet<>();
            for (Object item : coll) {
                if (item != null) {
                    set.add(item.toString());
                }
            }
            return set;
        } else if (routesObj instanceof String str && !str.isBlank()) {
            return new LinkedHashSet<>(List.of(str));
        }
        return new LinkedHashSet<>();
    }

    /**
     * Computes a deterministic deduplication key for a document based on repository ID,
     * file path, line boundaries, and chunk type. Safely handles null metadata maps.
     *
     * @param doc document to generate key for
     * @return deduplication key formatted as {@code repo:path:startLine:endLine:type}
     */
    private String getDeduplicationKey(Document doc) {
        if (doc == null) {
            return "";
        }
        Map<String, Object> meta = doc.getMetadata();
        if (meta == null) {
            return doc.getId() != null ? doc.getId() : String.valueOf(doc.hashCode());
        }
        String repoId = String.valueOf(meta.getOrDefault("repositoryId", "default"));
        String filePath = String.valueOf(meta.getOrDefault("filePath", ""));
        String canonicalPath = extractCanonicalPath(filePath);
        String startLine = String.valueOf(meta.getOrDefault("startLine", "0"));
        String endLine = String.valueOf(meta.getOrDefault("endLine", "0"));
        String type = String.valueOf(meta.getOrDefault("type", ""));

        return String.format("%s:%s:%s:%s:%s", repoId, canonicalPath, startLine, endLine, type);
    }
}
