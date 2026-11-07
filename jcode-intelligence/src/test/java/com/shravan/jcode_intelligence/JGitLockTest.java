package com.shravan.jcode_intelligence;

import com.shravan.jcode_intelligence.config.ChunkingConfig;
import com.shravan.jcode_intelligence.config.JGitConfiguration;
import com.shravan.jcode_intelligence.converter.DocumentConverter;
import com.shravan.jcode_intelligence.model.CodeChunk;
import com.shravan.jcode_intelligence.parser.*;
import com.shravan.jcode_intelligence.service.impl.GitServiceImpl;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link GitServiceImpl}'s clone/cleanup behaviour end-to-end against a
 * fully self-contained, isolated Git fixture that is created from scratch for every
 * test. No assumptions are made about the current working directory, the ASTra
 * repository checkout, GitHub Actions workspace layout, or network access - the
 * "remote" repository cloned in each test is a local, temporary Git repository
 * initialized with JGit and containing a minimal Java project.
 */
public class JGitLockTest {

    private GitServiceImpl gitService;
    private Path fixtureRepoDir;
    private String fixtureRepoUri;

    @BeforeEach
    public void setUp() throws Exception {
        JGitConfiguration config = new JGitConfiguration();
        config.configureJGit();

        gitService = new GitServiceImpl();
        fixtureRepoDir = createFixtureRepository();
        fixtureRepoUri = fixtureRepoDir.toUri().toString();
    }

    @AfterEach
    public void tearDown() throws IOException {
        if (fixtureRepoDir != null && Files.exists(fixtureRepoDir)) {
            gitService.cleanupRepository(fixtureRepoDir);
        }
    }

    @Test
    public void cloneOnly() throws IOException {
        Path clonedDir = gitService.cloneRepository(fixtureRepoUri);
        try {
            assertNotNull(clonedDir, "cloneRepository should return a non-null path");
            assertTrue(Files.exists(clonedDir), "cloned directory should exist after clone");
            assertTrue(Files.exists(clonedDir.resolve("src/main/java/com/example/Sample.java")),
                    "cloned repository should contain the expected source file");
        } finally {
            gitService.cleanupRepository(clonedDir);
        }
        assertFalse(Files.exists(clonedDir), "cleanupRepository should remove the temporary clone directory");
    }

    @Test
    public void cloneAndParse() throws IOException {
        Path clonedDir = gitService.cloneRepository(fixtureRepoUri);
        try {
            List<CodeChunk> chunks = parseProject(clonedDir);
            assertNotNull(chunks, "parser should return a non-null list of chunks");
            assertFalse(chunks.isEmpty(), "parsing the cloned project should produce at least one chunk");
        } finally {
            gitService.cleanupRepository(clonedDir);
        }
        assertFalse(Files.exists(clonedDir), "cleanupRepository should remove the temporary clone directory");
    }

    @Test
    public void cloneParseConvert() throws IOException {
        Path clonedDir = gitService.cloneRepository(fixtureRepoUri);
        try {
            List<CodeChunk> chunks = parseProject(clonedDir);
            assertFalse(chunks.isEmpty(), "parsing the cloned project should produce at least one chunk");

            DocumentConverter converter = new DocumentConverter();
            List<Document> docs = converter.convert(chunks);
            assertNotNull(docs, "converter should return a non-null list of documents");
            assertEquals(chunks.size(), docs.size(), "each chunk should be converted into exactly one document");
        } finally {
            gitService.cleanupRepository(clonedDir);
        }
        assertFalse(Files.exists(clonedDir), "cleanupRepository should remove the temporary clone directory");
    }

