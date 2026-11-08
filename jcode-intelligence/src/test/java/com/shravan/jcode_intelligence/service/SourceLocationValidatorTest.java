package com.shravan.jcode_intelligence.service;

import com.shravan.jcode_intelligence.dto.response.ChunkResponse;
import com.shravan.jcode_intelligence.service.SourceLocationValidator.LocationValidationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SourceLocationValidator} validating coordinate syntax,
 * temporary path prefix cleaning, directory traversal prevention, and physical
 * disk verification.
 */
class SourceLocationValidatorTest {

    private final SourceLocationValidator validator = new SourceLocationValidator();

    @Test
    @DisplayName("Synthetic PACKAGE chunks are exempted from physical filesystem verification")
    void testSyntheticPackageChunkExempted() {
        ChunkResponse pkgChunk = new ChunkResponse("PACKAGE", "repo-1", "com.example", "", "", "", "", 0, 0, "pkg overview");
        LocationValidationResult result = validator.validate(pkgChunk, "/non/existent/root");

        assertTrue(result.valid());
        assertTrue(result.linesValid());
        assertFalse(result.existsOnDisk());
        assertTrue(result.reason().contains("Synthetic chunk exempted"));
    }

    @Test
    @DisplayName("PACKAGE chunk with physical path undergoes standard validation and fails if file not found")
    void testPackageChunkWithPhysicalPathNotExempted(@TempDir Path tempRoot) {
        ChunkResponse pkgChunk = new ChunkResponse("PACKAGE", "repo-1", "com.example", "", "", "",
                "src/com/example/package-info.java", 1, 10, "pkg doc");

        LocationValidationResult result = validator.validate(pkgChunk, tempRoot.toString());

        assertFalse(result.valid(), "PACKAGE chunk with physical path must not be exempted from filesystem check");
        assertFalse(result.existsOnDisk());
        assertTrue(result.reason().contains("does not exist on disk"));
    }

    @Test
    @DisplayName("Empty or blank file paths fail validation for physical code chunks")
    void testEmptyOrBlankFilePathFails() {
        ChunkResponse blankPath = new ChunkResponse("METHOD", "repo-1", "com.example", "Foo", "bar", "bar()", "   ", 10, 20, "code");
        LocationValidationResult result = validator.validate(blankPath, null);

        assertFalse(result.valid());
        assertFalse(result.linesValid());
        assertTrue(result.reason().contains("empty or blank"));
    }

    @Test
    @DisplayName("Directory traversal sequences are detected and rejected")
    void testDirectoryTraversalRejected() {
        ChunkResponse traversalChunk = new ChunkResponse("METHOD", "repo-1", "com.example", "Foo", "bar", "bar()",
                "src/main/../../etc/passwd", 1, 10, "code");
        LocationValidationResult result = validator.validate(traversalChunk, null);

        assertFalse(result.valid());
        assertTrue(result.reason().contains("illegal directory traversal"));
    }

    @Test
    @DisplayName("Rejects only path segments equal to .. while permitting segment names containing dots")
    void testDirectoryTraversal_onlyExactDoubleDotSegmentRejected() {
        ChunkResponse validDots = new ChunkResponse("METHOD", "repo-1", "com.example", "Foo", "bar", "bar()",
                "src/foo..bar/App..java", 1, 10, "code");
        LocationValidationResult rValid = validator.validate(validDots, null);
        assertTrue(rValid.valid(), "Valid path segments containing multiple dots should not be rejected");

        ChunkResponse traversal1 = new ChunkResponse("METHOD", "repo-1", "com.example", "Foo", "bar", "bar()",
                "src/../App.java", 1, 10, "code");
        LocationValidationResult rInvalid1 = validator.validate(traversal1, null);
        assertFalse(rInvalid1.valid());
        assertTrue(rInvalid1.reason().contains("illegal directory traversal"));

        ChunkResponse traversal2 = new ChunkResponse("METHOD", "repo-1", "com.example", "Foo", "bar", "bar()",
                "../App.java", 1, 10, "code");
        LocationValidationResult rInvalid2 = validator.validate(traversal2, null);
        assertFalse(rInvalid2.valid());
        assertTrue(rInvalid2.reason().contains("illegal directory traversal"));
    }

    @Test
    @DisplayName("Invalid line numbers (zero start line or end preceding start) fail validation")
    void testInvalidLineCoordinates() {
        ChunkResponse zeroStart = new ChunkResponse("METHOD", "repo-1", "com.example", "Foo", "bar", "bar()",
                "src/Foo.java", 0, 10, "code");
        LocationValidationResult r1 = validator.validate(zeroStart, null);
        assertFalse(r1.valid());
        assertTrue(r1.reason().contains("startLine"));

        ChunkResponse invertedRange = new ChunkResponse("METHOD", "repo-1", "com.example", "Foo", "bar", "bar()",
                "src/Foo.java", 50, 20, "code");
        LocationValidationResult r2 = validator.validate(invertedRange, null);
        assertFalse(r2.valid());
        assertTrue(r2.reason().contains("endLine"));
    }

    @Test
    @DisplayName("Syntax passes gracefully when local repository root is not available on disk")
    void testSyntaxValidWhenRepositoryRootNotAvailable() {
        ChunkResponse validSyntax = new ChunkResponse("METHOD", "repo-1", "com.example", "Foo", "bar", "bar()",
                "src/Foo.java", 10, 25, "code");
        LocationValidationResult result = validator.validate(validSyntax, null);

        assertTrue(result.valid());
        assertFalse(result.existsOnDisk());
        assertTrue(result.linesValid());
    }

