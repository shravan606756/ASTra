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
    @DisplayName("Preserves ID normalization across concurrency, sparse check, and SQL queries")
    void testIdNormalization() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        LuceneGenerationStore store = mock(LuceneGenerationStore.class);
        RepositoryConcurrencyManager concurrencyManager = mock(RepositoryConcurrencyManager.class);

        when(store.hasIndex("default")).thenReturn(true);
        when(jdbcTemplate.update(anyString(), eq("default"))).thenReturn(1);

        RepositoryManagementServiceImpl service =
                new RepositoryManagementServiceImpl(jdbcTemplate, concurrencyManager, store);

        service.removeRepository(null);
        verify(concurrencyManager).acquireOrThrow("default");
        verify(store).hasIndex("default");
        verify(concurrencyManager).release("default");

        reset(concurrencyManager, store, jdbcTemplate);
        when(store.hasIndex("trimmed-repo")).thenReturn(true);
        when(jdbcTemplate.update(anyString(), eq("trimmed-repo"))).thenReturn(1);

        service.removeRepository("   trimmed-repo   ");
        verify(concurrencyManager).acquireOrThrow("trimmed-repo");
        verify(store).hasIndex("trimmed-repo");
        verify(concurrencyManager).release("trimmed-repo");
    }
}
