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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

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

    @Test
    @DisplayName("Overlapping index jobs do not delete active staging directories during orphan cleanup")
    void testOverlappingIndexJobsStagingDirectoryNotCleanedUp() throws IOException {
        String repoId = "overlap-repo";

        // Initial published generation (gen0)
        LuceneGenerationStore.StagingSession gen0 = store.stageGeneration(repoId);
        gen0.writeDocuments(List.of(new Document("d0", "initial content gen 0", Map.of("repositoryId", repoId))));
        store.publishGeneration(gen0);
        Path gen0Dir = gen0.getStagingDir();
        assertTrue(Files.exists(gen0Dir), "Gen0 should exist after initial publish");

        // Job 1 stages gen1
        LuceneGenerationStore.StagingSession gen1 = store.stageGeneration(repoId);
        gen1.writeDocuments(List.of(new Document("d1", "job one content gen 1", Map.of("repositoryId", repoId))));
        Path gen1Dir = gen1.getStagingDir();
        assertTrue(Files.exists(gen1Dir));

        // Job 2 stages gen2 concurrently while gen1 is still uncommitted
        LuceneGenerationStore.StagingSession gen2 = store.stageGeneration(repoId);
        gen2.writeDocuments(List.of(new Document("d2", "job two content gen 2", Map.of("repositoryId", repoId))));
        Path gen2Dir = gen2.getStagingDir();
        assertTrue(Files.exists(gen2Dir));

        // Job 1 publishes gen1 -> triggers orphan cleanup
        store.publishGeneration(gen1);

        // Verify: gen0 was an unreferenced old generation, so it should be cleaned up
        assertFalse(Files.exists(gen0Dir), "Unreferenced gen0 directory should be cleaned up as an orphan");

        // Crucial regression verification: gen2 is an ongoing staging session and MUST NOT be deleted
        assertTrue(Files.exists(gen2Dir), "Active staging directory gen2 must NOT be deleted by gen1 orphan cleanup");

        // Job 2 completes and publishes gen2 successfully without errors
        assertDoesNotThrow(() -> store.publishGeneration(gen2));

        // Verify gen2 is now CURRENT and searchable
        Path currentFile = tempDir.resolve("sparse-index").resolve(repoId).resolve("CURRENT");
        assertEquals(gen2.getGenerationName(), Files.readString(currentFile).trim());

        // gen1 is now orphaned and should be cleaned up
        assertFalse(Files.exists(gen1Dir), "Previous generation gen1 should be cleaned up once gen2 is published");

        // Search returns gen2 content
        List<Document> results = store.search(repoId, "job two", 5);
        assertEquals(1, results.size());
        assertEquals("d2", results.get(0).getId());
    }

    @Test
    @DisplayName("Concurrent overlapping index jobs stage and publish without file conflicts or orphan races")
    void testConcurrentOverlappingStagingAndPublish() throws Exception {
        String repoId = "concurrent-repo";
        int numThreads = 6;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();

        for (int i = 0; i < numThreads; i++) {
            final int index = i;
            futures.add(executor.submit(() -> {
                startLatch.await();
                LuceneGenerationStore.StagingSession session = store.stageGeneration(repoId);
                String docId = "doc-" + index;
                session.writeDocuments(List.of(new Document(docId, "concurrent text " + index,
                        Map.of("repositoryId", repoId, "docId", docId))));
                store.publishGeneration(session);
                return session.getGenerationName();
            }));
        }

        startLatch.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS), "Executor did not finish in time");

        List<String> finishedGens = new ArrayList<>();
        for (Future<String> future : futures) {
            finishedGens.add(future.get());
        }

        // Verify CURRENT is one of the finished generations
        Path currentFile = tempDir.resolve("sparse-index").resolve(repoId).resolve("CURRENT");
        assertTrue(Files.exists(currentFile));
        String currentGen = Files.readString(currentFile).trim();
        assertTrue(finishedGens.contains(currentGen));

        // Verify search operates smoothly on current index
        List<Document> searchResults = store.search(repoId, "concurrent", 10);
        assertFalse(searchResults.isEmpty());
    }

    @Test
    @DisplayName("Two concurrent publishes do not interfere and published generation matches a completed session")
    void testConcurrentPublishesDoNotInterfere() throws Exception {
        String repoId = "concurrent-publish-repo";

        LuceneGenerationStore.StagingSession session1 = store.stageGeneration(repoId);
        session1.writeDocuments(List.of(new Document("doc-1", "session one unique text",
                Map.of("repositoryId", repoId, "docId", "doc-1"))));

        LuceneGenerationStore.StagingSession session2 = store.stageGeneration(repoId);
        session2.writeDocuments(List.of(new Document("doc-2", "session two unique text",
                Map.of("repositoryId", repoId, "docId", "doc-2"))));

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        Future<?> f1 = executor.submit(() -> {
            barrier.await();
            store.publishGeneration(session1);
            return null;
        });

        Future<?> f2 = executor.submit(() -> {
            barrier.await();
            store.publishGeneration(session2);
            return null;
        });

        // Ensure both completed without throwing exceptions
        f1.get(10, TimeUnit.SECONDS);
        f2.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        Path currentFile = tempDir.resolve("sparse-index").resolve(repoId).resolve("CURRENT");
        assertTrue(Files.exists(currentFile), "CURRENT file must exist");
        String publishedGen = Files.readString(currentFile, java.nio.charset.StandardCharsets.UTF_8).trim();

        // The published generation must match one of the completed sessions
        java.util.Set<String> validGens = java.util.Set.of(session1.getGenerationName(), session2.getGenerationName());
        assertTrue(validGens.contains(publishedGen),
                "CURRENT generation (" + publishedGen + ") must match a completed session (" + validGens + ")");

        // The directory for the published generation must exist and be valid
        Path publishedGenDir = tempDir.resolve("sparse-index").resolve(repoId).resolve(publishedGen);
        assertTrue(Files.isDirectory(publishedGenDir), "Published generation directory must exist");

        // Verify search works and retrieves the document from the winning session
        String expectedDocId = publishedGen.equals(session1.getGenerationName()) ? "doc-1" : "doc-2";
        String query = publishedGen.equals(session1.getGenerationName()) ? "session one" : "session two";
        List<Document> searchResults = store.search(repoId, query, 5);
        assertFalse(searchResults.isEmpty(), "Search should find document for published generation");
        assertEquals(expectedDocId, searchResults.get(0).getId());

        // Verify no stray temporary CURRENT.*.tmp files were left behind
        try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(tempDir.resolve("sparse-index").resolve(repoId))) {
            for (Path entry : stream) {
                String name = entry.getFileName().toString();
                assertFalse(name.startsWith("CURRENT") && name.endsWith(".tmp"),
                        "No temporary CURRENT pointer files should remain: " + name);
            }
        }
    }

    @Test
    @DisplayName("resolveRepoDir confines safe IDs to index root and rejects traversal and absolute path IDs")
    void testResolveRepoDir_validAndInvalidIds() {
        Path baseDir = tempDir.resolve("sparse-index").toAbsolutePath().normalize();

        // Valid repository IDs
        assertEquals(baseDir.resolve("my-repo"), store.resolveRepoDir("my-repo"));
        assertEquals(baseDir.resolve("repo_123"), store.resolveRepoDir("repo_123"));
        assertEquals(baseDir.resolve("repo.v1"), store.resolveRepoDir("repo.v1"));
        assertEquals(baseDir.resolve("REPO-A"), store.resolveRepoDir("REPO-A"));

        // Null and blank default to "default"
        assertEquals(baseDir.resolve("default"), store.resolveRepoDir(null));
        assertEquals(baseDir.resolve("default"), store.resolveRepoDir(""));
        assertEquals(baseDir.resolve("default"), store.resolveRepoDir("   "));

        // Path traversal and absolute path IDs must throw IllegalArgumentException
        List<String> invalidIds = List.of(
                "../escaped",
                "..\\escaped",
                "..",
                ".",
                "../../root",
                "/etc/passwd",
                "/var/data",
                "C:\\Windows\\System32",
                "C:/Windows",
                "foo/bar",
                "foo\\bar",
                "foo bar",
                "foo;bar",
                "foo*bar"
        );

        for (String invalidId : invalidIds) {
            assertThrows(IllegalArgumentException.class, () -> store.resolveRepoDir(invalidId),
                    "Expected IllegalArgumentException for ID: " + invalidId);
        }
    }

    @Test
    @DisplayName("stageGeneration rejects traversal and absolute path IDs without creating files outside root")
    void testStageGeneration_rejectsPathTraversalAndAbsolutePaths() throws IOException {
        Path outsideDir = tempDir.resolve("outside-target");
        Files.createDirectories(outsideDir);

        assertThrows(IllegalArgumentException.class, () -> store.stageGeneration("../outside-target"));
        assertThrows(IllegalArgumentException.class, () -> store.stageGeneration(outsideDir.toAbsolutePath().toString()));

        // Verify outside directory remains empty
        try (var stream = Files.newDirectoryStream(outsideDir)) {
            assertFalse(stream.iterator().hasNext(), "Outside directory must not contain any staged files");
        }
    }

    @Test
    @DisplayName("search, hasIndex, and getDocumentCount reject path traversal IDs")
    void testSearchAndHasIndex_rejectsPathTraversal() {
        assertThrows(IllegalArgumentException.class, () -> store.search("../outside", "query", 5));
        assertThrows(IllegalArgumentException.class, () -> store.hasIndex("../outside"));
        assertThrows(IllegalArgumentException.class, () -> store.getDocumentCount("../outside"));
        assertThrows(IllegalArgumentException.class, () -> store.search("/etc/passwd", "query", 5));
        assertThrows(IllegalArgumentException.class, () -> store.hasIndex("/etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> store.getDocumentCount("/etc/passwd"));
    }

    @Test
    @DisplayName("deleteRepositoryIndex rejects path traversal without deleting external directories")
    void testDeleteRepositoryIndex_rejectsPathTraversalDoesNotDeleteExternalDirectory() throws IOException {
        Path victimDir = tempDir.resolve("victim-dir");
        Files.createDirectories(victimDir);
        Path secretFile = victimDir.resolve("important.txt");
        Files.writeString(secretFile, "critical data");

        assertThrows(IllegalArgumentException.class, () -> store.deleteRepositoryIndex("../victim-dir"));
        assertThrows(IllegalArgumentException.class, () -> store.deleteRepositoryIndex(victimDir.toAbsolutePath().toString()));

        // Verify victim directory and its files still exist
        assertTrue(Files.exists(victimDir), "Victim directory must not be deleted by path traversal");
        assertTrue(Files.exists(secretFile), "Victim file must not be deleted by path traversal");
        assertEquals("critical data", Files.readString(secretFile));
    }
}
