package com.shravan.jcode_intelligence.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shravan.jcode_intelligence.config.IntentRetrievalConfig;
import com.shravan.jcode_intelligence.config.RetrievalConfig;
import com.shravan.jcode_intelligence.model.QueryIntent;
import com.shravan.jcode_intelligence.model.RetrievalStrategy;
import com.shravan.jcode_intelligence.sparse.fusion.ReciprocalRankFusion;
import com.shravan.jcode_intelligence.sparse.mapper.LuceneDocumentMapper;
import com.shravan.jcode_intelligence.sparse.query.LuceneQueryBuilder;
import com.shravan.jcode_intelligence.sparse.store.LuceneGenerationStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentMetadata;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.util.*;
import java.util.concurrent.ThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.*;

class RetrievalStrategySelectorTest {

    private TestJdbcTemplate testJdbcTemplate;
    private TestVectorStore testVectorStore;
    private IntentRetrievalConfig config;
    private ArchitectureContextBuilder archBuilder;
    private SymbolExtractor symbolExtractor;
    private RetrievalStrategySelector selector;

    @BeforeEach
    void setUp() {
        testJdbcTemplate = new TestJdbcTemplate();
        testVectorStore = new TestVectorStore();
        config = new IntentRetrievalConfig() {
            @Override public int getFinalTopK(QueryIntent intent) { return 10; }
            @Override public int getRawTopK(QueryIntent intent) { return 20; }
            @Override public double getSimilarityThreshold() { return 0.65; }
        };
        archBuilder = new ArchitectureContextBuilder(testJdbcTemplate);
        symbolExtractor = new SymbolExtractor();
        selector = new RetrievalStrategySelector(
                testVectorStore,
                testJdbcTemplate,
                symbolExtractor,
                config,
                archBuilder,
                new ObjectMapper()
        );
    }

    @Test
    @DisplayName("enrichWithParentContext executes 0 database queries when no parent chunks exist")
    void testEnrichWithParentContext_zeroQueriesWhenNoParents() throws Exception {
        Map<String, Object> meta1 = new HashMap<>();
        meta1.put("type", "CLASS");
        meta1.put("chunkId", "C1");
        meta1.put("filePath", "src/Foo.java");
        meta1.put("startLine", 1);
        meta1.put("endLine", 50);

        Map<String, Object> meta2 = new HashMap<>();
        meta2.put("type", "PACKAGE");
        meta2.put("chunkId", "PKG1");

        List<Document> docs = List.of(new Document("class Foo", meta1), new Document("package com", meta2));

        Method enrichMethod = RetrievalStrategySelector.class.getDeclaredMethod("enrichWithParentContext", List.class, String.class);
        enrichMethod.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<Document> result = (List<Document>) enrichMethod.invoke(selector, docs, "test-repo");

        assertEquals(2, result.size());
        assertEquals(0, testJdbcTemplate.executedQueries.size(), "Zero queries must be executed when no parent chunks are requested");
    }

