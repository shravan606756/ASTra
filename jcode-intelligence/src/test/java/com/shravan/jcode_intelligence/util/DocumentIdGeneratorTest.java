package com.shravan.jcode_intelligence.util;

import com.shravan.jcode_intelligence.model.EmbeddedRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DocumentIdGeneratorTest {

    @Test
    @DisplayName("Deterministic ID produces valid RFC-4122 type 3 UUID")
    void testValidRfc4122Type3Uuid() {
        String id = DocumentIdGenerator.generateDeterministicId("repo-1", "chunk-42", "content");
        assertNotNull(id);
        UUID uuid = assertDoesNotThrow(() -> UUID.fromString(id));
        assertEquals(3, uuid.version(), "Must be version 3 name-based UUID");
    }

    @Test
    @DisplayName("Non-blank chunkId takes precedence over content")
    void testChunkIdPrecedence() {
        String id1 = DocumentIdGenerator.generateDeterministicId("repo-1", "chunk-42", "content-A");
        String id2 = DocumentIdGenerator.generateDeterministicId("repo-1", "chunk-42", "content-B");
        assertEquals(id1, id2, "ID must be identical regardless of content when chunkId is non-blank");
    }

    @Test
    @DisplayName("Null or blank chunkId falls back to content")
    void testFallbackToContentWhenChunkIdBlank() {
        String id1 = DocumentIdGenerator.generateDeterministicId("repo-1", null, "content-A");
        String id2 = DocumentIdGenerator.generateDeterministicId("repo-1", "", "content-A");
        String id3 = DocumentIdGenerator.generateDeterministicId("repo-1", "   ", "content-A");
        String id4 = DocumentIdGenerator.generateDeterministicId("repo-1", null, "content-B");

        assertEquals(id1, id2);
        assertEquals(id1, id3);
        assertNotEquals(id1, id4, "Different content must produce different IDs when chunkId is missing");
    }

    @Test
    @DisplayName("Null or blank repositoryId defaults to 'default'")
    void testDefaultRepositoryId() {
        String idDefault = DocumentIdGenerator.generateDeterministicId("default", "chunk-1", "code");
        String idNull = DocumentIdGenerator.generateDeterministicId(null, "chunk-1", "code");
        String idEmpty = DocumentIdGenerator.generateDeterministicId("", "chunk-1", "code");
        String idBlank = DocumentIdGenerator.generateDeterministicId("   ", "chunk-1", "code");

        assertEquals(idDefault, idNull);
        assertEquals(idDefault, idEmpty);
        assertEquals(idDefault, idBlank);
    }

    @Test
    @DisplayName("DocumentIdGenerator and EmbeddedRecord produce identical IDs (parity)")
    void testParityWithEmbeddedRecord() {
        String id1 = DocumentIdGenerator.generateDeterministicId("repo-x", "c-1", "body");
        String id2 = EmbeddedRecord.generateDeterministicId("repo-x", "c-1", "body");
        assertEquals(id1, id2);
    }

    @Test
    @DisplayName("Two overloaded methods in the same class get different IDs")
    void testOverloadedMethodsInSameClassGetDifferentIds() {
        String repo = "my-repo";
        String filePath = "src/main/java/com/example/Calculator.java";
        String chunkId = "com.example.Calculator::METHOD::add";

        String id1 = DocumentIdGenerator.generateDeterministicId(
                repo, filePath, chunkId, "int add(int a, int b)", 10, 15, null, "return a + b;"
        );
        String id2 = DocumentIdGenerator.generateDeterministicId(
                repo, filePath, chunkId, "double add(double a, double b)", 20, 25, null, "return a + b;"
        );

        assertNotEquals(id1, id2, "Overloaded methods with different signatures must have different deterministic IDs");
    }

    @Test
    @DisplayName("Two same-named classes in different files get different IDs")
    void testSameNamedClassesInDifferentFilesGetDifferentIds() {
        String repo = "my-repo";
        String chunkId = "com.example.TestFixture::CLASS";
        String signature = "public class TestFixture";

        String idMain = DocumentIdGenerator.generateDeterministicId(
                repo, "src/main/java/com/example/TestFixture.java", chunkId, signature, 1, 20, null, "public class TestFixture {}"
        );
        String idTest = DocumentIdGenerator.generateDeterministicId(
                repo, "src/test/java/com/example/TestFixture.java", chunkId, signature, 1, 20, null, "public class TestFixture {}"
        );

        assertNotEquals(idMain, idTest, "Classes with same name in different files must have different deterministic IDs");
    }

    @Test
    @DisplayName("Method fragments for the same method get different IDs")
    void testMethodFragmentsGetDifferentIds() {
        String repo = "my-repo";
        String filePath = "src/main/java/com/example/BigMethod.java";
        String chunkId = "com.example.BigMethod::METHOD_FRAGMENT::process";
        String signature = "void process()";

        String frag0 = DocumentIdGenerator.generateDeterministicId(
                repo, filePath, chunkId, signature, 10, 50, 0, "fragment 0"
        );
        String frag1 = DocumentIdGenerator.generateDeterministicId(
                repo, filePath, chunkId, signature, 51, 90, 1, "fragment 1"
        );

        assertNotEquals(frag0, frag1, "Distinct method fragments must have distinct deterministic IDs");
    }

    @Test
    @DisplayName("File path normalization handles backslashes and slashes identically")
    void testFilePathNormalization() {
        String repo = "my-repo";
        String chunkId = "com.example.App::CLASS";
        String sig = "public class App";

        String winId = DocumentIdGenerator.generateDeterministicId(
                repo, "src\\main\\java\\com\\example\\App.java", chunkId, sig, 1, 10, null, "class App {}"
        );
        String unixId = DocumentIdGenerator.generateDeterministicId(
                repo, "src/main/java/com/example/App.java", chunkId, sig, 1, 10, null, "class App {}"
        );

        assertEquals(winId, unixId, "Normalized Windows and Unix paths must yield identical IDs");
    }
}
