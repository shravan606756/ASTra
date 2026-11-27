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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
                Map.of("repositoryId", repoId, "className", "UserService", "elementName", "findUserById", "chunkId",
                        "c1"));
        Document doc2 = new Document("doc-2", "public class OrderService { public Order createOrder() {} }",
                Map.of("repositoryId", repoId, "className", "OrderService", "elementName", "createOrder", "chunkId",
                        "c2"));

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

        // Crucial regression verification: gen2 is an ongoing staging session and MUST
        // NOT be deleted
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
        try (java.nio.file.DirectoryStream<Path> stream = Files
                .newDirectoryStream(tempDir.resolve("sparse-index").resolve(repoId))) {
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
                "foo*bar");

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
        assertThrows(IllegalArgumentException.class,
                () -> store.stageGeneration(outsideDir.toAbsolutePath().toString()));

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
        assertThrows(IllegalArgumentException.class,
                () -> store.deleteRepositoryIndex(victimDir.toAbsolutePath().toString()));

        // Verify victim directory and its files still exist
        assertTrue(Files.exists(victimDir), "Victim directory must not be deleted by path traversal");
        assertTrue(Files.exists(secretFile), "Victim file must not be deleted by path traversal");
        assertEquals("critical data", Files.readString(secretFile));
    }

    @Test
    @DisplayName("deleteRepositoryIndex rejects blank repository ID and leaves default repository intact")
    void testDeleteRepositoryIndex_rejectsBlankIdAndLeavesDefaultIntact() throws IOException {
        // Stage and publish default generation
        LuceneGenerationStore.StagingSession defaultSession = store.stageGeneration("default");
        defaultSession.writeDocuments(List.of(new Document("doc-def", "default repo content",
                Map.of("repositoryId", "default"))));
        store.publishGeneration(defaultSession);

        assertTrue(store.hasIndex("default"));

        // Attempt deletion with null, empty, and whitespace
        assertThrows(IllegalArgumentException.class, () -> store.deleteRepositoryIndex(null));
        assertThrows(IllegalArgumentException.class, () -> store.deleteRepositoryIndex(""));
        assertThrows(IllegalArgumentException.class, () -> store.deleteRepositoryIndex("   "));

        // Default repository must still be completely intact and searchable
        assertTrue(store.hasIndex("default"), "Default repository must remain intact after blank deletion attempt");
        List<Document> results = store.search("default", "default", 5);
        assertEquals(1, results.size());
        assertEquals("doc-def", results.get(0).getId());
    }

    @Test
    @DisplayName("isValidRepositoryId distinguishes safe filesystem IDs from unsafe or legacy IDs")
    void testIsValidRepositoryId() {
        assertTrue(LuceneGenerationStore.isValidRepositoryId("valid-repo"));
        assertTrue(LuceneGenerationStore.isValidRepositoryId("repo_123"));
        assertTrue(LuceneGenerationStore.isValidRepositoryId("repo.v1"));
        assertTrue(LuceneGenerationStore.isValidRepositoryId("REPO-A"));

        // Unsafe or legacy IDs
        assertFalse(LuceneGenerationStore.isValidRepositoryId("repo@v1"));
        assertFalse(LuceneGenerationStore.isValidRepositoryId("repo#1"));
        assertFalse(LuceneGenerationStore.isValidRepositoryId("../outside"));
        assertFalse(LuceneGenerationStore.isValidRepositoryId(".."));
        assertFalse(LuceneGenerationStore.isValidRepositoryId("."));
        assertFalse(LuceneGenerationStore.isValidRepositoryId(""));
        assertFalse(LuceneGenerationStore.isValidRepositoryId("   "));
        assertFalse(LuceneGenerationStore.isValidRepositoryId(null));

        // Filesystem operations strictly reject legacy repo@v1
        assertThrows(IllegalArgumentException.class, () -> store.stageGeneration("repo@v1"));
        assertThrows(IllegalArgumentException.class, () -> store.resolveRepoDir("repo@v1"));
        assertThrows(IllegalArgumentException.class, () -> store.search("repo@v1", "query", 5));
        assertThrows(IllegalArgumentException.class, () -> store.hasIndex("repo@v1"));
    }

    @Test
    @DisplayName("searchUnscoped does not leak reader references, allowing orphan cleanup on replacement")
    void testSearchUnscoped_doesNotLeakReaderReferencesAndAllowsOrphanCleanup() throws IOException {
        String repoA = "repo-alpha-leak";
        String repoB = "repo-beta-leak";

        // Stage and publish repo A (Gen 1)
        LuceneGenerationStore.StagingSession sessionA1 = store.stageGeneration(repoA);
        sessionA1.writeDocuments(List.of(new Document("doc-a1", "search target content alpha",
                Map.of("repositoryId", repoA, "chunkId", "ca1"))));
        store.publishGeneration(sessionA1);
        Path genA1Dir = sessionA1.getStagingDir();

        // Stage and publish repo B
        LuceneGenerationStore.StagingSession sessionB = store.stageGeneration(repoB);
        sessionB.writeDocuments(List.of(new Document("doc-b1", "search target content beta",
                Map.of("repositoryId", repoB, "chunkId", "cb1"))));
        store.publishGeneration(sessionB);

        // Warm up and verify initial cached ref count is 1
        assertEquals(1, store.search(repoA, "target", 5).size());
        assertEquals(1, store.search(repoB, "target", 5).size());
        assertEquals(1, store.getSearcherRefCount(repoA), "Initial cached reader ref count must be 1");
        assertEquals(1, store.getSearcherRefCount(repoB), "Initial cached reader ref count must be 1");

        // Execute repeated unscoped searches
        for (int i = 0; i < 10; i++) {
            List<Document> unscoped = store.searchUnscoped("target", 10);
            assertEquals(2, unscoped.size());
            assertEquals(1, store.getSearcherRefCount(repoA),
                    "Ref count must not increment after unscoped search " + i);
            assertEquals(1, store.getSearcherRefCount(repoB),
                    "Ref count must not increment after unscoped search " + i);
        }

        // Execute repeated scoped searches and verify ref count remains 1
        for (int i = 0; i < 5; i++) {
            assertEquals(1, store.search(repoA, "target", 5).size());
            assertEquals(1, store.getSearcherRefCount(repoA));
        }

        // Publish Gen 2 for repo A
        LuceneGenerationStore.StagingSession sessionA2 = store.stageGeneration(repoA);
        sessionA2.writeDocuments(List.of(new Document("doc-a2", "new version content alpha",
                Map.of("repositoryId", repoA, "chunkId", "ca2"))));
        store.publishGeneration(sessionA2);

        // Because ref count was not leaked, Gen 1 reader was fully closed during
        // eviction,
        // so Windows file locks are released and orphan cleanup successfully removes
        // Gen 1 directory.
        assertFalse(Files.exists(genA1Dir), "Orphaned Gen 1 directory must be cleanly removed without file lock leaks");

        // Verify newly published generation is searchable
        List<Document> searchResults = store.search(repoA, "alpha", 5);
        assertEquals(1, searchResults.size());
        assertEquals("doc-a2", searchResults.get(0).getId());
    }

    @Test
    @DisplayName("Concurrent searches during generation publish and eviction do not fail with AlreadyClosedException")
    void testConcurrentSearchesDuringPublishAndEviction() throws Exception {
        String repoId = "repo-concurrent-evict";

        LuceneGenerationStore.StagingSession gen1 = store.stageGeneration(repoId);
        gen1.writeDocuments(List.of(new Document("doc-1", "concurrent search target text",
                Map.of("repositoryId", repoId, "chunkId", "c1"))));
        store.publishGeneration(gen1);

        int numThreads = 8;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numThreads);
        List<Throwable> errors = new CopyOnWriteArrayList<>();

        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < 25; j++) {
                        if (threadId % 2 == 0) {
                            List<Document> results = store.search(repoId, "target", 5);
                            assertFalse(results.isEmpty());
                        } else {
                            List<Document> results = store.searchUnscoped("target", 5);
                            assertFalse(results.isEmpty());
                        }
                    }
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();

        // While threads are querying, publish gen 2
        Thread.sleep(10);
        LuceneGenerationStore.StagingSession gen2 = store.stageGeneration(repoId);
        gen2.writeDocuments(List.of(new Document("doc-2", "concurrent search target text updated",
                Map.of("repositoryId", repoId, "chunkId", "c2"))));
        store.publishGeneration(gen2);

        assertTrue(doneLatch.await(10, TimeUnit.SECONDS), "Concurrent searches timed out");
        executor.shutdown();

        assertTrue(errors.isEmpty(), "Concurrent searches failed with errors: " + errors);
    }

    @Test
    @DisplayName("Concurrent publish during acquireSearcher openIfChanged checks does not abort searches or doc count")
    void testConcurrentPublishDuringOpenIfChangedDoesNotThrowAlreadyClosedException() throws Exception {
        String repoId = "repo-openifchanged-race";

        LuceneGenerationStore.StagingSession gen1 = store.stageGeneration(repoId);
        gen1.writeDocuments(List.of(new Document("doc-1", "openIfChanged race target content",
                Map.of("repositoryId", repoId, "chunkId", "c1"))));
        store.publishGeneration(gen1);

        // Pre-warm the cache
        assertEquals(1, store.getDocumentCount(repoId));
        assertEquals(1, store.search(repoId, "target", 5).size());

        int numQueryThreads = 6;
        ExecutorService executor = Executors.newFixedThreadPool(numQueryThreads + 1);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numQueryThreads + 1);
        List<Throwable> errors = new CopyOnWriteArrayList<>();

        // Query threads exercising search, unscoped search, and getDocumentCount
        for (int i = 0; i < numQueryThreads; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < 30; j++) {
                        if (threadId % 3 == 0) {
                            List<Document> res = store.search(repoId, "target", 5);
                            assertFalse(res.isEmpty(), "Scoped search returned empty results");
                        } else if (threadId % 3 == 1) {
                            List<Document> res = store.searchUnscoped("target", 5);
                            assertFalse(res.isEmpty(), "Unscoped search returned empty results");
                        } else {
                            int count = store.getDocumentCount(repoId);
                            assertTrue(count >= 1, "Document count was 0");
                        }
                    }
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Publisher thread rapidly publishing new generations
        executor.submit(() -> {
            try {
                startLatch.await();
                for (int g = 2; g <= 4; g++) {
                    Thread.sleep(5);
                    LuceneGenerationStore.StagingSession nextGen = store.stageGeneration(repoId);
                    nextGen.writeDocuments(List.of(new Document("doc-" + g, "openIfChanged race target content v" + g,
                            Map.of("repositoryId", repoId, "chunkId", "c" + g))));
                    store.publishGeneration(nextGen);
                }
            } catch (Throwable t) {
                errors.add(t);
            } finally {
                doneLatch.countDown();
            }
        });

        startLatch.countDown();
        assertTrue(doneLatch.await(15, TimeUnit.SECONDS), "Concurrent test timed out");
        executor.shutdown();

        assertTrue(errors.isEmpty(), "Errors occurred during concurrent openIfChanged: " + errors);
    }

    @Test
    @DisplayName("Two store instances: orphan cleanup does not delete in-flight temporary pointer of concurrent publisher")
    void testTwoStoresConcurrentPublishAndOrphanCleanup() throws Exception {
        Path sharedIndexDir = tempDir.resolve("shared-sparse-index");
        RetrievalConfig config1 = new RetrievalConfig();
        config1.setIndexDir(sharedIndexDir.toString());
        RetrievalConfig config2 = new RetrievalConfig();
        config2.setIndexDir(sharedIndexDir.toString());

        LuceneGenerationStore store1 = new LuceneGenerationStore(config1, mapper, queryBuilder);
        LuceneGenerationStore store2 = new LuceneGenerationStore(config2, mapper, queryBuilder);

        try {
            String repoId = "two-store-repo";

            // Store 1 stages generation 1
            LuceneGenerationStore.StagingSession session1 = store1.stageGeneration(repoId);
            session1.writeDocuments(List.of(new Document("doc-s1", "store one content",
                    Map.of("repositoryId", repoId, "chunkId", "c1"))));
            session1.getIndexWriter().commit();
            session1.close();

            // Store 2 stages and publishes generation 2
            LuceneGenerationStore.StagingSession session2 = store2.stageGeneration(repoId);
            session2.writeDocuments(List.of(new Document("doc-s2", "store two content",
                    Map.of("repositoryId", repoId, "chunkId", "c2"))));

            // Simulate store 1 pausing between writing currentTmp and moving it:
            Path repoDir = sharedIndexDir.resolve(repoId);
            Path store1InFlightTmp = repoDir.resolve("CURRENT.store1-inflight.tmp");
            Files.writeString(store1InFlightTmp, session1.getGenerationName(), StandardCharsets.UTF_8);
            assertTrue(Files.exists(store1InFlightTmp));

            // Store 2 publishes generation 2, which triggers orphan sweep
            store2.publishGeneration(session2);

            // Verify that store 2's orphan sweep did NOT delete store 1's in-flight
            // temporary pointer
            assertTrue(Files.exists(store1InFlightTmp),
                    "In-flight temporary pointer of store 1 must not be deleted by store 2's orphan sweep");

            // Store 1 completes its publication by atomically moving its pointer
            Path currentFile = repoDir.resolve("CURRENT");
            Files.move(store1InFlightTmp, currentFile, StandardCopyOption.REPLACE_EXISTING);

            // Store 1 is now active CURRENT
            assertEquals(session1.getGenerationName(), Files.readString(currentFile).trim());

            // Search on store 1 sees store 1 content
            List<Document> results = store1.search(repoId, "store", 5);
            assertFalse(results.isEmpty());
            assertEquals("doc-s1", results.get(0).getId());
        } finally {
            store1.close();
            store2.close();
        }
    }

    @Test
    @DisplayName("Search transparently recovers when selected generation directory disappears during concurrent publication")
    void testSearchRecoversWhenSelectedGenerationDisappearsConcurrently() throws Exception {
        String repoId = "repo-disappearing-gen";

        // Stage and publish Gen 1
        LuceneGenerationStore.StagingSession gen1 = store.stageGeneration(repoId);
        gen1.writeDocuments(List.of(new Document("doc-1", "disappearing generation content v1",
                Map.of("repositoryId", repoId, "chunkId", "c1"))));
        store.publishGeneration(gen1);

        // Stage and publish Gen 2
        LuceneGenerationStore.StagingSession gen2 = store.stageGeneration(repoId);
        gen2.writeDocuments(List.of(new Document("doc-2", "disappearing generation content v2",
                Map.of("repositoryId", repoId, "chunkId", "c2"))));
        store.publishGeneration(gen2);

        // Pre-warm cache on Gen 2
        assertEquals(1, store.getDocumentCount(repoId));
        assertEquals(1, store.search(repoId, "content", 5).size());

        // Now simulate a transition where CURRENT was pointing to an older generation
        // that has been deleted
        Path repoDir = tempDir.resolve("sparse-index").resolve(repoId);
        Path currentFile = repoDir.resolve("CURRENT");
        String staleGen = "gen_stale_deleted_" + System.currentTimeMillis();

        // Write stale pointer to CURRENT momentarily
        Files.writeString(currentFile, staleGen, StandardCharsets.UTF_8);

        // Concurrently publish Gen 3 which writes real CURRENT and removes older
        // generations
        LuceneGenerationStore.StagingSession gen3 = store.stageGeneration(repoId);
        gen3.writeDocuments(List.of(new Document("doc-3", "disappearing generation content v3",
                Map.of("repositoryId", repoId, "chunkId", "c3"))));

        // Start search thread
        CompletableFuture<List<Document>> searchFuture = CompletableFuture.supplyAsync(() -> {
            try {
                Thread.sleep(5);
            } catch (InterruptedException ignored) {
            }
            return store.search(repoId, "content", 5);
        });

        // Publish Gen 3 immediately
        store.publishGeneration(gen3);

        List<Document> results = searchFuture.get(5, TimeUnit.SECONDS);
        assertNotNull(results);
        assertFalse(results.isEmpty(), "Search must recover and return published results rather than empty");
        assertEquals("doc-3", results.get(0).getId());
        assertEquals(1, store.getDocumentCount(repoId));
    }
}