    @Test
    public void doubleCloneCleanup() throws IOException {
        Path firstClone = gitService.cloneRepository(fixtureRepoUri);
        try {
            assertTrue(Files.exists(firstClone), "first clone directory should exist");
        } finally {
            gitService.cleanupRepository(firstClone);
        }
        assertFalse(Files.exists(firstClone), "first clone directory should be removed after cleanup");

        Path secondClone = gitService.cloneRepository(fixtureRepoUri);
        try {
            assertTrue(Files.exists(secondClone), "second clone directory should exist");
            assertNotEquals(firstClone, secondClone, "each clone should use a distinct temporary directory");
        } finally {
            gitService.cleanupRepository(secondClone);
        }
        assertFalse(Files.exists(secondClone), "second clone directory should be removed after cleanup");
    }

    /**
     * Verifies that shallow cloning with depth 1 retrieves exactly 1 commit (the latest commit
     * on the default branch), even when the upstream remote repository contains multiple commits.
     */
    @Test
    public void shallowCloneDepthOne() throws Exception {
        // Verify fixture repository has multiple commits
        try (Git fixtureGit = Git.open(fixtureRepoDir.toFile())) {
            int fixtureCommitCount = 0;
            for (RevCommit c : fixtureGit.log().call()) {
                fixtureCommitCount++;
            }
            assertTrue(fixtureCommitCount > 1, "Fixture repo should have multiple commits");
        }

        // Clone with depth 1
        Path clonedDir = gitService.cloneRepository(fixtureRepoUri);
        try {
            assertTrue(Files.exists(clonedDir), "Cloned directory should exist");
            try (Git clonedGit = Git.open(clonedDir.toFile())) {
                int clonedCommitCount = 0;
                RevCommit singleCommit = null;
                for (RevCommit c : clonedGit.log().call()) {
                    clonedCommitCount++;
                    singleCommit = c;
                }
                assertEquals(1, clonedCommitCount, "Shallow clone should have exactly 1 commit");
                assertNotNull(singleCommit);
                assertEquals("Second commit", singleCommit.getShortMessage());
            }
        } finally {
            gitService.cleanupRepository(clonedDir);
        }
        assertFalse(Files.exists(clonedDir), "cleanupRepository should remove the temporary clone directory");
    }

    /**
     * Verifies that cloning an explicit branch checks out the requested branch, verifies
     * branch-specific source files are present, and confirms depth 1 commit truncation.
     */
    @Test
    public void cloneExplicitBranch() throws Exception {
        Path clonedDir = gitService.cloneRepository(fixtureRepoUri, "feature-branch");
        try {
            assertTrue(Files.exists(clonedDir), "Cloned directory should exist");
            assertTrue(Files.exists(clonedDir.resolve("src/main/java/com/example/Feature.java")),
                    "Explicitly cloned branch should contain Feature.java");
            try (Git clonedGit = Git.open(clonedDir.toFile())) {
                int commitCount = 0;
                RevCommit singleCommit = null;
                for (RevCommit c : clonedGit.log().call()) {
                    commitCount++;
                    singleCommit = c;
                }
                assertEquals(1, commitCount, "Shallow clone of feature branch should have exactly 1 commit");
                assertNotNull(singleCommit);
                assertEquals("Feature branch commit", singleCommit.getShortMessage());
            }
        } finally {
            gitService.cleanupRepository(clonedDir);
        }
        assertFalse(Files.exists(clonedDir), "cleanupRepository should remove the temporary clone directory");
    }

    /**
     * Verifies that attempting to clone a nonexistent branch throws {@link IOException}
     * and guarantees that the temporary clone directory is immediately deleted without leaks.
     */
    @Test
    public void cloneNonExistentBranchFailsAndCleansUp() throws IOException {
        Path tempBaseDir = Path.of(System.getProperty("java.io.tmpdir"));
        Set<Path> existingAstraDirs;
        try (Stream<Path> stream = Files.list(tempBaseDir)) {
            existingAstraDirs = stream
                    .filter(p -> p.getFileName().toString().startsWith("astra-repo-"))
                    .collect(Collectors.toSet());
        }

        IOException exception = assertThrows(IOException.class, () ->
                gitService.cloneRepository(fixtureRepoUri, "non-existent-branch-404"));

        assertTrue(exception.getMessage().contains("Failed to clone git repository"),
                "Exception message should indicate clone failure");
        assertTrue(exception.getMessage().contains("non-existent-branch-404"),
                "Exception message should mention the branch name");

        // Verify no leftover astra-repo-* directory was leaked
        try (Stream<Path> stream = Files.list(tempBaseDir)) {
            Set<Path> currentAstraDirs = stream
                    .filter(p -> p.getFileName().toString().startsWith("astra-repo-"))
                    .collect(Collectors.toSet());
            currentAstraDirs.removeAll(existingAstraDirs);
            assertTrue(currentAstraDirs.isEmpty(),
                    "No temporary astra-repo-* directory should remain after clone failure: " + currentAstraDirs);
        }
    }