    @Test
    @DisplayName("enrichWithParentContext executes exactly 1 batch query for multiple distinct parents")
    void testEnrichWithParentContext_singleBatchQueryForMultipleParents() throws Exception {
        Map<String, Object> m1 = new HashMap<>();
        m1.put("type", "METHOD");
        m1.put("parentChunkId", "P1");
        m1.put("chunkId", "M1");
        m1.put("filePath", "src/Foo.java");
        m1.put("startLine", 10);
        m1.put("endLine", 20);

        Map<String, Object> m2 = new HashMap<>();
        m2.put("type", "CONSTRUCTOR");
        m2.put("parentChunkId", "P2");
        m2.put("chunkId", "M2");
        m2.put("filePath", "src/Bar.java");
        m2.put("startLine", 5);
        m2.put("endLine", 15);

        Map<String, Object> m3 = new HashMap<>();
        m3.put("type", "FIELD");
        m3.put("parentChunkId", "P1"); // Duplicate parent
        m3.put("chunkId", "F1");
        m3.put("filePath", "src/Foo.java");
        m3.put("startLine", 2);
        m3.put("endLine", 4);

        List<Document> docs = List.of(
                new Document("method1", m1),
                new Document("constructor2", m2),
                new Document("field3", m3)
        );

        // Preload mock parent documents in the TestJdbcTemplate
        Map<String, Object> p1Meta = new HashMap<>();
        p1Meta.put("type", "CLASS");
        p1Meta.put("chunkId", "P1");
        p1Meta.put("filePath", "src/Foo.java");
        p1Meta.put("startLine", 1);
        p1Meta.put("endLine", 100);

        Map<String, Object> p2Meta = new HashMap<>();
        p2Meta.put("type", "CLASS");
        p2Meta.put("chunkId", "P2");
        p2Meta.put("filePath", "src/Bar.java");
        p2Meta.put("startLine", 1);
        p2Meta.put("endLine", 80);

        testJdbcTemplate.parentResponses.put("P1", new Document("class Foo summary", p1Meta));
        testJdbcTemplate.parentResponses.put("P2", new Document("class Bar summary", p2Meta));

        Method enrichMethod = RetrievalStrategySelector.class.getDeclaredMethod("enrichWithParentContext", List.class, String.class);
        enrichMethod.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<Document> result = (List<Document>) enrichMethod.invoke(selector, docs, "test-repo");

        assertEquals(1, testJdbcTemplate.executedQueries.size(), "Exactly 1 database query must be executed for batch enrichment");

        String executedSql = testJdbcTemplate.executedQueries.get(0);
        assertTrue(executedSql.contains("metadata->>'chunkId' = ANY (?)"), "SQL must use array ANY comparison: " + executedSql);
        assertTrue(executedSql.contains("metadata->>'repositoryId' = ?"), "SQL must enforce repository isolation: " + executedSql);

        Object[] args = testJdbcTemplate.executedArgs.get(0);
        assertEquals(2, args.length);
        assertTrue(args[0] instanceof String[]);
        String[] idArray = (String[]) args[0];
        assertEquals(2, idArray.length);
        assertEquals("P1", idArray[0]);
        assertEquals("P2", idArray[1]);
        assertEquals("test-repo", args[1]);

        // Parents are prepended before the 3 member documents
        assertEquals(5, result.size());
        assertEquals("P1", result.get(0).getMetadata().get("chunkId"));
        assertEquals("P2", result.get(1).getMetadata().get("chunkId"));
        assertEquals("M1", result.get(2).getMetadata().get("chunkId"));
        assertEquals("M2", result.get(3).getMetadata().get("chunkId"));
        assertEquals("F1", result.get(4).getMetadata().get("chunkId"));
    }

    @Test
    @DisplayName("enrichWithParentContext handles unscoped repository without syntax errors")
    void testEnrichWithParentContext_unscopedRepository() throws Exception {
        Map<String, Object> m1 = new HashMap<>();
        m1.put("type", "METHOD");
        m1.put("parentChunkId", "P1");

        List<Document> docs = List.of(new Document("method", m1));

        Method enrichMethod = RetrievalStrategySelector.class.getDeclaredMethod("enrichWithParentContext", List.class, String.class);
        enrichMethod.setAccessible(true);

        enrichMethod.invoke(selector, docs, null);

        assertEquals(1, testJdbcTemplate.executedQueries.size());
        String sql = testJdbcTemplate.executedQueries.get(0);
        assertTrue(sql.contains("metadata->>'chunkId' = ANY (?)"));
        assertFalse(sql.contains("metadata->>'repositoryId' = ?"), "Unscoped query must omit repository filter");
        assertEquals(1, testJdbcTemplate.executedArgs.get(0).length);
        assertTrue(testJdbcTemplate.executedArgs.get(0)[0] instanceof String[]);
        assertEquals("P1", ((String[]) testJdbcTemplate.executedArgs.get(0)[0])[0]);
    }

