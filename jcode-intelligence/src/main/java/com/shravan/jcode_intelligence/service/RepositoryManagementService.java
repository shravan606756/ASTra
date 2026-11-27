package com.shravan.jcode_intelligence.service;

import java.util.Collection;
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

    /**
     * Marks a repository as inconsistent in repository_statistics when stores diverge or indexing fails.
     */
    default void markRepositoryInconsistent(String repositoryId, String reason) {
        // Default no-op for backward compatibility with existing test implementations
    }

    /**
     * Deletes existing vector store records for the specified repository ID.
     * @return count of deleted vector rows
     */
    default int deleteRepositoryVectors(String repositoryId) {
        return 0;
    }

    /**
     * Deletes specific vector store records by their IDs.
     * @param vectorIds collection of vector record UUIDs to delete
     * @return count of deleted vector rows
     */
    default int deleteVectorsByIds(Collection<String> vectorIds) {
        return 0;
    }
}
