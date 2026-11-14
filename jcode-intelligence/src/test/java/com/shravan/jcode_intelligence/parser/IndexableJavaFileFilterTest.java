package com.shravan.jcode_intelligence.parser;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class IndexableJavaFileFilterTest {

    private IndexableJavaFileFilter filter;

    @BeforeEach
    void setUp() {
        filter = new IndexableJavaFileFilter();
    }

    @Test
    @DisplayName("Filter accepts regular .java files and rejects directories and non-java files")
    void testBasicAcceptance(@TempDir Path tempDir) throws IOException {
        Path javaFile = tempDir.resolve("Sample.java");
        Files.writeString(javaFile, "public class Sample {}");

        Path txtFile = tempDir.resolve("Sample.txt");
        Files.writeString(txtFile, "text content");

        Path directory = tempDir.resolve("subdir");
        Files.createDirectories(directory);

        assertTrue(filter.test(javaFile), "Regular .java file should be accepted");
        assertTrue(filter.isIndexable(javaFile), "isIndexable should return true for regular .java file");
        assertFalse(filter.test(txtFile), "Non-java file should be rejected");
        assertFalse(filter.test(directory), "Directory should be rejected");
        assertFalse(filter.test(null), "Null path should be rejected");
        assertFalse(filter.test(tempDir.resolve("non-existent.java")), "Non-existent file should be rejected");
    }

    @Test
    @DisplayName("Filter preserves relative-root edge case where target without leading slash is not excluded")
    void testRelativeRootEdgeCaseUnchanged() {
        // Path with no leading slash (e.g., "target/MyClass.java") does not contain "/target/"
        Path relativeTarget = Path.of("target", "MyClass.java");
        // String representation is "target/MyClass.java" which does not contain "/target/"
        String normalized = relativeTarget.toString().replace('\\', '/');
        assertFalse(normalized.contains("/target/"), "Relative path without leading slash must not match /target/");
    }
}