    @Test
    @DisplayName("Filesystem verification succeeds when file exists on disk and lines are within bounds")
    void testFilesystemVerification_fileExistsAndLinesValid(@TempDir Path tempRoot) throws IOException {
        Path sourceDir = tempRoot.resolve("src");
        Files.createDirectories(sourceDir);
        Path sourceFile = sourceDir.resolve("App.java");

        // Write a 30-line file
        StringBuilder content = new StringBuilder();
        for (int i = 1; i <= 30; i++) {
            content.append("// line ").append(i).append("\n");
        }
        Files.writeString(sourceFile, content.toString());

        ChunkResponse chunk = new ChunkResponse("METHOD", "repo-1", "com.example", "App", "main", "main()",
                "src/App.java", 5, 20, "code");

        LocationValidationResult result = validator.validate(chunk, tempRoot.toString());

        assertTrue(result.valid());
        assertTrue(result.existsOnDisk());
        assertTrue(result.linesValid());
        assertTrue(result.reason().contains("verified on disk"));
    }

    @Test
    @DisplayName("Filesystem verification exercises cleaned path on disk when temporary repo prefix is present")
    void testFilesystemVerification_temporaryRepoPrefixedPath(@TempDir Path tempRoot) throws IOException {
        Path sourceDir = tempRoot.resolve("src");
        Files.createDirectories(sourceDir);
        Path sourceFile = sourceDir.resolve("App.java");

        StringBuilder content = new StringBuilder();
        for (int i = 1; i <= 25; i++) {
            content.append("// line ").append(i).append("\n");
        }
        Files.writeString(sourceFile, content.toString());

        ChunkResponse chunk = new ChunkResponse("METHOD", "repo-1", "com.example", "App", "main", "main()",
                "Temp/astra-repo-98765/src/App.java", 5, 20, "code");

        LocationValidationResult result = validator.validate(chunk, tempRoot.toString());

        assertTrue(result.valid(), "Validator should resolve cleaned path on disk");
        assertTrue(result.existsOnDisk(), "File must exist on disk via cleaned relative path");
        assertTrue(result.linesValid(), "Line range must be within physical file bounds");
        assertTrue(result.reason().contains("verified on disk"));
    }

    @Test
    @DisplayName("Filesystem verification fails when file does not exist on disk")
    void testFilesystemVerification_fileNotFound(@TempDir Path tempRoot) {
        ChunkResponse chunk = new ChunkResponse("METHOD", "repo-1", "com.example", "App", "main", "main()",
                "src/NonExistent.java", 1, 10, "code");

        LocationValidationResult result = validator.validate(chunk, tempRoot.toString());

        assertFalse(result.valid());
        assertFalse(result.existsOnDisk());
        assertTrue(result.reason().contains("does not exist on disk"));
    }

    @Test
    @DisplayName("Filesystem verification fails when endLine exceeds total file lines")
    void testFilesystemVerification_endLineExceedsTotalLines(@TempDir Path tempRoot) throws IOException {
        Path sourceDir = tempRoot.resolve("src");
        Files.createDirectories(sourceDir);
        Path sourceFile = sourceDir.resolve("Small.java");

        // Write a 10-line file
        StringBuilder content = new StringBuilder();
        for (int i = 1; i <= 10; i++) {
            content.append("// line ").append(i).append("\n");
        }
        Files.writeString(sourceFile, content.toString());

        // Chunk specifies endLine 25 (exceeds 10)
        ChunkResponse chunk = new ChunkResponse("METHOD", "repo-1", "com.example", "Small", "run", "run()",
                "src/Small.java", 1, 25, "code");

        LocationValidationResult result = validator.validate(chunk, tempRoot.toString());

        assertFalse(result.valid());
        assertTrue(result.existsOnDisk());
        assertFalse(result.linesValid());
        assertTrue(result.reason().contains("exceeds total file lines"));
    }

    @Test
    @DisplayName("Catches UncheckedIOException during line counting and produces failed result rather than throwing")
    void testFilesystemVerification_uncheckedIOExceptionCaught(@TempDir Path tempRoot) throws IOException {
        Path sourceFile = tempRoot.resolve("invalid_encoding.java");
        // Write invalid UTF-8 byte sequence (lone continuation byte)
        Files.write(sourceFile, new byte[]{(byte) 0xC0, (byte) 0xAF});

        ChunkResponse chunk = new ChunkResponse("METHOD", "repo-1", "com.example", "App", "main", "main()",
                "invalid_encoding.java", 1, 10, "code");

        LocationValidationResult result = validator.validate(chunk, tempRoot.toString());

        assertFalse(result.valid());
        assertTrue(result.existsOnDisk());
        assertFalse(result.linesValid());
        assertTrue(result.reason().contains("Failed to read file lines:"));
    }

    @Test
    @DisplayName("validateAll validates list of chunks in batch")
    void testValidateAll() {
        ChunkResponse c1 = new ChunkResponse("PACKAGE", "repo-1", "com.example", "", "", "", "", 0, 0, "pkg");
        ChunkResponse c2 = new ChunkResponse("METHOD", "repo-1", "com.example", "Foo", "bar", "bar()", "src/Foo.java", 1, 10, "code");

        List<LocationValidationResult> results = validator.validateAll(List.of(c1, c2), null);

        assertNotNull(results);
        assertEquals(2, results.size());
        assertTrue(results.get(0).valid());
        assertTrue(results.get(1).valid());
    }

    @Test
    @DisplayName("Handles null and empty chunk lists safely")
    void testNullAndEmptySafe() {
        assertTrue(validator.validateAll(null, "/root").isEmpty());
        assertTrue(validator.validateAll(List.of(), "/root").isEmpty());
        LocationValidationResult nullResult = validator.validate(null, "/root");
        assertNotNull(nullResult);
        assertFalse(nullResult.valid());
    }
}