    @Test
    @DisplayName("enrichWithParentContext executes 0 queries when parent is already present in documents")
    void testEnrichWithParentContext_parentAlreadyInDocuments_executesZeroQueries() throws Exception {
        Map<String, Object> p1Meta = new HashMap<>();
        p1Meta.put("type", "CLASS");
        p1Meta.put("chunkId", "P1");
        p1Meta.put("repositoryId", "repo1");
        p1Meta.put("filePath", "src/Foo.java");
        p1Meta.put("startLine", 1);
        p1Meta.put("endLine", 100);

        Map<String, Object> m1 = new HashMap<>();
        m1.put("type", "METHOD");
        m1.put("chunkId", "M1");
        m1.put("parentChunkId", "P1");
        m1.put("repositoryId", "repo1");
        m1.put("filePath", "src/Foo.java");
        m1.put("startLine", 10);
        m1.put("endLine", 20);

        Document parentDoc = new Document("class Foo", p1Meta);
        Document memberDoc = new Document("method foo", m1);

        Method enrichMethod = RetrievalStrategySelector.class.getDeclaredMethod("enrichWithParentContext", List.class, String.class);
        enrichMethod.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<Document> result = (List<Document>) enrichMethod.invoke(selector, List.of(parentDoc, memberDoc), "repo1");

        // Parent should not be fetched from DB and executedQueries must be empty (0 queries)
        assertEquals(0, testJdbcTemplate.executedQueries.size(), "Zero queries must be executed when parent is already in documents");
        assertEquals(2, result.size());
        assertEquals("P1", result.get(0).getMetadata().get("chunkId"));
        assertEquals("M1", result.get(1).getMetadata().get("chunkId"));
    }

    @Test
    @DisplayName("parseMetadataJson reuses ObjectMapper and handles null, blank, and malformed inputs gracefully")
    void testParseMetadataJson() throws Exception {
        Method parseMethod = RetrievalStrategySelector.class.getDeclaredMethod("parseMetadataJson", String.class);
        parseMethod.setAccessible(true);

        @SuppressWarnings("unchecked")
        Map<String, Object> valid = (Map<String, Object>) parseMethod.invoke(selector, "{\"type\":\"METHOD\",\"rank\":5}");
        assertNotNull(valid);
        assertEquals("METHOD", valid.get("type"));
        assertEquals(5, valid.get("rank"));

        @SuppressWarnings("unchecked")
        Map<String, Object> nullResult = (Map<String, Object>) parseMethod.invoke(selector, (String) null);
        assertNotNull(nullResult);
        assertTrue(nullResult.isEmpty());

        @SuppressWarnings("unchecked")
        Map<String, Object> blankResult = (Map<String, Object>) parseMethod.invoke(selector, "   ");
        assertNotNull(blankResult);
        assertTrue(blankResult.isEmpty());

        @SuppressWarnings("unchecked")
        Map<String, Object> malformedResult = (Map<String, Object>) parseMethod.invoke(selector, "{\"invalid\": json...");
        assertNotNull(malformedResult);
        assertTrue(malformedResult.isEmpty());
    }

    @Test
    @DisplayName("Constructor backward compatibility without ObjectMapper")
    void testConstructorBackwardCompatibility() {
        RetrievalStrategySelector backwardCompatible = new RetrievalStrategySelector(
                testVectorStore,
                testJdbcTemplate,
                symbolExtractor,
                config,
                archBuilder
        );
        assertNotNull(backwardCompatible);
    }

    @Test
    @DisplayName("SQL metadata queries project only content and metadata, omitting id")
    void testSqlQueriesOmitIdProjection() throws Exception {
        Method lookupMethod = RetrievalStrategySelector.class.getDeclaredMethod("lookupMethodBySymbol", String.class, String.class);
        lookupMethod.setAccessible(true);
        lookupMethod.invoke(selector, "testMethod", "test-repo");

        assertFalse(testJdbcTemplate.executedQueries.isEmpty());
        String sql = testJdbcTemplate.executedQueries.get(0);
        assertTrue(sql.startsWith("SELECT content, metadata FROM vector_store"));
        assertFalse(sql.contains("SELECT id, content, metadata"));
    }

    @Test
    @DisplayName("Concurrent retrieval for METHOD intent preserves metadata-first result ordering")
    void testConcurrentRetrievalForMethodPreservesOrder() {
        Map<String, Object> metaExact = new HashMap<>();
        metaExact.put("type", "METHOD");
        metaExact.put("chunkId", "M_EXACT");
        metaExact.put("filePath", "src/Task.java");
        metaExact.put("startLine", 10);
        metaExact.put("endLine", 30);
        Document exactDoc = new Document("executeTask source", metaExact);

        Map<String, Object> metaVector = new HashMap<>();
        metaVector.put("type", "METHOD");
        metaVector.put("chunkId", "M_VECTOR");
        metaVector.put("filePath", "src/Other.java");
        metaVector.put("startLine", 50);
        metaVector.put("endLine", 70);
        Document vectorDoc = new Document("other vector source", metaVector);

        testJdbcTemplate.defaultMethodResponse = List.of(exactDoc);
        testVectorStore.defaultSearchResults = List.of(vectorDoc);

        List<Document> docs = selector.retrieve("executeTask()", QueryIntent.METHOD, "test-repo");

        assertNotNull(docs);
        assertEquals(2, docs.size());
        assertEquals("M_EXACT", docs.get(0).getMetadata().get("chunkId"), "Exact metadata match must appear before vector match");
        assertEquals("M_VECTOR", docs.get(1).getMetadata().get("chunkId"));
    }

