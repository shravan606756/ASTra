package com.shravan.jcode_intelligence.service;

import java.util.List;
import java.util.Map;

/**
 * Manages indexed repositories inside the vector store.
 */
public interface RepositoryManagementService {

    /**
     * Lists all distinct repository IDs currently indexed.
     */
    List<String> listRepositories();

    /**
     * Removes all vectors associated with a specific repository ID.
     * @return true if vectors were removed, false if none existed.
     */
    boolean removeRepository(String repositoryId);

    /**
     * Saves statistics for a repository after indexing completes.
     */
    void saveRepositoryStats(String repositoryId, com.shravan.jcode_intelligence.model.IndexingStatistics stats);

    /**
     * Returns statistics about the indexed chunks for a repository.
     */
    com.shravan.jcode_intelligence.dto.response.RepositoryStatsDTO getRepositoryStats(String repositoryId);
}
