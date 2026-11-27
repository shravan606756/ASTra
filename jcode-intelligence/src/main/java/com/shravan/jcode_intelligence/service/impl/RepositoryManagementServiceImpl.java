package com.shravan.jcode_intelligence.service.impl;

import com.shravan.jcode_intelligence.service.RepositoryConcurrencyManager;
import com.shravan.jcode_intelligence.service.RepositoryManagementService;
import com.shravan.jcode_intelligence.sparse.store.LuceneGenerationStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

@Service
public class RepositoryManagementServiceImpl implements RepositoryManagementService {

    private static final Logger log = LoggerFactory.getLogger(RepositoryManagementServiceImpl.class);

    private final JdbcTemplate jdbcTemplate;
    private final RepositoryConcurrencyManager concurrencyManager;
    private final LuceneGenerationStore luceneGenerationStore;

    public RepositoryManagementServiceImpl(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, new RepositoryConcurrencyManager(), null);
    }

    public RepositoryManagementServiceImpl(JdbcTemplate jdbcTemplate,
            RepositoryConcurrencyManager concurrencyManager) {
        this(jdbcTemplate, concurrencyManager, null);
    }

    @Autowired
    public RepositoryManagementServiceImpl(JdbcTemplate jdbcTemplate,
            @Autowired(required = false) RepositoryConcurrencyManager concurrencyManager,
            @Autowired(required = false) LuceneGenerationStore luceneGenerationStore) {
        this.jdbcTemplate = jdbcTemplate;
        this.concurrencyManager = concurrencyManager != null ? concurrencyManager : new RepositoryConcurrencyManager();
        this.luceneGenerationStore = luceneGenerationStore;
    }

    @jakarta.annotation.PostConstruct
    public void init() {
        String createTableSql = """
                    CREATE TABLE IF NOT EXISTS repository_statistics (
                        repository_id VARCHAR PRIMARY KEY,
                        total_chunks INT,
                        packages INT,
                        classes INT,
                        interfaces INT,
                        enums INT,
                        records INT,
                        fields INT,
                        constructors INT,
                        methods INT,
                        fragments INT,
                        largest_class VARCHAR,
                        largest_method VARCHAR,
                        indexing_time_ms BIGINT,
                        status VARCHAR DEFAULT 'CONSISTENT'
                    )
                """;
        jdbcTemplate.execute(createTableSql);
        try {
            jdbcTemplate.execute("ALTER TABLE repository_statistics ADD COLUMN IF NOT EXISTS status VARCHAR DEFAULT 'CONSISTENT'");
        } catch (Exception ignored) {
            // For in-memory or databases where column already exists
        }
    }

    @Override
    public List<String> listRepositories() {
        // Also ensure repositories are returned if they exist in vector_store OR
        // repository_statistics OR sparse index
        String sql = "SELECT DISTINCT repository_id FROM repository_statistics UNION SELECT DISTINCT metadata->>'repositoryId' FROM vector_store WHERE metadata->>'repositoryId' IS NOT NULL";
        List<String> sqlRepos = jdbcTemplate.queryForList(sql, String.class);
        if (luceneGenerationStore != null) {
            Set<String> allRepos = new TreeSet<>(sqlRepos);
            allRepos.addAll(luceneGenerationStore.listRepositories());
            return new ArrayList<>(allRepos);
        }
        return sqlRepos;
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public boolean removeRepository(String repositoryId) {
        if (repositoryId == null || repositoryId.isBlank()) {
            throw new IllegalArgumentException("repositoryId must not be null or blank for repository deletion");
        }
        String safeRepoId = repositoryId.trim();
        if (concurrencyManager != null) {
            concurrencyManager.acquireOrThrow(safeRepoId);
        }
        try {
            boolean isSafeSparseId = LuceneGenerationStore.isValidRepositoryId(safeRepoId);
            // Check sparse existence before SQL deletion only if ID is safe for sparse filesystem store
            boolean sparseExisted = isSafeSparseId && (luceneGenerationStore != null) && luceneGenerationStore.hasIndex(safeRepoId);

            String sqlVector = "DELETE FROM vector_store WHERE metadata->>'repositoryId' = ?";
            int deletedVectors = jdbcTemplate.update(sqlVector, safeRepoId);

            String sqlStats = "DELETE FROM repository_statistics WHERE repository_id = ?";
            int deletedStats = jdbcTemplate.update(sqlStats, safeRepoId);

            boolean removed = deletedVectors > 0 || deletedStats > 0 || sparseExisted;

            // Delete sparse data after commit, or immediately without an active transaction, only if safe for sparse store
            if (removed && isSafeSparseId && luceneGenerationStore != null) {
                if (TransactionSynchronizationManager.isActualTransactionActive()) {
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            luceneGenerationStore.deleteRepositoryIndex(safeRepoId);
                        }
                    });
                } else {
                    luceneGenerationStore.deleteRepositoryIndex(safeRepoId);
                }
            }

            return removed;
        } finally {
            if (concurrencyManager != null) {
                concurrencyManager.release(safeRepoId);
            }
        }
    }

    private String normalizeRepoId(String repositoryId) {
        if (repositoryId == null || repositoryId.isBlank()) {
            throw new IllegalArgumentException("repositoryId must not be null or blank");
        }
        return repositoryId.trim();
    }

    @Override
    public void saveRepositoryStats(String repositoryId,
            com.shravan.jcode_intelligence.model.IndexingStatistics stats) {
        String sql = """
                    INSERT INTO repository_statistics (
                        repository_id, total_chunks, packages, classes, interfaces, enums, records, fields, constructors, methods, fragments,
                        largest_class, largest_method, indexing_time_ms, status
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'CONSISTENT')
                    ON CONFLICT (repository_id) DO UPDATE SET
                        total_chunks = EXCLUDED.total_chunks,
                        packages = EXCLUDED.packages,
                        classes = EXCLUDED.classes,
                        interfaces = EXCLUDED.interfaces,
                        enums = EXCLUDED.enums,
                        records = EXCLUDED.records,
                        fields = EXCLUDED.fields,
                        constructors = EXCLUDED.constructors,
                        methods = EXCLUDED.methods,
                        fragments = EXCLUDED.fragments,
                        largest_class = EXCLUDED.largest_class,
                        largest_method = EXCLUDED.largest_method,
                        indexing_time_ms = EXCLUDED.indexing_time_ms,
                        status = 'CONSISTENT'
                """;

        jdbcTemplate.update(sql,
                repositoryId,
                stats.getTotalChunks(),
                stats.getPackages(),
                stats.getClasses(),
                stats.getInterfaces(),
                stats.getEnums(),
                stats.getRecords(),
                stats.getFields(),
                stats.getConstructors(),
                stats.getMethods(),
                stats.getFragments(),
                stats.getLargestClassName(),
                stats.getLargestMethodName(),
                stats.getIndexingDurationMs());
    }

    @Override
    public com.shravan.jcode_intelligence.dto.response.RepositoryStatsDTO getRepositoryStats(String repositoryId) {
        if (repositoryId == null || repositoryId.isBlank()) {
            throw new IllegalArgumentException("repositoryId must not be null or blank");
        }
        String safeRepoId = repositoryId.trim();
        String sql = "SELECT * FROM repository_statistics WHERE repository_id = ?";

        return jdbcTemplate.query(sql, rs -> {
            if (rs.next()) {
                String status = "CONSISTENT";
                try {
                    String colVal = rs.getString("status");
                    if (colVal != null && !colVal.isBlank()) {
                        status = colVal;
                    }
                } catch (Exception ignored) {}

                return new com.shravan.jcode_intelligence.dto.response.RepositoryStatsDTO(
                        rs.getInt("packages"),
                        rs.getInt("classes"),
                        rs.getInt("interfaces"),
                        rs.getInt("enums"),
                        rs.getInt("records"),
                        rs.getInt("fields"),
                        rs.getInt("constructors"),
                        rs.getInt("methods"),
                        rs.getInt("fragments"),
                        rs.getInt("total_chunks"),
                        rs.getString("largest_class"),
                        rs.getString("largest_method"),
                        rs.getLong("indexing_time_ms"),
                        status);
            }
            return new com.shravan.jcode_intelligence.dto.response.RepositoryStatsDTO();
        }, safeRepoId);
    }

    @Override
    public void markRepositoryInconsistent(String repositoryId, String reason) {
        if (repositoryId == null || repositoryId.isBlank()) {
            return;
        }
        String safeRepoId = repositoryId.trim();
        log.warn("[REPOSITORY INCONSISTENT] Marking repository '{}' as INCONSISTENT: {}", safeRepoId, reason);
        String sql = """
                INSERT INTO repository_statistics (repository_id, status)
                VALUES (?, 'INCONSISTENT')
                ON CONFLICT (repository_id) DO UPDATE SET status = 'INCONSISTENT'
                """;
        try {
            jdbcTemplate.update(sql, safeRepoId);
        } catch (Exception e) {
            log.error("Failed to mark repository '{}' as INCONSISTENT: {}", safeRepoId, e.getMessage(), e);
        }
    }

    @Override
    public int deleteRepositoryVectors(String repositoryId) {
        if (repositoryId == null || repositoryId.isBlank()) {
            return 0;
        }
        String safeRepoId = repositoryId.trim();
        String sql = "DELETE FROM vector_store WHERE metadata->>'repositoryId' = ?";
        return jdbcTemplate.update(sql, safeRepoId);
    }

    @Override
    public int deleteVectorsByIds(java.util.Collection<String> vectorIds) {
        if (vectorIds == null || vectorIds.isEmpty()) {
            return 0;
        }
        List<String> validIds = vectorIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        if (validIds.isEmpty()) {
            return 0;
        }
        String sql = "DELETE FROM vector_store WHERE id = ?::uuid";
        int[][] batchResults = jdbcTemplate.batchUpdate(sql, validIds, 500, (ps, id) -> ps.setString(1, id));
        int totalDeleted = 0;
        for (int[] batch : batchResults) {
            for (int count : batch) {
                if (count > 0) {
                    totalDeleted += count;
                }
            }
        }
        return totalDeleted;
    }

    @Override
    public Set<String> getRepositoryVectorIds(String repositoryId) {
        if (repositoryId == null || repositoryId.isBlank()) {
            return Collections.emptySet();
        }
        String safeRepoId = repositoryId.trim();
        String sql = "SELECT id::text FROM vector_store WHERE metadata->>'repositoryId' = ?";
        try {
            List<String> ids = jdbcTemplate.query(sql, (rs, rowNum) -> rs.getString(1), safeRepoId);
            return new HashSet<>(ids);
        } catch (Exception e) {
            log.error("Failed to retrieve existing vector IDs for repository '{}': {}", safeRepoId, e.getMessage(), e);
            if (e instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException("Failed to retrieve existing vector IDs for repository " + safeRepoId, e);
        }
    }
}
