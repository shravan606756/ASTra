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
    @DisplayName("Filter excludes directories that are the first component of a relative path")
    void testRelativePathFirstComponentExclusions(@TempDir Path tempDir) throws IOException {
        Path targetDir = tempDir.resolve("target");
        Files.createDirectories(targetDir);
        Path targetFile = targetDir.resolve("MyClass.java");
        Files.writeString(targetFile, "public class MyClass {}");

        Path genDir = tempDir.resolve("generated-sources");
        Files.createDirectories(genDir);
        Path genFile = genDir.resolve("Generated.java");
        Files.writeString(genFile, "public class Generated {}");

        assertFalse(filter.test(targetFile), "target directory at start of relative path must be excluded");
        assertFalse(filter.test(genFile), "generated-sources directory at start of relative path must be excluded");
    }

    @Test
    @DisplayName("Filter preserves full component matching for similar directory names")
    void testSimilarDirectoryNamesNotExcluded(@TempDir Path tempDir) throws IOException {
        Path targetsDir = tempDir.resolve("targets");
        Files.createDirectories(targetsDir);
        Path targetsFile = targetsDir.resolve("TargetsClass.java");
        Files.writeString(targetsFile, "public class TargetsClass {}");

        assertTrue(filter.test(targetsFile), "targets directory should not be excluded by /target/");
    }
}
