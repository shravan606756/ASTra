package com.shravan.jcode_intelligence.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.lang.reflect.Method;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ArchitectureContextBuilderTest {

    @Test
    @DisplayName("retrieveOrchestrationMethods pushes candidate filters and LIMIT 50 into SQL")
    void testRetrieveOrchestrationMethods_pushesDownFilters() throws Exception {
        MockJdbcTemplate mockJdbc = new MockJdbcTemplate();
        ArchitectureContextBuilder builder = new ArchitectureContextBuilder(mockJdbc);

        Method m = ArchitectureContextBuilder.class.getDeclaredMethod("retrieveOrchestrationMethods", String.class);
        m.setAccessible(true);
        m.invoke(builder, "test-repo");

        assertFalse(mockJdbc.executedQueries.isEmpty());
        String sql = mockJdbc.executedQueries.get(0);

        assertTrue(sql.startsWith("SELECT content, metadata FROM vector_store"), "Must project content and metadata: " + sql);
        assertFalse(sql.contains("SELECT id, content, metadata"), "Must not project id: " + sql);
        assertTrue(sql.contains("metadata->>'type' IN ('METHOD', 'CONSTRUCTOR')"), "Must filter method/constructor types: " + sql);
        assertTrue(sql.contains("ILIKE 'main'"), "Must match main case-insensitively: " + sql);
        assertTrue(sql.contains("LIKE 'execute%'"), "Must match execute prefix: " + sql);
        assertTrue(sql.contains("LIKE 'create%'"), "Must match create prefix: " + sql);
        assertTrue(sql.contains("ILIKE '%bean%'"), "Must match @Bean annotations: " + sql);
        assertTrue(sql.contains("metadata->>'repositoryId' = ?"), "Must enforce repository filter: " + sql);
        assertTrue(sql.contains("LIMIT 50"), "Must include candidate limit: " + sql);
    }

    @Test
    @DisplayName("retrieveOrchestrationMethods filters out accessors and caps at 10 items")
    void testRetrieveOrchestrationMethods_filtersAccessorsAndCapsAt10() throws Exception {
        MockJdbcTemplate mockJdbc = new MockJdbcTemplate();

        // 3 accessors + 12 orchestration methods
        List<Document> candidates = new ArrayList<>();

        Map<String, Object> getter = new HashMap<>();
        getter.put("elementName", "getValue");
        candidates.add(new Document("getter", getter));

        Map<String, Object> setter = new HashMap<>();
        setter.put("elementName", "setValue");
        candidates.add(new Document("setter", setter));

        Map<String, Object> toStringMeta = new HashMap<>();
        toStringMeta.put("elementName", "toString");
        candidates.add(new Document("toString", toStringMeta));

        for (int i = 1; i <= 12; i++) {
            Map<String, Object> meta = new HashMap<>();
            meta.put("elementName", "executeJob" + i);
            candidates.add(new Document("executeJob" + i, meta));
        }

        mockJdbc.returnDocs = candidates;

        ArchitectureContextBuilder builder = new ArchitectureContextBuilder(mockJdbc);
        Method m = ArchitectureContextBuilder.class.getDeclaredMethod("retrieveOrchestrationMethods", String.class);
        m.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<Document> result = (List<Document>) m.invoke(builder, "test-repo");

        assertEquals(10, result.size(), "Orchestration methods must cap at 10 items");
        for (Document doc : result) {
            String name = String.valueOf(doc.getMetadata().get("elementName"));
            assertFalse(name.startsWith("get"), "Getters must be excluded: " + name);
            assertFalse(name.startsWith("set"), "Setters must be excluded: " + name);
            assertNotEquals("toString", name, "toString must be excluded: " + name);
        }
    }

    @Test
    @DisplayName("Package and component queries omit id column projection")
    void testStructuralQueriesOmitId() throws Exception {
        MockJdbcTemplate mockJdbc = new MockJdbcTemplate();
        ArchitectureContextBuilder builder = new ArchitectureContextBuilder(mockJdbc);

        Method mPkg = ArchitectureContextBuilder.class.getDeclaredMethod("retrieveAllPackageSummaries", String.class);
        mPkg.setAccessible(true);
        mPkg.invoke(builder, "repo1");

        Method mComp = ArchitectureContextBuilder.class.getDeclaredMethod("retrieveComponentsByRole", String.class);
        mComp.setAccessible(true);
        mComp.invoke(builder, "repo1");

        Method mIface = ArchitectureContextBuilder.class.getDeclaredMethod("retrieveArchitecturalInterfaces", String.class);
        mIface.setAccessible(true);
        mIface.invoke(builder, "repo1");

        for (String sql : mockJdbc.executedQueries) {
            assertTrue(sql.startsWith("SELECT content, metadata FROM vector_store"), "Must omit id: " + sql);
            assertFalse(sql.contains("SELECT id, content, metadata"), "Must not project id: " + sql);
        }
    }

    @Test
    @DisplayName("parseMetadataJson handles valid and malformed JSON safely")
    void testParseMetadataJson() throws Exception {
        ArchitectureContextBuilder builder = new ArchitectureContextBuilder(new MockJdbcTemplate());
        Method m = ArchitectureContextBuilder.class.getDeclaredMethod("parseMetadataJson", String.class);
        m.setAccessible(true);

        @SuppressWarnings("unchecked")
        Map<String, Object> valid = (Map<String, Object>) m.invoke(builder, "{\"role\":\"CONTROLLER\"}");
        assertEquals("CONTROLLER", valid.get("role"));

        @SuppressWarnings("unchecked")
        Map<String, Object> malformed = (Map<String, Object>) m.invoke(builder, "not json");
        assertNotNull(malformed);
        assertTrue(malformed.isEmpty());
    }

    private static class MockJdbcTemplate extends JdbcTemplate {
        final List<String> executedQueries = new ArrayList<>();
        List<Document> returnDocs = List.of();

        @Override
        @SuppressWarnings("unchecked")
        public <T> List<T> query(String sql, Object[] args, RowMapper<T> rowMapper) {
            executedQueries.add(sql);
            return (List<T>) returnDocs;
        }
    }
}

