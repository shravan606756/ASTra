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
    }
}
