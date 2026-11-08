package com.shravan.jcode_intelligence.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VectorStoreIndexInitializerTest {

    @Test
    @DisplayName("Statement definitions contain all required composite B-tree and pattern expression indexes")
    void testIndexStatementsCoverAllRequiredFields() {
        List<String> statements = VectorStoreIndexInitializer.getIndexStatements();
        assertEquals(7, statements.size());

        assertTrue(statements.stream().anyMatch(s -> s.contains("idx_vector_store_repo_chunk_id")
                && s.contains("(metadata->>'repositoryId')") && s.contains("(metadata->>'chunkId')")));
        assertTrue(statements.stream().anyMatch(s -> s.contains("idx_vector_store_repo_type")
                && s.contains("(metadata->>'type')")));
        assertTrue(statements.stream().anyMatch(s -> s.contains("idx_vector_store_repo_class_name")
                && s.contains("(metadata->>'className')")));
        assertTrue(statements.stream().anyMatch(s -> s.contains("idx_vector_store_repo_element_name")
                && s.contains("(metadata->>'elementName')")));
        assertTrue(statements.stream().anyMatch(s -> s.contains("idx_vector_store_repo_method_name")
                && s.contains("(metadata->>'methodName')")));
        assertTrue(statements.stream().anyMatch(s -> s.contains("idx_vector_store_repo_package_name")
                && s.contains("(metadata->>'packageName')")));
        assertTrue(statements.stream().anyMatch(s -> s.contains("idx_vector_store_repo_element_pattern")
                && s.contains("varchar_pattern_ops")));
    }

    @Test
    @DisplayName("initializeIndexes skips execution when vector_store table does not exist")
    void testSkipsWhenTableDoesNotExist() {
        MockJdbcTemplate mockJdbc = new MockJdbcTemplate(false);
        VectorStoreIndexInitializer initializer = new VectorStoreIndexInitializer(mockJdbc);

        initializer.initializeIndexes();

        assertTrue(mockJdbc.executedStatements.isEmpty(), "No index DDL must execute when table is absent");
    }

    @Test
    @DisplayName("initializeIndexes executes all index statements when vector_store table exists")
    void testExecutesAllWhenTableExists() {
        MockJdbcTemplate mockJdbc = new MockJdbcTemplate(true);
        VectorStoreIndexInitializer initializer = new VectorStoreIndexInitializer(mockJdbc);

        initializer.initializeIndexes();

        assertEquals(7, mockJdbc.executedStatements.size(), "All 7 index creation statements must execute");
        for (String sql : mockJdbc.executedStatements) {
            assertTrue(sql.startsWith("CREATE INDEX IF NOT EXISTS"));
        }
    }

    @Test
    @DisplayName("initializeIndexes tolerates database errors gracefully without throwing")
    void testHandlesExceptionsGracefully() {
        JdbcTemplate failingJdbc = new JdbcTemplate() {
            @Override
            public <T> T queryForObject(String sql, Class<T> requiredType) {
                throw new org.springframework.dao.DataAccessResourceFailureException("DB down");
            }
        };
        VectorStoreIndexInitializer initializer = new VectorStoreIndexInitializer(failingJdbc);
        assertDoesNotThrow(initializer::initializeIndexes);
    }

    private static class MockJdbcTemplate extends JdbcTemplate {
        private final boolean tableExists;
        final List<String> executedStatements = new ArrayList<>();

        MockJdbcTemplate(boolean tableExists) {
            this.tableExists = tableExists;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T queryForObject(String sql, Class<T> requiredType) {
            if (sql.contains("information_schema.tables")) {
                return (T) Integer.valueOf(tableExists ? 1 : 0);
            }
            return null;
        }

        @Override
        public void execute(String sql) throws DataAccessException {
            executedStatements.add(sql);
        }
    }
}

