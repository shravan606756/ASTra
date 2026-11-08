package com.shravan.jcode_intelligence.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shravan.jcode_intelligence.config.IntentRetrievalConfig;
import com.shravan.jcode_intelligence.model.QueryIntent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentMetadata;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.lang.reflect.Method;
import java.sql.ResultSet;
import java.util.*;

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
        assertTrue(executedSql.contains("metadata->>'chunkId' IN (?,?)"), "SQL must use batched IN clause: " + executedSql);
        assertTrue(executedSql.contains("metadata->>'repositoryId' = ?"), "SQL must enforce repository isolation: " + executedSql);

        Object[] args = testJdbcTemplate.executedArgs.get(0);
        assertEquals(3, args.length);
        assertEquals("P1", args[0]);
        assertEquals("P2", args[1]);
        assertEquals("test-repo", args[2]);

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
        assertTrue(sql.contains("metadata->>'chunkId' IN (?)"));
        assertFalse(sql.contains("metadata->>'repositoryId' = ?"), "Unscoped query must omit repository filter");
        assertEquals(1, testJdbcTemplate.executedArgs.get(0).length);
        assertEquals("P1", testJdbcTemplate.executedArgs.get(0)[0]);
    }

    @Test
    @DisplayName("enrichWithParentContext deduplicates parent already present in documents")
    void testEnrichWithParentContext_parentAlreadyInDocuments() throws Exception {
        Map<String, Object> p1Meta = new HashMap<>();
        p1Meta.put("type", "CLASS");
        p1Meta.put("chunkId", "P1");
        p1Meta.put("repositoryId", "repo1");
        p1Meta.put("filePath", "src/Foo.java");
        p1Meta.put("startLine", 1);
        p1Meta.put("endLine", 100);

        Map<String, Object> m1 = new HashMap<>();
        m1.put("type", "METHOD");
        m1.put("parentChunkId", "P1");
        m1.put("repositoryId", "repo1");
        m1.put("filePath", "src/Foo.java");
        m1.put("startLine", 10);
        m1.put("endLine", 20);

        Document parentDoc = new Document("class Foo", p1Meta);
        Document memberDoc = new Document("method foo", m1);

        testJdbcTemplate.parentResponses.put("P1", new Document("class Foo duplicate", p1Meta));

        Method enrichMethod = RetrievalStrategySelector.class.getDeclaredMethod("enrichWithParentContext", List.class, String.class);
        enrichMethod.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<Document> result = (List<Document>) enrichMethod.invoke(selector, List.of(parentDoc, memberDoc), "repo1");

        // Parent should not be duplicated
        assertEquals(2, result.size());
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

    // ── Test doubles ──────────────────────────────────────────

    private static class TestJdbcTemplate extends JdbcTemplate {
        final List<String> executedQueries = new ArrayList<>();
        final List<Object[]> executedArgs = new ArrayList<>();
        final Map<String, Document> parentResponses = new HashMap<>();

        @Override
        @SuppressWarnings("unchecked")
        public <T> List<T> query(String sql, Object[] args, RowMapper<T> rowMapper) {
            executedQueries.add(sql);
            executedArgs.add(args != null ? args.clone() : new Object[0]);

            List<T> results = new ArrayList<>();
            if (sql.contains("metadata->>'chunkId' IN")) {
                if (args != null) {
                    for (Object arg : args) {
                        String id = String.valueOf(arg);
                        Document doc = parentResponses.get(id);
                        if (doc != null) {
                            results.add((T) doc);
                        }
                    }
                }
            }
            return results;
        }
    }

    private static class TestVectorStore implements VectorStore {
        @Override public void add(List<Document> documents) {}
        @Override public void delete(List<String> idList) {}
        @Override public void delete(org.springframework.ai.vectorstore.filter.Filter.Expression filterExpression) {}
        @Override public List<Document> similaritySearch(String query) { return List.of(); }
        @Override public List<Document> similaritySearch(SearchRequest request) { return List.of(); }
    }
}

