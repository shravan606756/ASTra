package com.shravan.jcode_intelligence.cli.ui;

import com.shravan.jcode_intelligence.dto.response.QueryBenchmarkResultDTO.RetrievedChunkDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BenchmarkTableFormatterTest {

    @Test
    @DisplayName("cleanRepoPath strips temporary repository directory prefixes and normalizes slashes")
    void testCleanRepoPathTempPrefixes() {
        String windowsTemp = "C:\\Users\\User\\AppData\\Local\\Temp\\astra-repo-12345\\javaparser-core\\src\\main\\java\\com\\github\\javaparser\\JavaParser.java";
        assertEquals("javaparser-core/src/main/java/com/github/javaparser/JavaParser.java",
                BenchmarkTableFormatter.cleanRepoPath(windowsTemp));

        String unixTemp = "/tmp/astra-repo-98765/src/main/java/com/example/App.java";
        assertEquals("src/main/java/com/example/App.java",
                BenchmarkTableFormatter.cleanRepoPath(unixTemp));

        String standardRel = "javaparser-core/src/main/java/com/github/javaparser/JavaParser.java";
        assertEquals(standardRel, BenchmarkTableFormatter.cleanRepoPath(standardRel));

        assertEquals("-", BenchmarkTableFormatter.cleanRepoPath(null));
        assertEquals("-", BenchmarkTableFormatter.cleanRepoPath("   "));
    }

    @Test
    @DisplayName("truncatePath preserves file name and immediate parent within length limit")
    void testTruncatePath() {
        String path = "javaparser-core/src/main/java/com/github/javaparser/ast/Node.java";
        String truncated = BenchmarkTableFormatter.truncatePath(path, 30);

        assertTrue(truncated.length() <= 30, "Truncated path must not exceed maxLen: " + truncated);
        assertTrue(truncated.startsWith(".../"), "Truncated path should start with ellipsis prefix: " + truncated);
        assertTrue(truncated.endsWith("Node.java"), "Truncated path must retain filename: " + truncated);

        // Short path should remain un-truncated
        String shortPath = "src/main/App.java";
        assertEquals(shortPath, BenchmarkTableFormatter.truncatePath(shortPath, 40));

        // Sub-3 and zero lengths
        assertEquals("", BenchmarkTableFormatter.truncatePath(shortPath, 0));
        assertEquals("sr", BenchmarkTableFormatter.truncatePath(shortPath, 2));
    }

    @Test
    @DisplayName("truncateSymbol truncates long symbols with ellipsis")
    void testTruncateSymbol() {
        String longSymbol = "veryLongMethodNameWithManyParametersAndGenerics";
        String truncated = BenchmarkTableFormatter.truncateSymbol(longSymbol, 15);

        assertEquals(15, truncated.length());
        assertTrue(truncated.endsWith("..."));

        assertEquals("shortMethod", BenchmarkTableFormatter.truncateSymbol("shortMethod", 20));
        assertEquals("-", BenchmarkTableFormatter.truncateSymbol(null, 20));
        assertEquals("-", BenchmarkTableFormatter.truncateSymbol("   ", 20));

        // Sub-3 and zero lengths
        assertEquals("", BenchmarkTableFormatter.truncateSymbol(longSymbol, 0));
        assertEquals("ve", BenchmarkTableFormatter.truncateSymbol(longSymbol, 2));
    }

    @Test
    @DisplayName("formatChunksTable renders appropriate columns with and without ground truth")
    void testFormatChunksTable() {
        RetrievedChunkDTO chunk1 = new RetrievedChunkDTO(
                "c1",
                "C:/Users/Temp/astra-repo-1/javaparser-core/src/main/java/com/github/javaparser/JavaParser.java",
                "JavaParser",
                "parse",
                "METHOD",
                1,
                true,
                3
        );

        List<RetrievedChunkDTO> chunks = List.of(chunk1);

        // With ground truth
        TableRenderer tableWithGt = BenchmarkTableFormatter.formatChunksTable(chunks, true);
        String renderedWithGt = tableWithGt.render();
        assertTrue(renderedWithGt.contains("Rank"));
        assertTrue(renderedWithGt.contains("File"));
        assertTrue(renderedWithGt.contains("Symbol"));
        assertTrue(renderedWithGt.contains("Type"));
        assertTrue(renderedWithGt.contains("Rel?"));
        assertTrue(renderedWithGt.contains("Grade"));
        assertTrue(renderedWithGt.contains("YES"));
        assertTrue(renderedWithGt.contains("3"));
        assertFalse(renderedWithGt.contains("astra-repo-1"), "Rendered table must not show temporary extraction directory");

        // Without ground truth
        RetrievedChunkDTO chunkNoGt = new RetrievedChunkDTO(
                "c2",
                "src/main/java/com/example/Service.java",
                "Service",
                "execute",
                "METHOD",
                1,
                null,
                null
        );
        TableRenderer tableWithoutGt = BenchmarkTableFormatter.formatChunksTable(List.of(chunkNoGt), false);
        String renderedWithoutGt = tableWithoutGt.render();
        assertTrue(renderedWithoutGt.contains("Rank"));
        assertTrue(renderedWithoutGt.contains("File"));
        assertFalse(renderedWithoutGt.contains("Rel?"));
        assertFalse(renderedWithoutGt.contains("Grade"));

        // Empty chunks
        TableRenderer emptyTable = BenchmarkTableFormatter.formatChunksTable(new ArrayList<>(), false);
        assertTrue(emptyTable.render().contains("No chunks retrieved"));

        // Chunks with null element in list
        List<RetrievedChunkDTO> chunksWithNull = new ArrayList<>();
        chunksWithNull.add(null);
        chunksWithNull.add(chunk1);
        TableRenderer tableWithNull = BenchmarkTableFormatter.formatChunksTable(chunksWithNull, true);
        assertNotNull(tableWithNull);
        assertTrue(tableWithNull.render().contains("JavaParser"));
    }

    @Test
    @DisplayName("truncatePath handles exact out-of-bounds boundary condition where fileName.length() == maxLen - 4")
    void testTruncatePathExactBoundaryCondition() {
        // Filename length 44: LargeRepoStreamingMemoryCalibrationTest.java
        String fileName44 = "LargeRepoStreamingMemoryCalibrationTest.java";
        assertEquals(44, fileName44.length());
        String fullPath = "javaparser-core/src/main/java/com/github/javaparser/ast/visitor/" + fileName44;

        // When maxLen = 48, fileName.length() == maxLen - 4 (44 == 48 - 4).
        // In the prior buggy implementation, this caused fileName.substring(44 - (48 - 3)) = fileName.substring(-1),
        // throwing StringIndexOutOfBoundsException: Range [-1, 44) out of bounds for length 44.
        assertDoesNotThrow(() -> {
            String result = BenchmarkTableFormatter.truncatePath(fullPath, 48);
            assertNotNull(result);
            assertTrue(result.length() <= 48, "Result length must be <= 48 but was " + result.length() + ": " + result);
            assertTrue(result.endsWith(fileName44), "Result must retain filename: " + result);
        });

        // Test boundary values around maxLen: 45 (maxLen - 3), 46 (maxLen - 2), 47 (maxLen - 1), 48 (maxLen), 49 (maxLen + 1)
        for (int maxLen = 44; maxLen <= 52; maxLen++) {
            final int len = maxLen;
            assertDoesNotThrow(() -> {
                String res = BenchmarkTableFormatter.truncatePath(fullPath, len);
                assertTrue(res.length() <= len, "Failed for maxLen " + len + ": length " + res.length() + " > " + len);
            });
        }
    }

    @Test
    @DisplayName("truncatePath handles paths without delimiters (single filename)")
    void testTruncatePathWithoutDelimiters() {
        String singleFile = "GenericVisitorWithDefaultsPrintVisitor.java"; // length 44
        String truncated = BenchmarkTableFormatter.truncatePath(singleFile, 20);
        assertEquals(20, truncated.length());
        assertTrue(truncated.startsWith("..."));

        assertEquals(singleFile, BenchmarkTableFormatter.truncatePath(singleFile, 50));
        assertEquals("", BenchmarkTableFormatter.truncatePath(singleFile, 0));
        assertEquals("Ge", BenchmarkTableFormatter.truncatePath(singleFile, 2));
        assertEquals("Gen", BenchmarkTableFormatter.truncatePath(singleFile, 3));
    }

    @Test
    @DisplayName("cleanRepoPath safely handles missing delimiters, empty slashes, and whitespace")
    void testCleanRepoPathEdgeCases() {
        assertEquals("-", BenchmarkTableFormatter.cleanRepoPath(""));
        assertEquals("-", BenchmarkTableFormatter.cleanRepoPath("   "));
        assertEquals("-", BenchmarkTableFormatter.cleanRepoPath("///"));
        assertEquals("-", BenchmarkTableFormatter.cleanRepoPath("\\\\\\"));
        assertEquals("file.txt", BenchmarkTableFormatter.cleanRepoPath("///file.txt"));
    }

    @Test
    @DisplayName("truncateSymbol handles small and edge case maxLen safely")
    void testTruncateSymbolEdgeCases() {
        assertEquals("", BenchmarkTableFormatter.truncateSymbol("testSymbol", 0));
        assertEquals("t", BenchmarkTableFormatter.truncateSymbol("testSymbol", 1));
        assertEquals("te", BenchmarkTableFormatter.truncateSymbol("testSymbol", 2));
        assertEquals("...", BenchmarkTableFormatter.truncateSymbol("testSymbol", 3));
        assertEquals("t...", BenchmarkTableFormatter.truncateSymbol("testSymbol", 4));
    }
}
