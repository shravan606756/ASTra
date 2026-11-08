package com.shravan.jcode_intelligence.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Initializes composite B-tree expression indexes and pattern operator indexes on {@code vector_store}
 * metadata fields once the application is ready and Spring AI has initialized the underlying schema.
 */
@Component
public class VectorStoreIndexInitializer {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreIndexInitializer.class);

    private static final List<String> INDEX_STATEMENTS = List.of(
            "CREATE INDEX IF NOT EXISTS idx_vector_store_repo_chunk_id ON vector_store ((metadata->>'repositoryId'), (metadata->>'chunkId'))",
            "CREATE INDEX IF NOT EXISTS idx_vector_store_repo_type ON vector_store ((metadata->>'repositoryId'), (metadata->>'type'))",
            "CREATE INDEX IF NOT EXISTS idx_vector_store_repo_class_name ON vector_store ((metadata->>'repositoryId'), (metadata->>'className'))",
            "CREATE INDEX IF NOT EXISTS idx_vector_store_repo_element_name ON vector_store ((metadata->>'repositoryId'), (metadata->>'elementName'))",
            "CREATE INDEX IF NOT EXISTS idx_vector_store_repo_method_name ON vector_store ((metadata->>'repositoryId'), (metadata->>'methodName'))",
            "CREATE INDEX IF NOT EXISTS idx_vector_store_repo_package_name ON vector_store ((metadata->>'repositoryId'), (metadata->>'packageName'))",
            "CREATE INDEX IF NOT EXISTS idx_vector_store_repo_element_pattern ON vector_store ((metadata->>'repositoryId'), (metadata->>'elementName') varchar_pattern_ops)"
    );

    private final JdbcTemplate jdbcTemplate;

    /**
     * Constructs a {@code VectorStoreIndexInitializer} with the given JDBC template.
     *
     * @param jdbcTemplate Spring JDBC template used for database operations
     */
    public VectorStoreIndexInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Executes the idempotent index creation statements if the {@code vector_store} table exists.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void initializeIndexes() {
        if (jdbcTemplate == null) {
            return;
        }

        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'vector_store'",
                    Integer.class
            );

            if (count == null || count == 0) {
                log.info("Table 'vector_store' does not exist yet; skipping index initialization");
                return;
            }

            log.info("Verifying and establishing composite metadata indexes on 'vector_store'...");
            for (String sql : INDEX_STATEMENTS) {
                try {
                    jdbcTemplate.execute(sql);
                } catch (Exception e) {
                    log.warn("Failed to create index statement [{}]: {}", sql, e.getMessage());
                }
            }
            log.info("Composite metadata indexes on 'vector_store' established successfully");
        } catch (Exception e) {
            log.warn("Could not verify or initialize vector_store indexes: {}", e.getMessage());
        }
    }

    /**
     * Package-private getter for testing statement definitions.
     *
     * @return immutable list of index creation SQL statements
     */
    static List<String> getIndexStatements() {
        return INDEX_STATEMENTS;
    }
}

