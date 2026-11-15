package com.shravan.jcode_intelligence.service.classification;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class LocAnalyzerTest {

    private final LocAnalyzer analyzer = new LocAnalyzer();

    @Test
    public void testCountNonBlankLinesWithVariousLineTypes(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("Sample.java");
        String content = """
                package com.example;
                
                // Single-line comment
                /* Multi-line
                   comment */
                
                   \t   
                public class Sample {
                
                    private int x = 1;
                
                    public void test() {
                        System.out.println("hello");
                    }
                }
                """;
        Files.writeString(file, content, StandardCharsets.UTF_8);

        // Lines analysis:
        // 1: package com.example; (1)
        // 2: "" (blank - 0)
        // 3: // Single-line comment (1)
        // 4: /* Multi-line (1)
        // 5:    comment */ (1)
        // 6: "" (blank - 0)
        // 7: "   \t   " (whitespace - 0)
        // 8: public class Sample { (1)
        // 9: "" (blank - 0)
        // 10:     private int x = 1; (1)
        // 11: "" (blank - 0)
        // 12:     public void test() { (1)
        // 13:         System.out.println("hello"); (1)
        // 14:     } (1)
        // 15: } (1)
        // 16: "" (blank trailing newline - 0)
        // Total non-blank: 10 lines

        long loc = analyzer.countNonBlankLines(file);
        assertEquals(10, loc);

        LocAnalysis analysis = analyzer.analyze(tempDir);
        assertEquals(10, analysis.totalLoc());
        assertEquals(1, analysis.eligibleFiles().size());
        assertEquals(file, analysis.eligibleFiles().get(0));
    }

    @Test
    public void testEmptyFileReturnsZero(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("Empty.java");
        Files.writeString(file, "", StandardCharsets.UTF_8);

        assertEquals(0, analyzer.countNonBlankLines(file));
        LocAnalysis analysis = analyzer.analyze(tempDir);
        assertEquals(0, analysis.totalLoc());
        assertEquals(1, analysis.eligibleFiles().size());
    }

    @Test
    public void testWhitespaceOnlyFileReturnsZero(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("Whitespace.java");
        Files.writeString(file, "   \n\t\t\n  \r\n \n", StandardCharsets.UTF_8);

        assertEquals(0, analyzer.countNonBlankLines(file));
        LocAnalysis analysis = analyzer.analyze(tempDir);
        assertEquals(0, analysis.totalLoc());
        assertEquals(1, analysis.eligibleFiles().size());
    }

    @Test
    public void testCrlfLineEndings(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("Crlf.java");
        String content = "public class A {\r\n\r\n    int a = 1;\r\n}\r\n";
        Files.writeString(file, content, StandardCharsets.UTF_8);

        // 3 non-blank lines: declaration, field, closing brace
        assertEquals(3, analyzer.countNonBlankLines(file));
    }

    @Test
    public void testMissingFinalNewline(@TempDir Path tempDir) throws IOException {
        Path fileWithNewline = tempDir.resolve("WithNewline.java");
        Path fileWithoutNewline = tempDir.resolve("WithoutNewline.java");

        Files.writeString(fileWithNewline, "class A {\n}\n", StandardCharsets.UTF_8);
        Files.writeString(fileWithoutNewline, "class A {\n}", StandardCharsets.UTF_8);

        assertEquals(2, analyzer.countNonBlankLines(fileWithNewline));
        assertEquals(2, analyzer.countNonBlankLines(fileWithoutNewline));
    }

    @Test
    public void testMalformedUtf8IsReplacedAndCounted(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("Malformed.java");
        // Write invalid UTF-8 byte sequence (0xFF, 0xFE are invalid UTF-8 leading bytes)
        byte[] bytes = new byte[]{
                'c', 'l', 'a', 's', 's', ' ', 'A', ' ', '{', '\n',
                (byte) 0xFF, (byte) 0xFE, ' ', '/', '/', ' ', 'b', 'a', 'd', '\n',
                '}', '\n'
        };
        Files.write(file, bytes);

        // Should not throw MalformedInputException, should replace and count 3 lines
        long loc = analyzer.countNonBlankLines(file);
        assertEquals(3, loc);
    }

    @Test
    public void testExclusionsAreNotCounted(@TempDir Path tempDir) throws IOException {
        // Included file
        Path src = tempDir.resolve("src").resolve("main").resolve("java").resolve("App.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "public class App {}\n");

        // Excluded files in various directories
        String[] excludedDirs = {
                "target", "build", ".git", "src/test/resources", "test-resources",
                "generated-sources", "generated-test-sources", "node_modules", ".gradle", ".m2"
        };
        for (String dir : excludedDirs) {
            Path excludedFile = tempDir.resolve(dir).resolve("Excluded.java");
            Files.createDirectories(excludedFile.getParent());
            Files.writeString(excludedFile, "public class Excluded {}\n");
        }

        // Similar directory that is NOT excluded
        Path validDir = tempDir.resolve("my-target").resolve("Included.java");
        Files.createDirectories(validDir.getParent());
        Files.writeString(validDir, "public class Included {}\n");

        LocAnalysis analysis = analyzer.analyze(tempDir);
        // Only App.java and Included.java should be discovered and counted
        assertEquals(2, analysis.eligibleFiles().size());
        assertEquals(2, analysis.totalLoc());
    }

    @Test
    public void testSymlinkDeduplication(@TempDir Path tempDir) throws IOException {
        Path realFile = tempDir.resolve("Real.java");
        Files.writeString(realFile, "public class Real {\n    int x;\n}\n");

        Path symlinkFile = tempDir.resolve("Symlink.java");
        boolean symlinkCreated = false;
        try {
            Files.createSymbolicLink(symlinkFile, realFile);
            symlinkCreated = true;
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            // OS / permissions might not support symlink creation without admin
        }
        Assumptions.assumeTrue(symlinkCreated, "Symlinks must be supported to run symlink deduplication test");

        LocAnalysis analysis = analyzer.analyze(tempDir);
        assertEquals(1, analysis.eligibleFiles().size(), "Symlinked file should be deduplicated");
        assertEquals(3, analysis.totalLoc());
    }

    @Test
    public void testMissingRootThrowsNoSuchFileException(@TempDir Path tempDir) {
        Path missing = tempDir.resolve("does_not_exist");
        assertThrows(NoSuchFileException.class, () -> analyzer.analyze(missing));
    }

    @Test
    public void testEligibleFilesListIsImmutable(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("A.java");
        Files.writeString(file, "class A {}\n");

        LocAnalysis analysis = analyzer.analyze(tempDir);
        assertThrows(UnsupportedOperationException.class, () -> analysis.eligibleFiles().add(file));
    }
}