    @Test
    @DisplayName("Concurrent retrieval for CLASS intent preserves class, package, and vector ordering")
    void testConcurrentRetrievalForClassPreservesOrder() {
        Map<String, Object> metaClass = new HashMap<>();
        metaClass.put("type", "CLASS");
        metaClass.put("chunkId", "C_EXACT");
        metaClass.put("className", "ParserService");
        metaClass.put("packageName", "com.example");
        metaClass.put("filePath", "src/ParserService.java");
        metaClass.put("startLine", 1);
        metaClass.put("endLine", 100);
        Document classDoc = new Document("class ParserService", metaClass);

        Map<String, Object> metaPackage = new HashMap<>();
        metaPackage.put("type", "PACKAGE");
        metaPackage.put("chunkId", "P_EXACT");
        metaPackage.put("packageName", "com.example");
        metaPackage.put("filePath", "src/package-info.java");
        metaPackage.put("startLine", 1);
        metaPackage.put("endLine", 20);
        Document packageDoc = new Document("package com.example", metaPackage);

        Map<String, Object> metaVector = new HashMap<>();
        metaVector.put("type", "CLASS");
        metaVector.put("chunkId", "C_VECTOR");
        metaVector.put("filePath", "src/OtherService.java");
        metaVector.put("startLine", 1);
        metaVector.put("endLine", 80);
        Document vectorDoc = new Document("other vector service", metaVector);

        testJdbcTemplate.defaultClassResponse = List.of(classDoc);
        testJdbcTemplate.defaultPackageResponse = List.of(packageDoc);
        testVectorStore.defaultSearchResults = List.of(vectorDoc);

        List<Document> docs = selector.retrieve("ParserService", QueryIntent.CLASS, "test-repo");

        assertNotNull(docs);
        assertEquals(3, docs.size());
        assertEquals("C_EXACT", docs.get(0).getMetadata().get("chunkId"), "Exact class match must appear first");
        assertEquals("P_EXACT", docs.get(1).getMetadata().get("chunkId"), "Package match must appear second");
        assertEquals("C_VECTOR", docs.get(2).getMetadata().get("chunkId"), "Vector match must appear third");
    }

    @Test
    @DisplayName("Strategy selection routes to DENSE, SPARSE, or HYBRID candidate retrieval")
    void testStrategySelection_Dense_Sparse_Hybrid() {
        Document vectorDoc = new Document("v1", "vector content", Map.of("chunkId", "V1", "filePath", "src/V1.java", "type", "CLASS"));
        Document sparseDoc = new Document("s1", "sparse content", Map.of("chunkId", "S1", "filePath", "src/S1.java", "type", "CLASS"));

        TestVectorStore vs = new TestVectorStore();
        vs.defaultSearchResults = List.of(vectorDoc);

        RetrievalConfig rConfig = new RetrievalConfig();
        TestLuceneStore ls = new TestLuceneStore(rConfig);
        ls.defaultSearchResults = List.of(sparseDoc);

        RetrievalStrategySelector stratSelector = new RetrievalStrategySelector(
                vs, testJdbcTemplate, symbolExtractor, config, archBuilder,
                new ObjectMapper(), null, ls, new ReciprocalRankFusion(rConfig), rConfig
        );

        // 1. DENSE strategy
        List<Document> denseResults = stratSelector.retrieve("query", QueryIntent.UNKNOWN, "repo-1", RetrievalStrategy.DENSE);
        assertEquals(1, vs.callCount);
        assertEquals(0, ls.callCount);
        assertEquals(1, denseResults.size());
        assertEquals("V1", denseResults.get(0).getMetadata().get("chunkId"));

        // 2. SPARSE strategy
        vs.callCount = 0;
        ls.callCount = 0;
        List<Document> sparseResults = stratSelector.retrieve("query", QueryIntent.UNKNOWN, "repo-1", RetrievalStrategy.SPARSE);
        assertEquals(0, vs.callCount);
        assertEquals(1, ls.callCount);
        assertEquals(1, sparseResults.size());
        assertEquals("S1", sparseResults.get(0).getMetadata().get("chunkId"));

        // 3. HYBRID strategy
        vs.callCount = 0;
        ls.callCount = 0;
        List<Document> hybridResults = stratSelector.retrieve("query", QueryIntent.UNKNOWN, "repo-1", RetrievalStrategy.HYBRID);
        assertEquals(1, vs.callCount);
        assertEquals(1, ls.callCount);
        assertEquals(2, hybridResults.size());
    }

