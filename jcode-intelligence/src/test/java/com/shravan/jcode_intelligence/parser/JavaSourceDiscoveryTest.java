package com.shravan.jcode_intelligence.parser;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JavaSourceDiscoveryTest {

    private JavaSourceDiscovery discovery;

    @BeforeEach
    void setUp() {
        discovery = new JavaSourceDiscovery();
    }

    @Test
    @DisplayName("Included main and test sources are both discovered")
    void testIncludedMainAndTestSources(@TempDir Path tempDir) throws IOException {
        Path mainFile = tempDir.resolve("src/main/java/com/example/MainService.java");
        Files.createDirectories(mainFile.getParent());
        Files.writeString(mainFile, "package com.example; public class MainService {}");

        Path testFile = tempDir.resolve("src/test/java/com/example/MainServiceTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, "package com.example; public class MainServiceTest {}");

        List<Path> discovered = discovery.discover(tempDir);

        assertEquals(2, discovered.size(), "Both main and test Java source files should be discovered");
        assertTrue(discovered.contains(mainFile), "Discovered sources must contain main source file");
        assertTrue(discovered.contains(testFile), "Discovered sources must contain test source file");
    }

    @Test
    @DisplayName("All 10 excluded directories are filtered out")
    void testAllExcludedDirectories(@TempDir Path tempDir) throws IOException {
        // Valid file that must be discovered
        Path validFile = tempDir.resolve("src/main/java/com/example/Valid.java");
        Files.createDirectories(validFile.getParent());
        Files.writeString(validFile, "package com.example; public class Valid {}");

        // The 5 existing exclusions + 5 new exclusions:
        List<String> excludedSubPaths = List.of(
                "target/classes/Excluded1.java",
                "build/classes/Excluded2.java",
                ".git/hooks/Excluded3.java",
                "src/test/resources/fixtures/Excluded4.java",
                "test-resources/fixtures/Excluded5.java",
                "generated-sources/annotations/Excluded6.java",
                "generated-test-sources/annotations/Excluded7.java",
                "node_modules/pkg/Excluded8.java",
                ".gradle/caches/Excluded9.java",
                ".m2/repository/Excluded10.java"
        );

        for (String subPath : excludedSubPaths) {
            Path file = tempDir.resolve(subPath);
            Files.createDirectories(file.getParent());
            Files.writeString(file, "public class Excluded {}");
        }

        List<Path> discovered = discovery.discover(tempDir);

        assertEquals(1, discovered.size(), "Only the valid file should be discovered; all 10 excluded directories must be filtered");
        assertEquals(validFile, discovered.get(0));
    }

    @Test
    @DisplayName("Case-sensitive suffix behavior accepts only lowercase .java")
    void testCaseSensitiveSuffixBehavior(@TempDir Path tempDir) throws IOException {
        Path valid = tempDir.resolve("Valid.java");
        Files.writeString(valid, "public class Valid {}");

        Path upper = tempDir.resolve("Upper.JAVA");
        Files.writeString(upper, "public class Upper {}");

        Path mixed = tempDir.resolve("Mixed.Java");
        Files.writeString(mixed, "public class Mixed {}");

        Path incomplete = tempDir.resolve("Incomplete.jav");
        Files.writeString(incomplete, "public class Incomplete {}");

        Path text = tempDir.resolve("Readme.txt");
        Files.writeString(text, "Readme");

        List<Path> discovered = discovery.discover(tempDir);

        assertEquals(1, discovered.size(), "Only file with exact lowercase .java suffix must be discovered");
        assertEquals(valid, discovered.get(0));
    }

    @Test
    @DisplayName("Case-sensitive substring behavior does not exclude directories with different casing")
    void testCaseSensitiveSubstringBehavior(@TempDir Path tempDir) throws IOException {
        // Exclusions are case-sensitive ("/target/", "/build/", etc.)
        // Directories with different casing such as "/Target/" or "/BUILD/" must not be excluded
        Path upperTarget = tempDir.resolve("Target/classes/UpperTarget.java");
        Files.createDirectories(upperTarget.getParent());
        Files.writeString(upperTarget, "public class UpperTarget {}");

        Path upperBuild = tempDir.resolve("BUILD/output/UpperBuild.java");
        Files.createDirectories(upperBuild.getParent());
        Files.writeString(upperBuild, "public class UpperBuild {}");

        List<Path> discovered = discovery.discover(tempDir);

        assertEquals(2, discovered.size(), "Files under /Target/ and /BUILD/ should not be excluded by case-sensitive /target/ or /build/ checks");
        assertTrue(discovered.contains(upperTarget));
        assertTrue(discovered.contains(upperBuild));
    }

    @Test
    @DisplayName("Similar directory names are not falsely excluded")
    void testSimilarDirectoryNamesNotExcluded(@TempDir Path tempDir) throws IOException {
        List<String> similarPaths = List.of(
                "my-target/ServiceA.java",
                "target-sources/ServiceB.java",
                "build-tools/ServiceC.java",
                "un-generated-sources/ServiceD.java",
                "not_node_modules/ServiceE.java",
                "sub.gradle/ServiceF.java",
                "m2/ServiceG.java"
        );

        List<Path> createdPaths = new ArrayList<>();
        for (String rel : similarPaths) {
            Path file = tempDir.resolve(rel);
            Files.createDirectories(file.getParent());
            Files.writeString(file, "public class " + file.getFileName().toString().replace(".java", "") + " {}");
            createdPaths.add(file);
        }

        List<Path> discovered = discovery.discover(tempDir);

        assertEquals(similarPaths.size(), discovered.size(), "Files in directories with similar names must not be excluded");
        for (Path expected : createdPaths) {
            assertTrue(discovered.contains(expected), "Should discover: " + expected);
        }
    }

    @Test
    @DisplayName("Empty root returns empty list and returned list is immutable")
    void testEmptyResultAndImmutability(@TempDir Path tempDir) throws IOException {
        List<Path> discovered = discovery.discover(tempDir);

        assertNotNull(discovered);
        assertTrue(discovered.isEmpty(), "Empty directory should return empty list");
        assertThrows(UnsupportedOperationException.class, () -> discovered.add(Path.of("Dummy.java")),
                "Returned list must be immutable");
    }

    @Test
    @DisplayName("Discovered files are sorted in natural path order")
    void testSortedResults(@TempDir Path tempDir) throws IOException {
        Path zFile = tempDir.resolve("z/Z.java");
        Path aFile = tempDir.resolve("a/A.java");
        Path mFile = tempDir.resolve("m/M.java");
        Path bFile = tempDir.resolve("b/B.java");

        for (Path p : List.of(zFile, aFile, mFile, bFile)) {
            Files.createDirectories(p.getParent());
            Files.writeString(p, "public class Sample {}");
        }

        List<Path> discovered = discovery.discover(tempDir);

        assertEquals(4, discovered.size());
        List<Path> expectedSorted = new ArrayList<>(List.of(zFile, aFile, mFile, bFile));
        Collections.sort(expectedSorted);

        assertEquals(expectedSorted, discovered, "Discovered list must be sorted by path");
    }

    @Test
    @DisplayName("Missing root propagates NoSuchFileException or IOException")
    void testMissingRootPropagatesIOException(@TempDir Path tempDir) {
        Path missing = tempDir.resolve("non_existent_directory_12345");

        assertThrows(IOException.class, () -> discovery.discover(missing),
                "Missing root path must throw IOException or NoSuchFileException");
        assertThrows(NoSuchFileException.class, () -> discovery.discover(null),
                "Null root path must throw NoSuchFileException");
    }

    @Test
    @DisplayName("Symlinks are deduplicated by toRealPath, keeping first path in lexical order")
    void testSymlinkDeduplication(@TempDir Path tempDir) throws IOException {
        // Create a real file in "b_real/File.java"
        Path realDir = tempDir.resolve("b_real");
        Files.createDirectories(realDir);
        Path realFile = realDir.resolve("File.java");
        Files.writeString(realFile, "public class File {}");

        // Create a symlink in "a_link/File.java" pointing to realFile
        // In lexical order: "a_link/File.java" comes before "b_real/File.java"
        Path linkDir = tempDir.resolve("a_link");
        Files.createDirectories(linkDir);
        Path linkFile = linkDir.resolve("File.java");

        try {
            Files.createSymbolicLink(linkFile, realFile);
        } catch (UnsupportedOperationException | FileSystemException | SecurityException e) {
            Assumptions.assumeTrue(false, "Symlinks not supported on this environment: " + e.getMessage());
            return;
        } catch (IOException e) {
            Assumptions.assumeTrue(false, "Symlink creation failed: " + e.getMessage());
            return;
        }

        List<Path> discovered = discovery.discover(tempDir);

        // Deduplication should result in exactly 1 entry
        assertEquals(1, discovered.size(), "Symlink pointing to the same real file must be deduplicated");
        // Because "a_link/File.java" comes before "b_real/File.java" in lexical order, linkFile should be kept
        assertEquals(linkFile, discovered.get(0), "First path in lexical order must be retained");
    }
}