    /**
     * Verifies that passing an empty or all-whitespace branch string gracefully falls back
     * to the remote's default branch rather than failing.
     */
    @Test
    public void cloneBlankAndEmptyBranchDefaultsToHead() throws Exception {
        // Test empty string branch
        Path emptyBranchClone = gitService.cloneRepository(fixtureRepoUri, "");
        try {
            assertTrue(Files.exists(emptyBranchClone), "Clone directory for empty branch should exist");
            try (Git git = Git.open(emptyBranchClone.toFile())) {
                RevCommit commit = git.log().call().iterator().next();
                assertEquals("Second commit", commit.getShortMessage(), "Empty branch should clone default branch HEAD");
            }
        } finally {
            gitService.cleanupRepository(emptyBranchClone);
        }

        // Test whitespace string branch
        Path whitespaceBranchClone = gitService.cloneRepository(fixtureRepoUri, "   \t  ");
        try {
            assertTrue(Files.exists(whitespaceBranchClone), "Clone directory for whitespace branch should exist");
            try (Git git = Git.open(whitespaceBranchClone.toFile())) {
                RevCommit commit = git.log().call().iterator().next();
                assertEquals("Second commit", commit.getShortMessage(), "Whitespace branch should clone default branch HEAD");
            }
        } finally {
            gitService.cleanupRepository(whitespaceBranchClone);
        }
    }

    /**
     * Verifies that branch names with leading or trailing whitespace are automatically trimmed
     * and successfully checked out.
     */
    @Test
    public void cloneBranchWithWhitespaceIsTrimmed() throws Exception {
        Path clonedDir = gitService.cloneRepository(fixtureRepoUri, "   feature-branch  \n");
        try {
            assertTrue(Files.exists(clonedDir), "Cloned directory should exist");
            assertTrue(Files.exists(clonedDir.resolve("src/main/java/com/example/Feature.java")),
                    "Trimmed branch clone should contain Feature.java");
            try (Git git = Git.open(clonedDir.toFile())) {
                RevCommit commit = git.log().call().iterator().next();
                assertEquals("Feature branch commit", commit.getShortMessage());
            }
        } finally {
            gitService.cleanupRepository(clonedDir);
        }
        assertFalse(Files.exists(clonedDir), "cleanupRepository should remove directory");
    }

    /**
     * Verifies that a full Git ref name (e.g. "refs/heads/feature-branch") is accepted and
     * cloned properly.
     */
    @Test
    public void cloneFullRefName() throws Exception {
        Path clonedDir = gitService.cloneRepository(fixtureRepoUri, "refs/heads/feature-branch");
        try {
            assertTrue(Files.exists(clonedDir), "Cloned directory should exist");
            assertTrue(Files.exists(clonedDir.resolve("src/main/java/com/example/Feature.java")),
                    "Full ref clone should contain Feature.java");
            try (Git git = Git.open(clonedDir.toFile())) {
                RevCommit commit = git.log().call().iterator().next();
                assertEquals("Feature branch commit", commit.getShortMessage());
            }
        } finally {
            gitService.cleanupRepository(clonedDir);
        }
        assertFalse(Files.exists(clonedDir), "cleanupRepository should remove directory");
    }

