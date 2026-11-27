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

    @Test
    @DisplayName("Two classes in the same file with identical oversized method signatures and corresponding fragment indices get distinct IDs")
    void testTwoClassesInSameFileWithIdenticalSignaturesAndFragmentIndices() {
        String repo = "test-repo";
        String filePath = "src/main/java/com/example/MultiClassSource.java";
        String signature = "public void execute(String payload)";

        // Class A fragment 0
        com.shravan.jcode_intelligence.model.CodeChunk classAFrag0 = new com.shravan.jcode_intelligence.model.CodeChunk();
        classAFrag0.setRepositoryId(repo);
        classAFrag0.setFilePath(filePath);
        classAFrag0.setParentChunkId("com.example.ClassA::CLASS");
        classAFrag0.setId("com.example.ClassA::CLASS::METHOD_FRAGMENT::execute#fragment0");
        classAFrag0.setMethodName("execute");
        classAFrag0.setSignature(signature);
        classAFrag0.setStartLine(10);
        classAFrag0.setEndLine(25);
        classAFrag0.setFragmentIndex(0);
        classAFrag0.setContent("// Fragment 0 of ClassA.execute");

        // Class B fragment 0
        com.shravan.jcode_intelligence.model.CodeChunk classBFrag0 = new com.shravan.jcode_intelligence.model.CodeChunk();
        classBFrag0.setRepositoryId(repo);
        classBFrag0.setFilePath(filePath);
        classBFrag0.setParentChunkId("com.example.ClassB::CLASS");
        classBFrag0.setId("com.example.ClassB::CLASS::METHOD_FRAGMENT::execute#fragment0");
        classBFrag0.setMethodName("execute");
        classBFrag0.setSignature(signature);
        classBFrag0.setStartLine(40);
        classBFrag0.setEndLine(55);
        classBFrag0.setFragmentIndex(0);
        classBFrag0.setContent("// Fragment 0 of ClassB.execute");

        String idA0 = DocumentIdGenerator.generateDeterministicId(classAFrag0);
        String idB0 = DocumentIdGenerator.generateDeterministicId(classBFrag0);

        assertNotEquals(idA0, idB0, "Corresponding fragments of identical methods in different classes of the same file must have distinct IDs");

        // Class A fragment 1 and Class B fragment 1
        com.shravan.jcode_intelligence.model.CodeChunk classAFrag1 = new com.shravan.jcode_intelligence.model.CodeChunk();
        classAFrag1.setRepositoryId(repo);
        classAFrag1.setFilePath(filePath);
        classAFrag1.setParentChunkId("com.example.ClassA::CLASS");
        classAFrag1.setId("com.example.ClassA::CLASS::METHOD_FRAGMENT::execute#fragment1");
        classAFrag1.setMethodName("execute");
        classAFrag1.setSignature(signature);
        classAFrag1.setStartLine(26);
        classAFrag1.setEndLine(35);
        classAFrag1.setFragmentIndex(1);

        com.shravan.jcode_intelligence.model.CodeChunk classBFrag1 = new com.shravan.jcode_intelligence.model.CodeChunk();
        classBFrag1.setRepositoryId(repo);
        classBFrag1.setFilePath(filePath);
        classBFrag1.setParentChunkId("com.example.ClassB::CLASS");
        classBFrag1.setId("com.example.ClassB::CLASS::METHOD_FRAGMENT::execute#fragment1");
        classBFrag1.setMethodName("execute");
        classBFrag1.setSignature(signature);
        classBFrag1.setStartLine(56);
        classBFrag1.setEndLine(65);
        classBFrag1.setFragmentIndex(1);

        String idA1 = DocumentIdGenerator.generateDeterministicId(classAFrag1);
        String idB1 = DocumentIdGenerator.generateDeterministicId(classBFrag1);

        assertNotEquals(idA1, idB1, "Fragment 1 of identical methods in different classes of the same file must have distinct IDs");
        assertNotEquals(idA0, idA1, "Fragment 0 and fragment 1 of same class must have distinct IDs");
        assertNotEquals(idB0, idB1, "Fragment 0 and fragment 1 of same class must have distinct IDs");

        // Verify that even if chunk IDs are missing, line ranges disambiguate them
        String idA0NoChunkId = DocumentIdGenerator.generateDeterministicId(
                repo, filePath, null, signature, 10, 25, 0, "// Fragment 0 of ClassA.execute"
        );
        String idB0NoChunkId = DocumentIdGenerator.generateDeterministicId(
                repo, filePath, null, signature, 40, 55, 0, "// Fragment 0 of ClassB.execute"
        );
        assertNotEquals(idA0NoChunkId, idB0NoChunkId,
                "Even without chunk ID, line ranges in same file must produce distinct IDs despite identical signatures and fragment indices");
    }
}
