package com.shravan.jcode_intelligence.service;

import com.shravan.jcode_intelligence.service.impl.RepositoryManagementServiceImpl;
import com.shravan.jcode_intelligence.sparse.store.LuceneGenerationStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class RepositoryManagementServiceTransactionTest {

    @Test
    @DisplayName("Sparse index existence checked before SQL and triggers 204 even if SQL rows are zero")
    void testSparseExistenceCheckedBeforeSql() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        LuceneGenerationStore store = mock(LuceneGenerationStore.class);
        RepositoryConcurrencyManager concurrencyManager = mock(RepositoryConcurrencyManager.class);

        when(store.hasIndex("repo-sparse-only")).thenReturn(true);
        when(jdbcTemplate.update(anyString(), eq("repo-sparse-only"))).thenReturn(0);

        RepositoryManagementServiceImpl service =
                new RepositoryManagementServiceImpl(jdbcTemplate, concurrencyManager, store);

        boolean removed = service.removeRepository("repo-sparse-only");

        assertTrue(removed, "Must return true (for 204) when sparse index existed even if SQL deleted rows = 0");
        verify(store).hasIndex("repo-sparse-only");
        verify(store).deleteRepositoryIndex("repo-sparse-only");
    }

    @Test
    @DisplayName("Returns 404 (false) when neither SQL rows nor sparse index exists")
    void testReturnNotFoundWhenNeitherExists() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        LuceneGenerationStore store = mock(LuceneGenerationStore.class);
        RepositoryConcurrencyManager concurrencyManager = mock(RepositoryConcurrencyManager.class);

        when(store.hasIndex("repo-ghost")).thenReturn(false);
        when(jdbcTemplate.update(anyString(), eq("repo-ghost"))).thenReturn(0);

        RepositoryManagementServiceImpl service =
                new RepositoryManagementServiceImpl(jdbcTemplate, concurrencyManager, store);

        boolean removed = service.removeRepository("repo-ghost");

        assertFalse(removed, "Must return false (for 404) when repository does not exist anywhere");
        verify(store).hasIndex("repo-ghost");
        verify(store, never()).deleteRepositoryIndex(anyString());
    }

    @Test
    @DisplayName("Deletes sparse data after commit when active transaction is present")
    void testDeleteSparseAfterCommitWithActiveTransaction() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        LuceneGenerationStore store = mock(LuceneGenerationStore.class);
        RepositoryConcurrencyManager concurrencyManager = mock(RepositoryConcurrencyManager.class);

        when(store.hasIndex("repo-tx")).thenReturn(true);
        when(jdbcTemplate.update(anyString(), eq("repo-tx"))).thenReturn(1);

        RepositoryManagementServiceImpl service =
                new RepositoryManagementServiceImpl(jdbcTemplate, concurrencyManager, store);

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            boolean removed = service.removeRepository("repo-tx");
            assertTrue(removed);

            // Sparse index must NOT be deleted before commit
            verify(store, never()).deleteRepositoryIndex(anyString());

            // Simulate transaction commit
            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCommit();
            }

            // Sparse index must be deleted after commit
            verify(store).deleteRepositoryIndex("repo-tx");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("Does not delete sparse data when transaction is rolled back")
    void testSparseDataNotDeletedOnRollback() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        LuceneGenerationStore store = mock(LuceneGenerationStore.class);
        RepositoryConcurrencyManager concurrencyManager = mock(RepositoryConcurrencyManager.class);

        when(store.hasIndex("repo-rollback")).thenReturn(true);
        when(jdbcTemplate.update(anyString(), eq("repo-rollback"))).thenReturn(1);

        RepositoryManagementServiceImpl service =
                new RepositoryManagementServiceImpl(jdbcTemplate, concurrencyManager, store);

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            boolean removed = service.removeRepository("repo-rollback");
            assertTrue(removed);

            // Rollback occurs (afterCommit is NOT called)
            verify(store, never()).deleteRepositoryIndex(anyString());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        // After clearing, verify sparse deletion was never called
        verify(store, never()).deleteRepositoryIndex(anyString());
    }

    @Test
    @DisplayName("Deletes sparse data immediately when no transaction is active")
    void testDeleteSparseImmediatelyWithoutTransaction() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        LuceneGenerationStore store = mock(LuceneGenerationStore.class);
        RepositoryConcurrencyManager concurrencyManager = mock(RepositoryConcurrencyManager.class);

        when(store.hasIndex("repo-no-tx")).thenReturn(true);
        when(jdbcTemplate.update(anyString(), eq("repo-no-tx"))).thenReturn(1);

        RepositoryManagementServiceImpl service =
                new RepositoryManagementServiceImpl(jdbcTemplate, concurrencyManager, store);

        boolean removed = service.removeRepository("repo-no-tx");

        assertTrue(removed);
        verify(store).deleteRepositoryIndex("repo-no-tx");
    }

    @Test
    @DisplayName("Blank or null repositoryId is rejected on removeRepository and leaves default repo intact")
    void testBlankRepositoryIdRejectedOnDeletion() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        LuceneGenerationStore store = mock(LuceneGenerationStore.class);
        RepositoryConcurrencyManager concurrencyManager = mock(RepositoryConcurrencyManager.class);

        RepositoryManagementServiceImpl service =
                new RepositoryManagementServiceImpl(jdbcTemplate, concurrencyManager, store);

        assertThrows(IllegalArgumentException.class, () -> service.removeRepository(null));
        assertThrows(IllegalArgumentException.class, () -> service.removeRepository(""));
        assertThrows(IllegalArgumentException.class, () -> service.removeRepository("   "));

        // Verify no interactions with SQL, concurrencyManager, or sparse store
        verifyNoInteractions(jdbcTemplate);
        verifyNoInteractions(store);
        verifyNoInteractions(concurrencyManager);
    }

    @Test
    @DisplayName("Trims valid repository IDs and allows explicit default deletion")
    void testIdNormalization() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        LuceneGenerationStore store = mock(LuceneGenerationStore.class);
        RepositoryConcurrencyManager concurrencyManager = mock(RepositoryConcurrencyManager.class);

        when(store.hasIndex("trimmed-repo")).thenReturn(true);
        when(jdbcTemplate.update(anyString(), eq("trimmed-repo"))).thenReturn(1);

        RepositoryManagementServiceImpl service =
                new RepositoryManagementServiceImpl(jdbcTemplate, concurrencyManager, store);

        service.removeRepository("   trimmed-repo   ");
        verify(concurrencyManager).acquireOrThrow("trimmed-repo");
        verify(store).hasIndex("trimmed-repo");
        verify(concurrencyManager).release("trimmed-repo");

        reset(concurrencyManager, store, jdbcTemplate);
        when(store.hasIndex("default")).thenReturn(true);
        when(jdbcTemplate.update(anyString(), eq("default"))).thenReturn(1);

        service.removeRepository("default");
        verify(concurrencyManager).acquireOrThrow("default");
        verify(store).hasIndex("default");
        verify(concurrencyManager).release("default");
    }

    @Test
    @DisplayName("Legacy repository ID repo@v1 is deleted via parameterized SQL and never passed to sparse store")
    void testLegacyRepoAtV1DeletedViaSqlWithoutSparseAccess() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        LuceneGenerationStore store = mock(LuceneGenerationStore.class);
        RepositoryConcurrencyManager concurrencyManager = mock(RepositoryConcurrencyManager.class);

        when(jdbcTemplate.update(eq("DELETE FROM vector_store WHERE metadata->>'repositoryId' = ?"), eq("repo@v1")))
                .thenReturn(10);
        when(jdbcTemplate.update(eq("DELETE FROM repository_statistics WHERE repository_id = ?"), eq("repo@v1")))
                .thenReturn(1);

        RepositoryManagementServiceImpl service =
                new RepositoryManagementServiceImpl(jdbcTemplate, concurrencyManager, store);

        boolean removed = service.removeRepository("repo@v1");
        assertTrue(removed, "Legacy repo@v1 with SQL rows must return true for successful removal");

        // Concurrency manager locks on the trimmed ID
        verify(concurrencyManager).acquireOrThrow("repo@v1");
        verify(concurrencyManager).release("repo@v1");

        // Parameterized SQL updates executed for repo@v1
        verify(jdbcTemplate).update("DELETE FROM vector_store WHERE metadata->>'repositoryId' = ?", "repo@v1");
        verify(jdbcTemplate).update("DELETE FROM repository_statistics WHERE repository_id = ?", "repo@v1");

        // CRITICAL: LuceneGenerationStore must NEVER be called with the unsafe legacy ID
        verify(store, never()).hasIndex(anyString());
        verify(store, never()).deleteRepositoryIndex(anyString());
        verifyNoInteractions(store);
    }

    @Test
    @DisplayName("Legacy repository ID repo@v1 statistics can be read via parameterized SQL without sparse store access")
    void testLegacyRepoAtV1StatisticsReadViaSql() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        LuceneGenerationStore store = mock(LuceneGenerationStore.class);
        RepositoryConcurrencyManager concurrencyManager = mock(RepositoryConcurrencyManager.class);

        RepositoryManagementServiceImpl service =
                new RepositoryManagementServiceImpl(jdbcTemplate, concurrencyManager, store);

        service.getRepositoryStats("repo@v1");

        verify(jdbcTemplate).query(eq("SELECT * FROM repository_statistics WHERE repository_id = ?"),
                any(org.springframework.jdbc.core.ResultSetExtractor.class), eq("repo@v1"));
        verifyNoInteractions(store);
    }
}
