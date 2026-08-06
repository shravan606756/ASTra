package com.shravan.jcode_intelligence.service.impl;

import com.shravan.jcode_intelligence.service.RepositoryManagementService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class RepositoryManagementServiceImpl implements RepositoryManagementService {

    private final JdbcTemplate jdbcTemplate;

    public RepositoryManagementServiceImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
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
                indexing_time_ms BIGINT
            )
        """;
        jdbcTemplate.execute(createTableSql);
    }

    @Override
    public List<String> listRepositories() {
        // Also ensure repositories are returned if they exist in vector_store OR repository_statistics
        String sql = "SELECT DISTINCT repository_id FROM repository_statistics UNION SELECT DISTINCT metadata->>'repositoryId' FROM vector_store WHERE metadata->>'repositoryId' IS NOT NULL";
        return jdbcTemplate.queryForList(sql, String.class);
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public boolean removeRepository(String repositoryId) {
        String sqlVector = "DELETE FROM vector_store WHERE metadata->>'repositoryId' = ?";
        int deletedVectors = jdbcTemplate.update(sqlVector, repositoryId);
        
        String sqlStats = "DELETE FROM repository_statistics WHERE repository_id = ?";
        jdbcTemplate.update(sqlStats, repositoryId);
        
        return deletedVectors > 0;
    }

    @Override
    public void saveRepositoryStats(String repositoryId, com.shravan.jcode_intelligence.model.IndexingStatistics stats) {
        String sql = """
            INSERT INTO repository_statistics (
                repository_id, total_chunks, packages, classes, interfaces, enums, records, fields, constructors, methods, fragments,
                largest_class, largest_method, indexing_time_ms
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                indexing_time_ms = EXCLUDED.indexing_time_ms
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
            stats.getIndexingDurationMs()
        );
    }

    @Override
    public com.shravan.jcode_intelligence.dto.response.RepositoryStatsDTO getRepositoryStats(String repositoryId) {
        String sql = "SELECT * FROM repository_statistics WHERE repository_id = ?";
        
        return jdbcTemplate.query(sql, rs -> {
            if (rs.next()) {
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
                    rs.getLong("indexing_time_ms")
                );
            }
            return new com.shravan.jcode_intelligence.dto.response.RepositoryStatsDTO();
        }, repositoryId);
    }
}