    @Test
    @DisplayName("Hybrid search handles branch failures independently without failing request")
    void testIndependentBranchFailureHandling() {
        Document vectorDoc = new Document("v1", "vector content", Map.of("chunkId", "V1", "filePath", "src/V1.java", "type", "CLASS"));
        Document sparseDoc = new Document("s1", "sparse content", Map.of("chunkId", "S1", "filePath", "src/S1.java", "type", "CLASS"));

        TestVectorStore vs = new TestVectorStore();
        RetrievalConfig rConfig = new RetrievalConfig();
        TestLuceneStore ls = new TestLuceneStore(rConfig);

        RetrievalStrategySelector stratSelector = new RetrievalStrategyStrategyWrapper(
                vs, testJdbcTemplate, symbolExtractor, config, archBuilder,
                new ObjectMapper(), null, ls, new ReciprocalRankFusion(rConfig), rConfig
        );

        // Subcase A: Vector store throws exception, Sparse succeeds
        vs.throwError = true;
        ls.throwError = false;
        ls.defaultSearchResults = List.of(sparseDoc);

        List<Document> resA = stratSelector.retrieve("query", QueryIntent.UNKNOWN, "repo-1", RetrievalStrategy.HYBRID);
        assertNotNull(resA);
        assertEquals(1, resA.size());
        assertEquals("S1", resA.get(0).getMetadata().get("chunkId"));

        // Subcase B: Sparse store throws exception, Vector succeeds
        vs.throwError = false;
        vs.defaultSearchResults = List.of(vectorDoc);
        ls.throwError = true;

        List<Document> resB = stratSelector.retrieve("query", QueryIntent.UNKNOWN, "repo-1", RetrievalStrategy.HYBRID);
        assertNotNull(resB);
        assertEquals(1, resB.size());
        assertEquals("V1", resB.get(0).getMetadata().get("chunkId"));
    }

    @Test
    @DisplayName("Fallback executor core size is configurable with default of 4")
    void testFallbackExecutorCoreSizeConfigurable() throws Exception {
        // 1. Custom core pool size
        RetrievalConfig customConfig = new RetrievalConfig();
        customConfig.setExecutorCorePoolSize(7);

        RetrievalStrategySelector customSelector = new RetrievalStrategySelector(
                testVectorStore, testJdbcTemplate, symbolExtractor, config, archBuilder,
                new ObjectMapper(), null, null, null, customConfig
        );

        Field execField = RetrievalStrategySelector.class.getDeclaredField("retrievalExecutor");
        execField.setAccessible(true);
        ThreadPoolExecutor customExecutor = (ThreadPoolExecutor) execField.get(customSelector);
        assertEquals(7, customExecutor.getCorePoolSize());

        // 2. Default core pool size (4)
        RetrievalConfig defaultConfig = new RetrievalConfig();
        RetrievalStrategySelector defaultSelector = new RetrievalStrategySelector(
                testVectorStore, testJdbcTemplate, symbolExtractor, config, archBuilder,
                new ObjectMapper(), null, null, null, defaultConfig
        );
        ThreadPoolExecutor defaultExecutor = (ThreadPoolExecutor) execField.get(defaultSelector);
        assertEquals(4, defaultExecutor.getCorePoolSize());
    }