    /**
     * Verifies that .setNoTags() prevents tags in the upstream repository from being fetched
     * into the local shallow clone.
     */
    @Test
    public void shallowCloneSuppressesTags() throws Exception {
        // Assert upstream repository has at least one tag
        try (Git fixtureGit = Git.open(fixtureRepoDir.toFile())) {
            assertFalse(fixtureGit.tagList().call().isEmpty(), "Upstream fixture repository should contain tags");
        }

        // Clone with shallow clone
        Path clonedDir = gitService.cloneRepository(fixtureRepoUri);
        try {
            assertTrue(Files.exists(clonedDir));
            try (Git clonedGit = Git.open(clonedDir.toFile())) {
                assertTrue(clonedGit.tagList().call().isEmpty(),
                        "Shallow clone with .setNoTags() should not contain any tags");
            }
        } finally {
            gitService.cleanupRepository(clonedDir);
        }
    }

    /**
     * Verifies that single-branch cloning (.setCloneAllBranches(false)) only fetches
     * the requested branch and does not create local tracking branches for others.
     */
    @Test
    public void singleBranchCloneExcludesOtherBranches() throws Exception {
        Path clonedDir = gitService.cloneRepository(fixtureRepoUri, "feature-branch");
        try {
            assertTrue(Files.exists(clonedDir));
            try (Git clonedGit = Git.open(clonedDir.toFile())) {
                List<org.eclipse.jgit.lib.Ref> branches = clonedGit.branchList().call();
                assertEquals(1, branches.size(), "Should only have 1 local branch");
                assertTrue(branches.get(0).getName().endsWith("feature-branch"),
                        "Local branch should be feature-branch");
            }
        } finally {
            gitService.cleanupRepository(clonedDir);
        }
    }

    /**
     * Verifies that attempting to clone an invalid or unreachable repository URL
     * fails with IOException and guarantees that no temporary directory is leaked.
     */
    @Test
    public void cloneInvalidRepositoryUriFailsAndCleansUp() throws IOException {
        Path tempBaseDir = Path.of(System.getProperty("java.io.tmpdir"));
        Set<Path> existingAstraDirs;
        try (Stream<Path> stream = Files.list(tempBaseDir)) {
            existingAstraDirs = stream
                    .filter(p -> p.getFileName().toString().startsWith("astra-repo-"))
                    .collect(Collectors.toSet());
        }

        assertThrows(IOException.class, () ->
                gitService.cloneRepository("file:///C:/non_existent_fixture_path_99999"));

        try (Stream<Path> stream = Files.list(tempBaseDir)) {
            Set<Path> currentAstraDirs = stream
                    .filter(p -> p.getFileName().toString().startsWith("astra-repo-"))
                    .collect(Collectors.toSet());
            currentAstraDirs.removeAll(existingAstraDirs);
            assertTrue(currentAstraDirs.isEmpty(),
                    "No temporary directory should leak on invalid repository URL");
        }
    }

    /**
     * Verifies cleanupRepository handles null and nonexistent directory paths gracefully
     * without throwing exceptions.
     */
    @Test
    public void cleanupRepositoryHandlesNullAndNonExistent() {
        // Null path
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> gitService.cleanupRepository(null));

