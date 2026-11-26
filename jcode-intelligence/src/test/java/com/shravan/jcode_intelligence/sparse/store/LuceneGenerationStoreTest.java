package com.shravan.jcode_intelligence.sparse.store;

import com.shravan.jcode_intelligence.config.RetrievalConfig;
import com.shravan.jcode_intelligence.sparse.mapper.LuceneDocumentMapper;
import com.shravan.jcode_intelligence.sparse.query.LuceneQueryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LuceneGenerationStoreTest {

    @TempDir
    Path tempDir;

    private RetrievalConfig config;
    private LuceneDocumentMapper mapper;
    private LuceneQueryBuilder queryBuilder;
    private LuceneGenerationStore store;

    @BeforeEach
    void setUp() {
        config = new RetrievalConfig();
        config.setIndexDir(tempDir.resolve("sparse-index").toString());
        mapper = new LuceneDocumentMapper();
        queryBuilder = new LuceneQueryBuilder(config);
        store = new LuceneGenerationStore(config, mapper, queryBuilder);
    }

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.close();
        }
    }

    @Test
    @DisplayName("Stage, write, publish, and search generation lifecycle")
    void testStagePublishSearchLifecycle() throws IOException {
        String repoId = "test-repo";

        // Initial search on non-existent repo should return empty gracefully
        List<Document> emptyResults = store.search(repoId, "UserService", 5);
        assertTrue(emptyResults.isEmpty(), "Missing index should return empty list");

        // Stage generation
        LuceneGenerationStore.StagingSession session = store.stageGeneration(repoId);
        assertNotNull(session);
        assertTrue(Files.isDirectory(session.getStagingDir()));

        // Write documents
        Document doc1 = new Document("doc-1", "public class UserService { public User findUserById(Long id) {} }",
                Map.of("repositoryId", repoId, "className", "UserService", "elementName", "findUserById", "chunkId", "c1"));
        Document doc2 = new Document("doc-2", "public class OrderService { public Order createOrder() {} }",
                Map.of("repositoryId", repoId, "className", "OrderService", "elementName", "createOrder", "chunkId", "c2"));

        session.writeDocuments(List.of(doc1, doc2));

        // Before publishing, search should still return empty
        assertTrue(store.search(repoId, "UserService", 5).isEmpty());

        // Publish generation
        store.publishGeneration(session);

        // Verify CURRENT file exists and points to generation
        Path currentFile = tempDir.resolve("sparse-index").resolve(repoId).resolve("CURRENT");
        assertTrue(Files.exists(currentFile));
        assertEquals(session.getGenerationName(), Files.readString(currentFile).trim());

        // Search published generation
        List<Document> searchResults = store.search(repoId, "UserService findUserById", 5);
        assertFalse(searchResults.isEmpty());
        assertEquals("doc-1", searchResults.get(0).getId());
        assertEquals("UserService", searchResults.get(0).getMetadata().get("className"));
        assertTrue((Double) searchResults.get(0).getMetadata().get("score") > 0.0);
    }

    @Test
    @DisplayName("Publishing new generation replaces CURRENT and cleans up orphans")
    void testGenerationReplacementAndOrphanCleanup() throws IOException {
        String repoId = "replace-repo";

        // Gen 1
        LuceneGenerationStore.StagingSession gen1 = store.stageGeneration(repoId);
        gen1.writeDocuments(List.of(new Document("d1", "version one content", Map.of("repositoryId", repoId))));
        store.publishGeneration(gen1);

        Path gen1Dir = gen1.getStagingDir();
        assertTrue(Files.exists(gen1Dir));

        // Gen 2
        LuceneGenerationStore.StagingSession gen2 = store.stageGeneration(repoId);
        gen2.writeDocuments(List.of(new Document("d2", "version two updated content", Map.of("repositoryId", repoId))));
        store.publishGeneration(gen2);

        Path currentFile = tempDir.resolve("sparse-index").resolve(repoId).resolve("CURRENT");
        assertEquals(gen2.getGenerationName(), Files.readString(currentFile).trim());

        // Search returns gen 2 document
        List<Document> results = store.search(repoId, "updated", 5);
        assertEquals(1, results.size());
        assertEquals("d2", results.get(0).getId());

        // Gen 1 directory should be cleaned up as orphan
        assertFalse(Files.exists(gen1Dir), "Orphaned generation directory should be removed");
    }

    @Test
    @DisplayName("Discard generation removes staging directory and leaves existing index unaffected")
    void testDiscardGeneration() throws IOException {
        String repoId = "discard-repo";

        // Gen 1 published
        LuceneGenerationStore.StagingSession gen1 = store.stageGeneration(repoId);
        gen1.writeDocuments(List.of(new Document("d1", "stable content", Map.of("repositoryId", repoId))));
        store.publishGeneration(gen1);

        // Gen 2 staged but discarded
        LuceneGenerationStore.StagingSession gen2 = store.stageGeneration(repoId);
        Path gen2Dir = gen2.getStagingDir();
        gen2.writeDocuments(List.of(new Document("d2", "broken content", Map.of("repositoryId", repoId))));
        store.discardGeneration(gen2);

        assertFalse(Files.exists(gen2Dir), "Discarded generation directory should be removed");

        // Existing search still serves Gen 1
        List<Document> results = store.search(repoId, "stable", 5);
        assertEquals(1, results.size());
        assertEquals("d1", results.get(0).getId());
    }

    @Test
    @DisplayName("Unscoped search merges results across multiple repositories")
    void testUnscopedSearch() throws IOException {
        String repo1 = "repo-alpha";
        String repo2 = "repo-beta";

        LuceneGenerationStore.StagingSession s1 = store.stageGeneration(repo1);
        s1.writeDocuments(List.of(new Document("doc-a", "public class PaymentGateway { void pay() {} }",
                Map.of("repositoryId", repo1, "className", "PaymentGateway"))));
        store.publishGeneration(s1);

        LuceneGenerationStore.StagingSession s2 = store.stageGeneration(repo2);
        s2.writeDocuments(List.of(new Document("doc-b", "public class PaymentClient { void sendPayment() {} }",
                Map.of("repositoryId", repo2, "className", "PaymentClient"))));
        store.publishGeneration(s2);

        List<Document> unscoped = store.searchUnscoped("Payment", 10);
        assertEquals(2, unscoped.size());
        List<String> foundIds = unscoped.stream().map(Document::getId).toList();
        assertTrue(foundIds.contains("doc-a"));
        assertTrue(foundIds.contains("doc-b"));
    }

    @Test
    @DisplayName("Disabled sparse retrieval returns empty results")
    void testDisabledSparseRetrieval() throws IOException {
        String repoId = "disabled-repo";

        LuceneGenerationStore.StagingSession s = store.stageGeneration(repoId);
        s.writeDocuments(List.of(new Document("d1", "sample code", Map.of("repositoryId", repoId))));
        store.publishGeneration(s);

        config.setSparseEnabled(false);

        List<Document> results = store.search(repoId, "sample", 5);
        assertTrue(results.isEmpty(), "Disabled sparse retrieval must return empty list");

        List<Document> unscoped = store.searchUnscoped("sample", 5);
        assertTrue(unscoped.isEmpty());
    }

    @Test
    @DisplayName("Delete repository index removes data and searchers")
    void testDeleteRepositoryIndex() throws IOException {
        String repoId = "delete-repo";

        LuceneGenerationStore.StagingSession s = store.stageGeneration(repoId);
        s.writeDocuments(List.of(new Document("d1", "sample code", Map.of("repositoryId", repoId))));
        store.publishGeneration(s);

        assertEquals(1, store.search(repoId, "sample", 5).size());

        store.deleteRepositoryIndex(repoId);

        Path repoDir = tempDir.resolve("sparse-index").resolve(repoId);
        assertFalse(Files.exists(repoDir));
        assertTrue(store.search(repoId, "sample", 5).isEmpty());
    }
}
