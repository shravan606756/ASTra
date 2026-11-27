package com.shravan.jcode_intelligence.service.evaluation.citation;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CitationValidatorTest {

    private CitationValidator validator;

    @BeforeEach
    void setUp() {
        validator = new CitationValidator();
    }

    private Git initGitRepo(Path dir) throws Exception {
        Git git = Git.init().setDirectory(dir.toFile()).call();
        StoredConfig config = git.getRepository().getConfig();
        config.setString("user", null, "name", "Test User");
        config.setString("user", null, "email", "test@example.com");
        config.save();
        return git;
    }

    @Test
    @DisplayName("Matched revision and clean working tree validates Java file with symbol successfully")
    void testMatchedRevisionAndCleanRepo_validatesJavaFileWithSymbol(@TempDir Path tempDir) throws Exception {
        try (Git git = initGitRepo(tempDir)) {
            Path javaFile = tempDir.resolve("Service.java");
            String code = """
                    package com.example;
                    
                    public class Service {
                        public void execute() {
                            System.out.println("Executing");
                        }
                    }
                    """;
            Files.writeString(javaFile, code);

            git.add().addFilepattern(".").call();
            RevCommit commit = git.commit().setMessage("Initial commit").call();

            Citation citation = Citation.valid("[cite: Service.java:4-6#execute]", "Service.java", 4, 6, "execute");
            List<CitationValidationResult> results = validator.validateAll(
                    List.of(citation), tempDir.toString(), commit.getName());

            assertEquals(1, results.size());
            CitationValidationResult res = results.get(0);
            assertTrue(res.structurallyValid());
            assertEquals(RevisionStatus.MATCHED, res.revisionStatus());
            assertEquals(FileStatus.VALID, res.fileStatus());
            assertEquals(EntityStatus.VALID, res.entityStatus());
        }
    }

    @Test
    @DisplayName("Mismatched revision skips validation rather than returning syntax-only validity")
    void testMismatchedRevision_skipsValidation(@TempDir Path tempDir) throws Exception {
        try (Git git = initGitRepo(tempDir)) {
            Path javaFile = tempDir.resolve("Service.java");
            Files.writeString(javaFile, "public class Service {}");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("Initial").call();

            Citation citation = Citation.valid("[cite: Service.java:1-1#Service]", "Service.java", 1, 1, "Service");
            String wrongRevision = "0123456789abcdef0123456789abcdef01234567";

            List<CitationValidationResult> results = validator.validateAll(
                    List.of(citation), tempDir.toString(), wrongRevision);

            assertEquals(1, results.size());
            CitationValidationResult res = results.get(0);
            assertFalse(res.structurallyValid());
            assertEquals(RevisionStatus.MISMATCHED, res.revisionStatus());
            assertEquals(FileStatus.SKIPPED, res.fileStatus());
            assertEquals(EntityStatus.SKIPPED, res.entityStatus());
            assertTrue(res.isSkipped());
        }
    }

    @Test
    @DisplayName("Dirty working tree reports DIRTY revision state and skips validation to avoid uncommitted edits")
    void testDirtyWorkingTree_reportsDirtyState(@TempDir Path tempDir) throws Exception {
        try (Git git = initGitRepo(tempDir)) {
            Path javaFile = tempDir.resolve("Service.java");
            Files.writeString(javaFile, "public class Service {}");
            git.add().addFilepattern(".").call();
            RevCommit commit = git.commit().setMessage("Initial").call();

            // Modify file without committing -> makes working tree dirty with declaration that exists only in uncommitted edits
            Files.writeString(javaFile, "public class Service {\n    void uncommittedMethod() {}\n}");

            // Citation references uncommittedMethod and lines 2-3 which exist only in uncommitted edits
            Citation citation = Citation.valid("[cite: Service.java:2-3#uncommittedMethod]", "Service.java", 2, 3, "uncommittedMethod");
            List<CitationValidationResult> results = validator.validateAll(
                    List.of(citation), tempDir.toString(), commit.getName());

            assertEquals(1, results.size());
            CitationValidationResult res = results.get(0);
            assertEquals(RevisionStatus.DIRTY, res.revisionStatus());
            assertTrue(res.isSkipped(), "Validation must be marked skipped when checkout is dirty");
            assertFalse(res.structurallyValid(), "Uncommitted declaration must not pass structural validation for committed revision");
            assertEquals(FileStatus.SKIPPED, res.fileStatus());
            assertEquals(EntityStatus.SKIPPED, res.entityStatus());
        }
    }

    @Test
    @DisplayName("validateSingle skips validation when revisionStatus is DIRTY")
    void testValidateSingle_skipsWhenDirty(@TempDir Path tempDir) {
        Citation citation = Citation.valid("[cite: Foo.java:1-5#bar]", "Foo.java", 1, 5, "bar");
        CitationValidationResult res = validator.validateSingle(citation, tempDir, RevisionStatus.DIRTY);
        assertEquals(RevisionStatus.DIRTY, res.revisionStatus());
        assertTrue(res.isSkipped());
        assertFalse(res.structurallyValid());
        assertEquals(FileStatus.SKIPPED, res.fileStatus());
        assertEquals(EntityStatus.SKIPPED, res.entityStatus());
    }

    @Test
    @DisplayName("Unavailable git repository skips validation")
    void testUnavailableGitRepo_skipsValidation(@TempDir Path tempDir) {
        // tempDir without .git
        Citation citation = Citation.valid("[cite: Foo.java:1-5]", "Foo.java", 1, 5, null);
        List<CitationValidationResult> results = validator.validateAll(
                List.of(citation), tempDir.toString(), null);

        assertEquals(1, results.size());
        CitationValidationResult res = results.get(0);
        assertFalse(res.structurallyValid());
        assertEquals(RevisionStatus.UNAVAILABLE, res.revisionStatus());
        assertEquals(FileStatus.SKIPPED, res.fileStatus());
        assertEquals(EntityStatus.SKIPPED, res.entityStatus());
    }

    @Test
    @DisplayName("Path traversal is rejected")
    void testPathTraversal_rejectsCitation(@TempDir Path tempDir) throws Exception {
        try (Git git = initGitRepo(tempDir)) {
            Path file = tempDir.resolve("Valid.java");
            Files.writeString(file, "public class Valid {}");
            git.add().addFilepattern(".").call();
            RevCommit commit = git.commit().setMessage("Initial").call();

            Citation traversal = Citation.valid("[cite: ../Outside.java:1-5]", "../Outside.java", 1, 5, null);
            List<CitationValidationResult> results = validator.validateAll(
                    List.of(traversal), tempDir.toString(), commit.getName());

            assertEquals(1, results.size());
            CitationValidationResult res = results.get(0);
            assertFalse(res.structurallyValid());
            assertEquals(FileStatus.TRAVERSAL_REJECTED, res.fileStatus());
        }
    }

    @Test
    @DisplayName("Line bounds exceeding file length are rejected")
    void testLineBounds_exceedsTotalLines(@TempDir Path tempDir) throws Exception {
        try (Git git = initGitRepo(tempDir)) {
            Path file = tempDir.resolve("Short.java");
            Files.writeString(file, "line 1\nline 2\nline 3\n");
            git.add().addFilepattern(".").call();
            RevCommit commit = git.commit().setMessage("Initial").call();

            Citation citation = Citation.valid("[cite: Short.java:1-100]", "Short.java", 1, 100, null);
            List<CitationValidationResult> results = validator.validateAll(
                    List.of(citation), tempDir.toString(), commit.getName());

            assertEquals(1, results.size());
            CitationValidationResult res = results.get(0);
            assertFalse(res.structurallyValid());
            assertEquals(FileStatus.OUT_OF_BOUNDS, res.fileStatus());
        }
    }

    @Test
    @DisplayName("Entity not found when symbol is not present in cited range")
    void testJavaEntity_symbolNotFound(@TempDir Path tempDir) throws Exception {
        try (Git git = initGitRepo(tempDir)) {
            Path file = tempDir.resolve("MyClass.java");
            Files.writeString(file, "public class MyClass {\n    void realMethod() {}\n}\n");
            git.add().addFilepattern(".").call();
            RevCommit commit = git.commit().setMessage("Initial").call();

            Citation citation = Citation.valid("[cite: MyClass.java:1-3#missingMethod]", "MyClass.java", 1, 3, "missingMethod");
            List<CitationValidationResult> results = validator.validateAll(
                    List.of(citation), tempDir.toString(), commit.getName());

            assertEquals(1, results.size());
            CitationValidationResult res = results.get(0);
            assertFalse(res.structurallyValid());
            assertEquals(FileStatus.VALID, res.fileStatus());
            assertEquals(EntityStatus.ENTITY_NOT_FOUND, res.entityStatus());
        }
    }

    @Test
    @DisplayName("Ambiguous match when multiple declarations match symbol in range")
    void testJavaEntity_ambiguousMatch(@TempDir Path tempDir) throws Exception {
        try (Git git = initGitRepo(tempDir)) {
            Path file = tempDir.resolve("Overloads.java");
            String code = """
                    public class Overloads {
                        void test() {}
                        void test(int x) {}
                    }
                    """;
            Files.writeString(file, code);
            git.add().addFilepattern(".").call();
            RevCommit commit = git.commit().setMessage("Initial").call();

            Citation citation = Citation.valid("[cite: Overloads.java:2-3#test]", "Overloads.java", 2, 3, "test");
            List<CitationValidationResult> results = validator.validateAll(
                    List.of(citation), tempDir.toString(), commit.getName());

            assertEquals(1, results.size());
            CitationValidationResult res = results.get(0);
            assertFalse(res.structurallyValid());
            assertEquals(EntityStatus.AMBIGUOUS_MATCH, res.entityStatus());
        }
    }

    @Test
    @DisplayName("Parse error when Java file has syntax errors")
    void testJavaEntity_parseError(@TempDir Path tempDir) throws Exception {
        try (Git git = initGitRepo(tempDir)) {
            Path file = tempDir.resolve("Corrupt.java");
            Files.writeString(file, "public class Corrupt { unclosed syntax ;;; {{");
            git.add().addFilepattern(".").call();
            RevCommit commit = git.commit().setMessage("Initial").call();

            Citation citation = Citation.valid("[cite: Corrupt.java:1-1#foo]", "Corrupt.java", 1, 1, "foo");
            List<CitationValidationResult> results = validator.validateAll(
                    List.of(citation), tempDir.toString(), commit.getName());

            assertEquals(1, results.size());
            CitationValidationResult res = results.get(0);
            assertFalse(res.structurallyValid());
            assertEquals(EntityStatus.PARSE_ERROR, res.entityStatus());
        }
    }

    @Test
    @DisplayName("Non-Java file checks existence and bounds, returning NOT_APPLICABLE for entity status")
    void testNonJavaFile_returnsNotApplicable(@TempDir Path tempDir) throws Exception {
        try (Git git = initGitRepo(tempDir)) {
            Path file = tempDir.resolve("schema.sql");
            Files.writeString(file, "CREATE TABLE users (\n    id BIGINT PRIMARY KEY,\n    name TEXT\n);\n");
            git.add().addFilepattern(".").call();
            RevCommit commit = git.commit().setMessage("Initial").call();

            Citation citation = Citation.valid("[cite: schema.sql:1-4]", "schema.sql", 1, 4, null);
            List<CitationValidationResult> results = validator.validateAll(
                    List.of(citation), tempDir.toString(), commit.getName());

            assertEquals(1, results.size());
            CitationValidationResult res = results.get(0);
            assertTrue(res.structurallyValid());
            assertEquals(FileStatus.VALID, res.fileStatus());
            assertEquals(EntityStatus.NOT_APPLICABLE, res.entityStatus());
        }
    }

    @Test
    @DisplayName("Malformed citation returns INVALID_SYNTAX")
    void testMalformedCitation_returnsInvalidSyntax(@TempDir Path tempDir) throws Exception {
        try (Git git = initGitRepo(tempDir)) {
            Path file = tempDir.resolve("Foo.java");
            Files.writeString(file, "public class Foo {}");
            git.add().addFilepattern(".").call();
            RevCommit commit = git.commit().setMessage("Initial").call();

            Citation malformed = Citation.malformed("[cite: broken]");
            List<CitationValidationResult> results = validator.validateAll(
                    List.of(malformed), tempDir.toString(), commit.getName());

            assertEquals(1, results.size());
            CitationValidationResult res = results.get(0);
            assertFalse(res.structurallyValid());
            assertEquals(FileStatus.INVALID_SYNTAX, res.fileStatus());
        }
    }

    @Test
    @DisplayName("Validates Java file and symbol in repository-relative path containing spaces")
    void testValidatesPathWithSpaces(@TempDir Path tempDir) throws Exception {
        try (Git git = initGitRepo(tempDir)) {
            Path subDir = tempDir.resolve("src/my package");
            Files.createDirectories(subDir);
            Path javaFile = subDir.resolve("Foo.java");
            String code = """
                    package my_package;

                    public class Foo {
                        public void doWork() {
                            System.out.println("working");
                        }
                    }
                    """;
            Files.writeString(javaFile, code);

            git.add().addFilepattern(".").call();
            RevCommit commit = git.commit().setMessage("Commit with spaces in path").call();

            Citation citation = Citation.valid("[cite: src/my package/Foo.java:4-6#doWork]", "src/my package/Foo.java", 4, 6, "doWork");
            List<CitationValidationResult> results = validator.validateAll(
                    List.of(citation), tempDir.toString(), commit.getName());

            assertEquals(1, results.size());
            CitationValidationResult res = results.get(0);
            assertTrue(res.structurallyValid(), "Citation with spaces in path must be structurally valid");
            assertEquals(RevisionStatus.MATCHED, res.revisionStatus());
            assertEquals(FileStatus.VALID, res.fileStatus());
            assertEquals(EntityStatus.VALID, res.entityStatus());

            // Path traversal with spaces should still be rejected
            Citation traversalCitation = Citation.valid("[cite: src/my package/../../Secret.java:1-5]", "src/my package/../../Secret.java", 1, 5, null);
            List<CitationValidationResult> traversalResults = validator.validateAll(
                    List.of(traversalCitation), tempDir.toString(), commit.getName());

            assertEquals(1, traversalResults.size());
            CitationValidationResult traversalRes = traversalResults.get(0);
            assertFalse(traversalRes.structurallyValid());
            assertEquals(FileStatus.TRAVERSAL_REJECTED, traversalRes.fileStatus());
        }
    }
}