        // Nonexistent path
        Path nonExistent = Path.of(System.getProperty("java.io.tmpdir"), "astra-non-existent-" + System.nanoTime());
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> gitService.cleanupRepository(nonExistent));
    }

    /**
     * Verifies cleanupRepository successfully removes directories containing read-only files
     * (common on Windows with Git pack and index files).
     */
    @Test
    public void cleanupRepositoryRemovesReadOnlyFiles() throws IOException {
        Path tempDir = Files.createTempDirectory("astra-readonly-test-");
        Path subDir = Files.createDirectories(tempDir.resolve("sub"));
        Path readOnlyFile = subDir.resolve("locked.txt");
        Files.writeString(readOnlyFile, "locked content");

        // Mark file read-only
        assertTrue(readOnlyFile.toFile().setReadOnly(), "File should be marked read-only");
        assertFalse(readOnlyFile.toFile().canWrite(), "File should not be writable");

        gitService.cleanupRepository(tempDir);
        assertFalse(Files.exists(tempDir), "cleanupRepository should successfully delete directory with read-only files");
    }

    /**
     * Verifies rapid sequential cloning and cleanup operations do not encounter
     * Windows file locking or resource starvation issues.
     */
    @Test
    public void sequentialClonesStressTest() throws IOException {
        for (int i = 0; i < 3; i++) {
            Path clone = gitService.cloneRepository(fixtureRepoUri);
            assertTrue(Files.exists(clone), "Clone " + i + " should exist");
            gitService.cleanupRepository(clone);
            assertFalse(Files.exists(clone), "Clone " + i + " should be deleted");
        }
    }

    /**
     * Creates a fresh, isolated Git repository in a temporary directory containing a
     * minimal Java project (a single package with a single class), then stages and
     * commits it. Returns the path to the repository's working directory.
     */
    private Path createFixtureRepository() throws Exception {
        Path repoDir = Files.createTempDirectory("jgitlock-fixture-");
        try (Git git = Git.init().setDirectory(repoDir.toFile()).call()) {
            Path srcDir = repoDir.resolve("src/main/java/com/example");
            Files.createDirectories(srcDir);
            Path javaFile = srcDir.resolve("Sample.java");
            Files.writeString(javaFile,
                    "package com.example;\n\n" +
                    "public class Sample {\n\n" +
                    "    public int add(int a, int b) {\n" +
                    "        return a + b;\n" +
                    "    }\n" +
                    "}\n");

            git.add().addFilepattern(".").call();
            git.commit()
                    .setMessage("Initial commit")
                    .setAuthor("JGitLockTest", "jgitlocktest@example.com")
                    .setSign(false)
                    .call();

            // Commit 2 on default branch
            Files.writeString(javaFile,
                    "package com.example;\n\n" +
                    "public class Sample {\n\n" +
                    "    public int add(int a, int b) {\n" +
                    "        return a + b;\n" +
                    "    }\n" +
                    "    public int sub(int a, int b) {\n" +
                    "        return a - b;\n" +
                    "    }\n" +
                    "}\n");
            git.add().addFilepattern(".").call();
            git.commit()
                    .setMessage("Second commit")
                    .setAuthor("JGitLockTest", "jgitlocktest@example.com")
                    .setSign(false)
                    .call();

            // Create a feature branch with its own commit
            String defaultBranch = git.getRepository().getBranch();
            git.branchCreate().setName("feature-branch").call();
            git.checkout().setName("feature-branch").call();
            Path featureFile = srcDir.resolve("Feature.java");
            Files.writeString(featureFile,
                    "package com.example;\n\n" +
                    "public class Feature {\n" +
                    "    public String name() { return \"feature\"; }\n" +
                    "}\n");
            git.add().addFilepattern(".").call();
            git.commit()
                    .setMessage("Feature branch commit")
                    .setAuthor("JGitLockTest", "jgitlocktest@example.com")
                    .setSign(false)
                    .call();

            // Switch back to default branch
            git.checkout().setName(defaultBranch).call();

            // Create a release tag on default branch
            git.tag().setName("v1.0.0").setMessage("Release 1.0.0").call();
        }
        return repoDir;
    }

    private List<CodeChunk> parseProject(Path projectRoot) throws IOException {
        ChunkingConfig config = new ChunkingConfig();
        CharBasedBudgetEstimator budgetEstimator = new CharBasedBudgetEstimator(config);
        JavaProjectParser parser = new JavaProjectParser(
                new com.shravan.jcode_intelligence.config.JavaParserConfig().javaParser(),
                new AstVisitor(new ChunkGenerator(
                        new MetadataExtractor(),
                        new ClassSummaryBuilder(budgetEstimator),
                        new MethodFragmenter(new MetadataExtractor(), budgetEstimator),
                        budgetEstimator
                ))
        );
        return parser.parse(projectRoot);
    }
}