    @Test
    @DisplayName("Real temporary Lucene index verifies exact identifier and typo fuzzy matching")
    void testRealLuceneExactIdentifierAndTypoSearch(@TempDir Path tempDir) throws Exception {
        RetrievalConfig luceneConfig = new RetrievalConfig();
        luceneConfig.setIndexDir(tempDir.toString());
        luceneConfig.setFuzzyMinTermLength(4);
        luceneConfig.setFuzzyMaxEdits(1);

        try (LuceneGenerationStore realStore = new LuceneGenerationStore(
                luceneConfig, new LuceneDocumentMapper(), new LuceneQueryBuilder(luceneConfig))) {

            try (LuceneGenerationStore.StagingSession session = realStore.stageGeneration("repo-test")) {
                Map<String, Object> meta = new HashMap<>();
                meta.put("type", "CLASS");
                meta.put("className", "PaymentProcessingService");
                meta.put("elementName", "PaymentProcessingService");
                meta.put("filePath", "src/PaymentProcessingService.java");
                meta.put("startLine", 1);
                meta.put("endLine", 50);
                meta.put("repositoryId", "repo-test");
                Document doc = new Document("uuid-payment-1",
                        "public class PaymentProcessingService { public void processPayment() {} }", meta);
                session.writeDocuments(List.of(doc));
                realStore.publishGeneration(session);
            }

            RetrievalStrategySelector realSelector = new RetrievalStrategySelector(
                    testVectorStore, testJdbcTemplate, symbolExtractor, config, archBuilder,
                    new ObjectMapper(), null, realStore, new ReciprocalRankFusion(luceneConfig), luceneConfig
            );

            // 1. Exact identifier search
            List<Document> exactResults = realSelector.retrieve(
                    "PaymentProcessingService", QueryIntent.UNKNOWN, "repo-test", RetrievalStrategy.SPARSE);
            assertFalse(exactResults.isEmpty(), "Exact identifier search must return results");
            assertEquals("uuid-payment-1", exactResults.get(0).getId());

            // 2. Typo query: "PaymentProcessngService" (1 edit distance: missing 'i' in Processing)
            List<Document> typoResults = realSelector.retrieve(
                    "PaymentProcessngService", QueryIntent.UNKNOWN, "repo-test", RetrievalStrategy.SPARSE);
            assertFalse(typoResults.isEmpty(), "Fuzzy search must tolerate typo and find PaymentProcessingService");
            assertEquals("uuid-payment-1", typoResults.get(0).getId());
        }
    }

    @Test
    @DisplayName("Deduplication preserves first-wins order, merges route union, and computes maximum dense score")
    void testDeduplicatePreservesFirstWinsOrderAndMergesMaxDenseScoreAndRouteUnion() throws Exception {
        // Document A (first encountered at index 0)
        Map<String, Object> metaA1 = new HashMap<>();
        metaA1.put("repositoryId", "repo-1");
        metaA1.put("filePath", "src/Service.java");
        metaA1.put("startLine", 10);
        metaA1.put("endLine", 30);
        metaA1.put("type", "METHOD");
        metaA1.put(RetrievalConstants.METADATA_DENSE_SCORE, 0.72);
        metaA1.put(RetrievalConstants.METADATA_RETRIEVAL_ROUTES, new LinkedHashSet<>(List.of(RetrievalConstants.ROUTE_VECTOR)));
        Document docA1 = new Document("id-A1", "content A", metaA1);

        // Document B (encountered at index 1)
        Map<String, Object> metaB = new HashMap<>();
        metaB.put("repositoryId", "repo-1");
        metaB.put("filePath", "src/Other.java");
        metaB.put("startLine", 5);
        metaB.put("endLine", 15);
        metaB.put("type", "CLASS");
        metaB.put(RetrievalConstants.METADATA_RETRIEVAL_ROUTES, new LinkedHashSet<>(List.of(RetrievalConstants.ROUTE_METADATA)));
        Document docB = new Document("id-B", "content B", metaB);

        // Document A duplicate (encountered at index 2 with higher dense score and different route)
        Map<String, Object> metaA2 = new HashMap<>();
        metaA2.put("repositoryId", "repo-1");
        metaA2.put("filePath", "src/Service.java");
        metaA2.put("startLine", 10);
        metaA2.put("endLine", 30);
        metaA2.put("type", "METHOD");
        metaA2.put(RetrievalConstants.METADATA_DENSE_SCORE, 0.95);
        metaA2.put(RetrievalConstants.METADATA_RETRIEVAL_ROUTES, new LinkedHashSet<>(List.of(RetrievalConstants.ROUTE_METADATA)));
        Document docA2 = new Document("id-A2", "content A duplicate", metaA2);

        Method deduplicateMethod = RetrievalStrategySelector.class.getDeclaredMethod("deduplicate", List.class);
        deduplicateMethod.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<Document> result = (List<Document>) deduplicateMethod.invoke(selector, List.of(docA1, docB, docA2));

        // First-wins: exactly 2 documents, A remains at index 0, B remains at index 1
        assertEquals(2, result.size());
        Document survivorA = result.get(0);
        Document survivorB = result.get(1);

        assertEquals("id-A1", survivorA.getId(), "First-wins encounter order must preserve original document identity");
        assertEquals("id-B", survivorB.getId(), "Original ordering of secondary documents must be preserved");

        // Merged dense score: max(0.72, 0.95) = 0.95
        Double mergedDenseScore = (Double) survivorA.getMetadata().get(RetrievalConstants.METADATA_DENSE_SCORE);
        assertNotNull(mergedDenseScore);
        assertEquals(0.95, mergedDenseScore, 0.0001);

        // Route union: contains both vector and metadata
        @SuppressWarnings("unchecked")
        Set<String> mergedRoutes = (Set<String>) survivorA.getMetadata().get(RetrievalConstants.METADATA_RETRIEVAL_ROUTES);
        assertNotNull(mergedRoutes);
        assertTrue(mergedRoutes.contains(RetrievalConstants.ROUTE_VECTOR));
        assertTrue(mergedRoutes.contains(RetrievalConstants.ROUTE_METADATA));
    }

