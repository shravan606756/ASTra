package com.shravan.jcode_intelligence.sparse.store;

import com.shravan.jcode_intelligence.config.RetrievalConfig;
import com.shravan.jcode_intelligence.sparse.analyzer.JavaSourceAnalyzer;
import com.shravan.jcode_intelligence.sparse.mapper.LuceneDocumentMapper;
import com.shravan.jcode_intelligence.sparse.query.LuceneQueryBuilder;
import jakarta.annotation.PreDestroy;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.MultiReader;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.AlreadyClosedException;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Per-repository generation store for Lucene BM25 sparse retrieval.
 *
 * <p>Supports:
 * <ul>
 *   <li>Isolated per-repository generation staging in dedicated folders</li>
 *   <li>Atomic pointer swap via {@code CURRENT} file update</li>
 *   <li>Staging discard and repository index deletion</li>
 *   <li>Point-in-time searching with lazy index opening and cached readers</li>
 *   <li>Unscoped cross-repository searching with merged top-K results</li>
 *   <li>Automatic orphan generation cleanup excluding active staging sessions</li>
 *   <li>Graceful handling of disabled or missing indexes</li>
 *   <li>Clean shutdown hook closing all readers, writers, and directories</li>
 * </ul>
 */
@Component
public class LuceneGenerationStore implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LuceneGenerationStore.class);

    private final RetrievalConfig config;
    private final LuceneDocumentMapper mapper;
    private final LuceneQueryBuilder queryBuilder;

    public static final Pattern SAFE_REPO_ID_PATTERN = Pattern.compile("^[A-Za-z0-9._-]+$");
    private static final long CRASH_REMNANT_SWEEP_THRESHOLD_MS = 300_000L; // 5 minutes

    private final ConcurrentMap<String, SearcherHolder> searcherCache = new ConcurrentHashMap<>();
    private final Set<StagingSession> activeStagingSessions = ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<String, Object> repoLocks = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * Validates that the provided repositoryId is a single safe path component.
     * Rejects path separators, relative traversal sequences, and absolute paths.
     * Defaults to "default" if null or blank (for indexing paths where omitted ID intentionally defaults).
     *
     * @param repositoryId caller-provided repository identifier
     * @return validated safe repository ID
     * @throws IllegalArgumentException if repositoryId contains path traversal or invalid characters
     */
    public static String validateRepositoryId(String repositoryId) {
        if (repositoryId == null || repositoryId.isBlank()) {
            return "default";
        }
        return validateNonBlankRepositoryId(repositoryId);
    }

    /**
     * Strictly validates that the provided repositoryId is non-null, non-blank, and a single safe path component.
     * Rejects null, blank, path separators, relative traversal sequences, and absolute paths.
     * Use this for deletion and lookup operations where an omitted ID must not target "default".
     *
     * @param repositoryId caller-provided repository identifier
     * @return trimmed safe repository ID
     * @throws IllegalArgumentException if repositoryId is null, blank, contains traversal, or invalid characters
     */
    public static String validateNonBlankRepositoryId(String repositoryId) {
        if (repositoryId == null || repositoryId.isBlank()) {
            throw new IllegalArgumentException("repositoryId must not be null or blank");
        }
        String trimmed = repositoryId.trim();
        if (trimmed.equals(".") || trimmed.equals("..") || !SAFE_REPO_ID_PATTERN.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("Invalid repositoryId '" + repositoryId +
                    "': repositoryId must be a single safe path component matching [A-Za-z0-9._-]+ and cannot be '.' or '..'");
        }
        return trimmed;
    }

    /**
     * Checks whether the provided repositoryId is a valid single safe path component
     * suitable for filesystem storage without throwing an exception.
     *
     * @param repositoryId caller-provided repository identifier
     * @return true if repositoryId is non-null, non-blank, and safe for filesystem operations
     */
    public static boolean isValidRepositoryId(String repositoryId) {
        if (repositoryId == null || repositoryId.isBlank()) {
            return false;
        }
        String trimmed = repositoryId.trim();
        return !trimmed.equals(".") && !trimmed.equals("..") && SAFE_REPO_ID_PATTERN.matcher(trimmed).matches();
    }

    /**
     * Resolves and verifies the repository directory under the configured index root.
     *
     * @param repositoryId caller-provided repository identifier
     * @return normalized Path guaranteed to reside strictly inside the index root
     * @throws IllegalArgumentException if repositoryId is invalid or attempts path traversal
     */
    public Path resolveRepoDir(String repositoryId) {
        String safeRepoId = validateRepositoryId(repositoryId);
        Path baseDir = Path.of(config.getIndexDir()).toAbsolutePath().normalize();
        Path repoDir = baseDir.resolve(safeRepoId).normalize();

        if (!repoDir.startsWith(baseDir) || repoDir.equals(baseDir)) {
            throw new IllegalArgumentException("Repository path traversal detected for repositoryId: " + repositoryId);
        }
        return repoDir;
    }

    private Object getRepoLock(String repositoryId) {
        String safeRepoId = validateRepositoryId(repositoryId);
        return repoLocks.computeIfAbsent(safeRepoId, k -> new Object());
    }

    public LuceneGenerationStore() {
        this(new RetrievalConfig(), new LuceneDocumentMapper(), new LuceneQueryBuilder());
    }

    @Autowired(required = false)
    public LuceneGenerationStore(RetrievalConfig config, LuceneDocumentMapper mapper, LuceneQueryBuilder queryBuilder) {
        this.config = config != null ? config : new RetrievalConfig();
        this.mapper = mapper != null ? mapper : new LuceneDocumentMapper();
        this.queryBuilder = queryBuilder != null ? queryBuilder : new LuceneQueryBuilder(this.config);
    }

    /**
     * Staging context for indexing a new generation.
     */
    public static class StagingSession implements AutoCloseable {
        private final String repositoryId;
        private final String generationName;
        private final Path stagingDir;
        private final Directory directory;
        private final IndexWriter indexWriter;
        private final LuceneDocumentMapper mapper;
        private final AtomicBoolean isClosed = new AtomicBoolean(false);
        private final AtomicInteger stagedDocsCount = new AtomicInteger(0);

        public StagingSession(String repositoryId, String generationName, Path stagingDir,
                              Directory directory, IndexWriter indexWriter, LuceneDocumentMapper mapper) {
            this.repositoryId = repositoryId;
            this.generationName = generationName;
            this.stagingDir = stagingDir;
            this.directory = directory;
            this.indexWriter = indexWriter;
            this.mapper = mapper;
        }

        public String getRepositoryId() {
            return repositoryId;
        }

        public String getGenerationName() {
            return generationName;
        }

        public Path getStagingDir() {
            return stagingDir;
        }

        public Directory getDirectory() {
            return directory;
        }

        public IndexWriter getIndexWriter() {
            return indexWriter;
        }

        public void writeDocuments(List<Document> documents) throws IOException {
            if (isClosed.get()) {
                throw new IllegalStateException("StagingSession is already closed");
            }
            if (documents == null || documents.isEmpty()) {
                return;
            }
            List<org.apache.lucene.document.Document> luceneDocs = new ArrayList<>(documents.size());
            for (Document doc : documents) {
                luceneDocs.add(mapper.toLuceneDocument(doc));
            }
            indexWriter.addDocuments(luceneDocs);
            stagedDocsCount.addAndGet(luceneDocs.size());
        }

        public int getStagedDocsCount() {
            return stagedDocsCount.get();
        }

        @Override
        public void close() {
            if (isClosed.compareAndSet(false, true)) {
                try {
                    if (indexWriter.isOpen()) {
                        indexWriter.close();
                    }
                } catch (Exception e) {
                    log.debug("Exception closing IndexWriter for session {}: {}", generationName, e.getMessage());
                }
                try {
                    directory.close();
                } catch (Exception e) {
                    log.debug("Exception closing Directory for session {}: {}", generationName, e.getMessage());
                }
            }
        }
    }

    /**
     * Begins staging a new index generation for the given repository.
     */
    public StagingSession stageGeneration(String repositoryId) throws IOException {
        ensureOpen();
        Path repoDir = resolveRepoDir(repositoryId);
        String safeRepoId = repoDir.getFileName().toString();

        synchronized (getRepoLock(safeRepoId)) {
            Files.createDirectories(repoDir);

            String genName = "gen_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().replace("-", "");
            Path stagingDir = repoDir.resolve(genName);
            Files.createDirectories(stagingDir);

            Directory directory = FSDirectory.open(stagingDir);
            IndexWriterConfig iwc = new IndexWriterConfig(JavaSourceAnalyzer.createIndexAnalyzer());
            iwc.setOpenMode(IndexWriterConfig.OpenMode.CREATE);
            iwc.setSimilarity(new BM25Similarity(config.getK1(), config.getB()));

            IndexWriter writer = new IndexWriter(directory, iwc);
            StagingSession session = new StagingSession(safeRepoId, genName, stagingDir, directory, writer, mapper);
            activeStagingSessions.add(session);
            return session;
        }
    }

    /**
     * Commits the staging session and atomically publishes it as the CURRENT generation for the repository.
     */
    public void publishGeneration(StagingSession session) throws IOException {
        ensureOpen();

        try {
            session.getIndexWriter().commit();
        } finally {
            session.close();
        }

        String safeRepoId = session.getRepositoryId();
        synchronized (getRepoLock(safeRepoId)) {
            activeStagingSessions.remove(session);

            Path repoDir = session.getStagingDir().getParent();
            Path currentFile = repoDir.resolve("CURRENT");
            Path currentTmp = repoDir.resolve("CURRENT." + UUID.randomUUID().toString().replace("-", "") + ".tmp");

            try {
                Files.writeString(currentTmp, session.getGenerationName(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);

                atomicMoveWithRetry(currentTmp, currentFile);
            } finally {
                try {
                    Files.deleteIfExists(currentTmp);
                } catch (IOException ignored) {
                }
            }

            // Update cached searcher with the newly published generation and close old searcher
            try {
                Path genDir = repoDir.resolve(session.getGenerationName()).normalize();
                Directory dir = FSDirectory.open(genDir);
                DirectoryReader reader = DirectoryReader.open(dir);
                IndexSearcher searcher = new IndexSearcher(reader);
                searcher.setSimilarity(new BM25Similarity(config.getK1(), config.getB()));
                SearcherHolder newHolder = new SearcherHolder(session.getGenerationName(), dir, reader, searcher);
                SearcherHolder old = searcherCache.put(safeRepoId, newHolder);
                if (old != null) {
                    old.close();
                }
            } catch (IOException e) {
                log.warn("Failed to pre-cache SearcherHolder for published generation {}: {}",
                        session.getGenerationName(), e.getMessage());
                evictAndCloseSearcher(safeRepoId);
            }

            // Clean up orphan (older non-current) generations, excluding active staging directories
            cleanupOrphans(repoDir, session.getGenerationName());
        }
    }

    private void atomicMoveWithRetry(Path source, Path target) throws IOException {
        IOException lastEx = null;
        for (int i = 0; i < 15; i++) {
            try {
                try {
                    Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (IOException e) {
                lastEx = e;
                try {
                    Thread.sleep(15);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while moving file to " + target, ie);
                }
            }
        }
        if (lastEx != null) {
            throw lastEx;
        }
    }

    private String readCurrentPointer(Path currentFile, String safeRepoId) {
        for (int i = 0; i < 5; i++) {
            try {
                return Files.readString(currentFile, StandardCharsets.UTF_8).trim();
            } catch (IOException e) {
                if (i == 4) {
                    log.debug("Failed to read CURRENT pointer for repo {}: {}", safeRepoId, e.getMessage());
                    return null;
                }
                try {
                    Thread.sleep(10);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * Discards an uncommitted staging session and removes its directory.
     */
    public void discardGeneration(StagingSession session) {
        if (session == null) {
            return;
        }
        String safeRepoId = session.getRepositoryId();
        try {
            if (session.getIndexWriter().isOpen()) {
                session.getIndexWriter().rollback();
            }
        } catch (Exception e) {
            log.debug("Rollback error on discard for {}: {}", session.getGenerationName(), e.getMessage());
        } finally {
            session.close();
        }

        synchronized (getRepoLock(safeRepoId)) {
            activeStagingSessions.remove(session);
            deleteRecursively(session.getStagingDir());
        }
    }

    /**
     * Deletes the entire index and directory structure for a repository.
     */
    public void deleteRepositoryIndex(String repositoryId) {
        if (repositoryId == null || repositoryId.isBlank()) {
            throw new IllegalArgumentException("repositoryId must not be null or blank for repository deletion");
        }
        Path repoDir = resolveRepoDir(repositoryId);
        String safeRepoId = repoDir.getFileName().toString();
        synchronized (getRepoLock(safeRepoId)) {
            evictAndCloseSearcher(safeRepoId);

            for (StagingSession session : new ArrayList<>(activeStagingSessions)) {
                if (safeRepoId.equals(session.getRepositoryId())) {
                    discardGeneration(session);
                }
            }

            if (Files.exists(repoDir)) {
                deleteRecursively(repoDir);
            }
        }
    }

    /**
     * Executes a point-in-time BM25 search against the active generation of a repository.
     */
    public List<Document> search(String repositoryId, String queryStr, int topK) {
        if (!config.isSparseEnabled() || closed.get()) {
            return Collections.emptyList();
        }
        if (queryStr == null || queryStr.isBlank() || topK <= 0) {
            return Collections.emptyList();
        }

        Path repoDir = resolveRepoDir(repositoryId);
        String safeRepoId = repoDir.getFileName().toString();
        SearcherHolder holder = acquireSearcher(safeRepoId);
        if (holder == null) {
            return Collections.emptyList();
        }

        try {
            Query query = queryBuilder.buildQuery(queryStr, safeRepoId);
            TopDocs topDocs = holder.searcher.search(query, topK);
            List<Document> results = new ArrayList<>(topDocs.scoreDocs.length);
            for (ScoreDoc sd : topDocs.scoreDocs) {
                org.apache.lucene.document.Document luceneDoc = holder.searcher.storedFields().document(sd.doc);
                results.add(mapper.toSpringAiDocument(luceneDoc, sd.score));
            }
            return results;
        } catch (IOException e) {
            log.error("Error executing Lucene search for repo {}: {}", safeRepoId, e.getMessage(), e);
            return Collections.emptyList();
        } finally {
            holder.releaseRef();
        }
    }

    /**
     * Executes an unscoped search across all active repository generations, merging top results.
     */
    public List<Document> searchUnscoped(String queryStr, int topK) {
        if (!config.isSparseEnabled() || closed.get()) {
            return Collections.emptyList();
        }
        if (queryStr == null || queryStr.isBlank() || topK <= 0) {
            return Collections.emptyList();
        }

        Path baseDir = Path.of(config.getIndexDir());
        if (!Files.isDirectory(baseDir)) {
            return Collections.emptyList();
        }

        List<SearcherHolder> leasedHolders = new ArrayList<>();
        List<DirectoryReader> activeReaders = new ArrayList<>();
        try {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(baseDir)) {
                for (Path repoPath : stream) {
                    if (Files.isDirectory(repoPath) && Files.exists(repoPath.resolve("CURRENT"))) {
                        String repoName = repoPath.getFileName().toString();
                        if (!SAFE_REPO_ID_PATTERN.matcher(repoName).matches() || repoName.equals(".") || repoName.equals("..")) {
                            continue;
                        }
                        SearcherHolder holder = acquireSearcher(repoName);
                        if (holder != null) {
                            if (holder.reader.numDocs() > 0) {
                                leasedHolders.add(holder);
                                activeReaders.add(holder.reader);
                            } else {
                                holder.releaseRef();
                            }
                        }
                    }
                }
            } catch (IOException e) {
                log.warn("Error scanning repositories for unscoped search: {}", e.getMessage());
                return Collections.emptyList();
            }

            if (activeReaders.isEmpty()) {
                return Collections.emptyList();
            }

            try (MultiReader multiReader = new MultiReader(activeReaders.toArray(new DirectoryReader[0]), false)) {
                IndexSearcher multiSearcher = new IndexSearcher(multiReader);
                multiSearcher.setSimilarity(new BM25Similarity(config.getK1(), config.getB()));

                Query query = queryBuilder.buildQuery(queryStr, null);
                TopDocs topDocs = multiSearcher.search(query, topK);

                List<Document> results = new ArrayList<>(topDocs.scoreDocs.length);
                for (ScoreDoc sd : topDocs.scoreDocs) {
                    org.apache.lucene.document.Document luceneDoc = multiSearcher.storedFields().document(sd.doc);
                    results.add(mapper.toSpringAiDocument(luceneDoc, sd.score));
                }
                return results;
            } catch (IOException e) {
                log.error("Error executing unscoped Lucene search: {}", e.getMessage(), e);
                return Collections.emptyList();
            }
        } finally {
            for (SearcherHolder holder : leasedHolders) {
                holder.releaseRef();
            }
        }
    }

    /**
     * Checks if an active published generation exists for the specified repository.
     */
    public boolean hasIndex(String repositoryId) {
        Path repoDir = resolveRepoDir(repositoryId);
        String safeRepoId = repoDir.getFileName().toString();
        Path currentFile = repoDir.resolve("CURRENT");
        if (!Files.exists(currentFile)) {
            return false;
        }
        try {
            String currentGen = Files.readString(currentFile, StandardCharsets.UTF_8).trim();
            Path genDir = repoDir.resolve(currentGen).normalize();
            if (!genDir.startsWith(repoDir) || genDir.equals(repoDir)) {
                return false;
            }
            return Files.isDirectory(genDir);
        } catch (IOException e) {
            log.debug("Failed to verify index existence for repo {}: {}", safeRepoId, e.getMessage());
            return false;
        }
    }

    /**
     * Returns the number of indexed documents in the active generation of the repository,
     * or 0 if no active generation exists.
     */
    public int getDocumentCount(String repositoryId) {
        Path repoDir = resolveRepoDir(repositoryId);
        String safeRepoId = repoDir.getFileName().toString();
        SearcherHolder holder = acquireSearcher(safeRepoId);
        if (holder == null) {
            return 0;
        }
        try {
            return holder.reader.numDocs();
        } finally {
            holder.releaseRef();
        }
    }

    /**
     * Lists all repositories that have an active published generation.
     */
    public List<String> listRepositories() {
        Path baseDir = Path.of(config.getIndexDir()).toAbsolutePath().normalize();
        if (!Files.isDirectory(baseDir)) {
            return Collections.emptyList();
        }
        List<String> repos = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(baseDir)) {
            for (Path repoPath : stream) {
                if (Files.isDirectory(repoPath) && Files.exists(repoPath.resolve("CURRENT"))) {
                    String name = repoPath.getFileName().toString();
                    if (!name.equals(".") && !name.equals("..") && SAFE_REPO_ID_PATTERN.matcher(name).matches()) {
                        repos.add(name);
                    }
                }
            }
        } catch (IOException e) {
            log.debug("Error listing repository directories: {}", e.getMessage());
        }
        return repos;
    }

    /**
     * Lazily acquires or refreshes a cached point-in-time SearcherHolder for a repository,
     * returning a holder that is already leased via acquireRef().
     * The caller is responsible for calling releaseRef() on the returned holder.
     */
    private SearcherHolder acquireSearcher(String repositoryId) {
        Path repoDir = resolveRepoDir(repositoryId);
        String safeRepoId = repoDir.getFileName().toString();
        Path currentFile = repoDir.resolve("CURRENT");
        if (!Files.exists(currentFile)) {
            return null;
        }

        for (int attempt = 0; attempt < 15; attempt++) {
            if (closed.get()) {
                return null;
            }

            if (attempt > 0) {
                try {
                    Thread.sleep(15);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }

            String currentGen = readCurrentPointer(currentFile, safeRepoId);
            if (currentGen == null || currentGen.isBlank()) {
                continue;
            }

            Path genDir = repoDir.resolve(currentGen).normalize();
            if (!genDir.startsWith(repoDir) || genDir.equals(repoDir)) {
                return null; // Path traversal security violation
            }
            if (!Files.isDirectory(genDir)) {
                // Generation directory was removed/replaced by concurrent publication, retry with fresh CURRENT
                continue;
            }

            SearcherHolder existing = searcherCache.get(safeRepoId);
            if (existing != null && existing.genName.equals(currentGen)) {
                if (existing.acquireRef()) {
                    boolean keepLease = false;
                    try {
                        DirectoryReader newReader = null;
                        try {
                            newReader = DirectoryReader.openIfChanged(existing.reader);
                        } catch (AlreadyClosedException e) {
                            continue;
                        } catch (IOException e) {
                            log.debug("Error checking openIfChanged for {}: {}", safeRepoId, e.getMessage());
                        }

                        if (newReader == null) {
                            keepLease = true;
                            return existing;
                        }

                        IndexSearcher newSearcher = new IndexSearcher(newReader);
                        newSearcher.setSimilarity(new BM25Similarity(config.getK1(), config.getB()));
                        SearcherHolder refreshed = new SearcherHolder(currentGen, existing.directory, newReader, newSearcher);
                        if (refreshed.acquireRef()) {
                            searcherCache.put(safeRepoId, refreshed);
                            existing.transferDirectoryOwnershipAndClose();
                            return refreshed;
                        } else {
                            refreshed.close();
                            continue;
                        }
                    } finally {
                        if (!keepLease) {
                            existing.releaseRef();
                        }
                    }
                }
            }

            synchronized (searcherCache) {
                // Re-read latest CURRENT pointer under lock to avoid using a stale pointer
                String latestGen = readCurrentPointer(currentFile, safeRepoId);
                if (latestGen == null || latestGen.isBlank()) {
                    continue;
                }
                Path latestGenDir = repoDir.resolve(latestGen).normalize();
                if (!latestGenDir.startsWith(repoDir) || latestGenDir.equals(repoDir)) {
                    return null;
                }

                existing = searcherCache.get(safeRepoId);
                if (existing != null && existing.genName.equals(latestGen)) {
                    if (existing.acquireRef()) {
                        return existing;
                    }
                }

                // Never close existing if it matches latestGen
                if (existing != null && !existing.genName.equals(latestGen)) {
                    existing.close();
                }

                if (!Files.isDirectory(latestGenDir)) {
                    continue;
                }

                try {
                    Directory dir = FSDirectory.open(latestGenDir);
                    DirectoryReader reader = DirectoryReader.open(dir);
                    IndexSearcher searcher = new IndexSearcher(reader);
                    searcher.setSimilarity(new BM25Similarity(config.getK1(), config.getB()));
                    SearcherHolder created = new SearcherHolder(latestGen, dir, reader, searcher);
                    if (created.acquireRef()) {
                        searcherCache.put(safeRepoId, created);
                        return created;
                    } else {
                        created.close();
                        continue;
                    }
                } catch (IOException e) {
                    log.debug("Failed to open IndexSearcher for repo {} generation {}: {}",
                            safeRepoId, latestGen, e.getMessage());
                    continue;
                }
            }
        }
        return null;
    }

    private void evictAndCloseSearcher(String repositoryId) {
        SearcherHolder holder = searcherCache.remove(repositoryId);
        if (holder != null) {
            holder.close();
        }
    }

    private void cleanupOrphans(Path repoDir, String currentGenName) {
        String safeRepoId = (repoDir != null && repoDir.getFileName() != null)
                ? repoDir.getFileName().toString() : "default";

        synchronized (getRepoLock(safeRepoId)) {
            Set<String> activeGens = activeStagingSessions.stream()
                    .map(StagingSession::getGenerationName)
                    .collect(Collectors.toSet());

            Set<Path> activeDirs = activeStagingSessions.stream()
                    .map(s -> s.getStagingDir().toAbsolutePath().normalize())
                    .collect(Collectors.toSet());

            Set<String> inFlightTmpGens = new HashSet<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(repoDir)) {
                for (Path entry : stream) {
                    if (Files.isRegularFile(entry)) {
                        String name = entry.getFileName().toString();
                        if (name.startsWith("CURRENT") && name.endsWith(".tmp")) {
                            try {
                                long ageMs = System.currentTimeMillis() - Files.getLastModifiedTime(entry).toMillis();
                                if (ageMs > CRASH_REMNANT_SWEEP_THRESHOLD_MS) {
                                    Files.deleteIfExists(entry);
                                } else {
                                    String gen = Files.readString(entry, StandardCharsets.UTF_8).trim();
                                    if (!gen.isEmpty()) {
                                        inFlightTmpGens.add(gen);
                                    }
                                }
                            } catch (IOException ignored) {
                            }
                        }
                    }
                }
            } catch (IOException e) {
                log.debug("Error inspecting temporary pointers during orphan cleanup for {}: {}", repoDir, e.getMessage());
            }

            try (DirectoryStream<Path> stream = Files.newDirectoryStream(repoDir)) {
                for (Path entry : stream) {
                    if (Files.isDirectory(entry)) {
                        String name = entry.getFileName().toString();
                        if (name.startsWith("gen_")
                                && !name.equals(currentGenName)
                                && !activeGens.contains(name)
                                && !inFlightTmpGens.contains(name)
                                && !activeDirs.contains(entry.toAbsolutePath().normalize())) {
                            deleteRecursively(entry);
                        }
                    }
                }
            } catch (IOException e) {
                log.debug("Orphan cleanup exception for {}: {}", repoDir, e.getMessage());
            }
        }
    }

    private void deleteRecursively(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    try {
                        Files.delete(file);
                    } catch (IOException ignored) {
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                    try {
                        Files.delete(dir);
                    } catch (IOException ignored) {
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.debug("Directory cleanup error for {}: {}", root, e.getMessage());
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("LuceneGenerationStore has been closed");
        }
    }

    @PreDestroy
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            for (SearcherHolder holder : searcherCache.values()) {
                holder.close();
                try {
                    holder.directory.close();
                } catch (Exception ignored) {
                }
            }
            searcherCache.clear();

            for (StagingSession session : activeStagingSessions) {
                discardGeneration(session);
            }
            activeStagingSessions.clear();
        }
    }

    int getSearcherRefCount(String repositoryId) {
        Path repoDir = resolveRepoDir(repositoryId);
        String safeRepoId = repoDir.getFileName().toString();
        SearcherHolder holder = searcherCache.get(safeRepoId);
        if (holder == null) {
            return -1;
        }
        return holder.reader.getRefCount();
    }

    private static class SearcherHolder {
        final String genName;
        final Directory directory;
        final DirectoryReader reader;
        final IndexSearcher searcher;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        SearcherHolder(String genName, Directory directory, DirectoryReader reader, IndexSearcher searcher) {
            this.genName = genName;
            this.directory = directory;
            this.reader = reader;
            this.searcher = searcher;
        }

        boolean acquireRef() {
            if (closed.get()) {
                return false;
            }
            return reader.tryIncRef();
        }

        void releaseRef() {
            try {
                reader.decRef();
            } catch (Exception ignored) {
            }
        }

        void close() {
            if (closed.compareAndSet(false, true)) {
                try {
                    reader.decRef();
                } catch (Exception ignored) {
                }
            }
        }

        void transferDirectoryOwnershipAndClose() {
            close();
        }
    }
}
