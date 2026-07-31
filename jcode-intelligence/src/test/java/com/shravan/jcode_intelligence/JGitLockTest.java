package com.shravan.jcode_intelligence;

import com.shravan.jcode_intelligence.config.ChunkingConfig;
import com.shravan.jcode_intelligence.config.JGitConfiguration;
import com.shravan.jcode_intelligence.converter.DocumentConverter;
import com.shravan.jcode_intelligence.model.CodeChunk;
import com.shravan.jcode_intelligence.parser.*;
import com.shravan.jcode_intelligence.service.impl.GitServiceImpl;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    public void tearDown() {
        if (fixtureRepoDir != null && Files.exists(fixtureRepoDir)) {
            FileSystemUtils.deleteRecursively(fixtureRepoDir);
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