    @Test
    @DisplayName("Deduplication safely handles empty metadata maps without NullPointerException")
    void testDeduplicateHandlesNullMetadata() throws Exception {
        Map<String, Object> m1 = new HashMap<>();
        m1.put("filePath", "src/File1.java");
        Document doc1 = Document.builder().id("id-null-1").text("text 1").metadata(m1).build();

        Map<String, Object> m2 = new HashMap<>();
        m2.put("filePath", "src/File2.java");
        Document doc2 = Document.builder().id("id-null-2").text("text 2").metadata(m2).build();

        Method deduplicateMethod = RetrievalStrategySelector.class.getDeclaredMethod("deduplicate", List.class);
        deduplicateMethod.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<Document> result = (List<Document>) deduplicateMethod.invoke(selector, List.of(doc1, doc2));

        assertNotNull(result);
        assertEquals(2, result.size());
    }

    @Test
    @DisplayName("Deduplication creates safe copies without mutating original shared or immutable metadata maps")
    void testDeduplicateDoesNotMutateSharedMaps() throws Exception {
        // Immutable map as metadata
        Map<String, Object> immutableMeta = Map.of(
                "repositoryId", "repo-shared",
                "filePath", "src/Shared.java",
                "startLine", 1,
                "endLine", 10,
                "type", "CLASS"
        );
        Document doc1 = new Document("shared-1", "content 1", immutableMeta);
        Document doc2 = new Document("shared-2", "content 2", immutableMeta);

        Method deduplicateMethod = RetrievalStrategySelector.class.getDeclaredMethod("deduplicate", List.class);
        deduplicateMethod.setAccessible(true);

        // Must not throw UnsupportedOperationException
        @SuppressWarnings("unchecked")
        List<Document> result = (List<Document>) deduplicateMethod.invoke(selector, List.of(doc1, doc2));

        assertEquals(1, result.size());
        // Verify original map remains unmodified
        assertFalse(immutableMeta.containsKey(RetrievalConstants.METADATA_RETRIEVAL_ROUTES));
    }

    @Test
    @DisplayName("performVectorSearch tags candidates with vector route and copies score to denseScore")
    void testPerformVectorSearchTagsVectorRoute() throws Exception {
        Map<String, Object> vecMeta = new HashMap<>();
        vecMeta.put("repositoryId", "repo-vec");
        Document mockDoc = Document.builder()
                .id("vec-1")
                .text("vector match content")
                .metadata(vecMeta)
                .score(0.88)
                .build();

        testVectorStore.defaultSearchResults = List.of(mockDoc);

        Method vectorSearchMethod = RetrievalStrategySelector.class.getDeclaredMethod(
                "performVectorSearch", String.class, int.class, String.class);
        vectorSearchMethod.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<Document> results = (List<Document>) vectorSearchMethod.invoke(selector, "query", 5, "repo-vec");

        assertFalse(results.isEmpty());
        Document returned = results.get(0);
        assertEquals(0.88, returned.getScore());
        assertEquals(0.88, returned.getMetadata().get(RetrievalConstants.METADATA_DENSE_SCORE));

        @SuppressWarnings("unchecked")
        Set<String> routes = (Set<String>) returned.getMetadata().get(RetrievalConstants.METADATA_RETRIEVAL_ROUTES);
        assertNotNull(routes);
        assertTrue(routes.contains(RetrievalConstants.ROUTE_VECTOR));
    }

    // ── Test doubles ──────────────────────────────────────────

    private static class RetrievalStrategyStrategyWrapper extends RetrievalStrategySelector {
        RetrievalStrategyStrategyWrapper(VectorStore vs, JdbcTemplate jt, SymbolExtractor se,
                                         IntentRetrievalConfig c, ArchitectureContextBuilder acb,
                                         ObjectMapper om, java.util.concurrent.Executor exec,
                                         LuceneGenerationStore ls, ReciprocalRankFusion rrf,
                                         RetrievalConfig rc) {
            super(vs, jt, se, c, acb, om, exec, ls, rrf, rc);
        }
    }

    private static class TestLuceneStore extends LuceneGenerationStore {
        List<Document> defaultSearchResults = List.of();
        boolean throwError = false;
        int callCount = 0;

        TestLuceneStore(RetrievalConfig config) {
            super(config, null, null);
        }

        @Override
        public List<Document> search(String repositoryId, String queryStr, int topK) {
            callCount++;
            if (throwError) {
                throw new RuntimeException("Simulated Lucene index failure");
            }
            return defaultSearchResults;
        }

        @Override
        public List<Document> searchUnscoped(String queryStr, int topK) {
            callCount++;
            if (throwError) {
                throw new RuntimeException("Simulated Lucene unscoped failure");
            }
            return defaultSearchResults;
        }
    }

    private static class TestVectorStore implements VectorStore {
        List<Document> defaultSearchResults = List.of();
        boolean throwError = false;
        int callCount = 0;

        @Override public void add(List<Document> documents) {}
        @Override public void delete(List<String> idList) {}
        @Override public void delete(org.springframework.ai.vectorstore.filter.Filter.Expression filterExpression) {}
        @Override
        public List<Document> similaritySearch(String query) {
            callCount++;
            if (throwError) {
                throw new RuntimeException("Simulated VectorStore failure");
            }
            return defaultSearchResults;
        }
        @Override
        public List<Document> similaritySearch(SearchRequest request) {
            callCount++;
            if (throwError) {
                throw new RuntimeException("Simulated VectorStore failure");
            }
            return defaultSearchResults;
        }
    }

    private static class TestJdbcTemplate extends JdbcTemplate {
        final List<String> executedQueries = new ArrayList<>();
        final List<Object[]> executedArgs = new ArrayList<>();
        final Map<String, Document> parentResponses = new HashMap<>();
        List<Document> defaultMethodResponse = List.of();
        List<Document> defaultClassResponse = List.of();
        List<Document> defaultPackageResponse = List.of();

        @Override
        @SuppressWarnings("unchecked")
        public <T> List<T> query(String sql, Object[] args, RowMapper<T> rowMapper) {
            executedQueries.add(sql);
            executedArgs.add(args != null ? args.clone() : new Object[0]);

            List<T> results = new ArrayList<>();
            if (sql.contains("metadata->>'chunkId' = ANY (?)")) {
                if (args != null && args.length > 0 && args[0] instanceof String[]) {
                    String[] ids = (String[]) args[0];
                    for (String id : ids) {
                        Document doc = parentResponses.get(id);
                        if (doc != null) {
                            results.add((T) doc);
                        }
                    }
                }
            } else if (sql.contains("metadata->>'packageName' = ?")) {
                results.addAll((List<T>) defaultPackageResponse);
            } else if (sql.contains("metadata->>'className' = ?")) {
                results.addAll((List<T>) defaultClassResponse);
            } else if (sql.contains("metadata->>'methodName' = ?") || sql.contains("metadata->>'elementName' LIKE ?")) {
                results.addAll((List<T>) defaultMethodResponse);
            }
            return results;
        }
    }
}

