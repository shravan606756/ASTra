package com.shravan.jcode_intelligence.service.evaluation.citation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CitationParserTest {

    private CitationParser parser;

    @BeforeEach
    void setUp() {
        parser = new CitationParser();
    }

    @Test
    @DisplayName("Extract valid citation with symbol")
    void testExtractValidCitationWithSymbol() {
        String text = "The AST parsing is handled in [cite: src/main/java/Parser.java:15-30#parseDeclaration].";
        List<Citation> citations = parser.parseCitations(text);

        assertEquals(1, citations.size());
        Citation c = citations.get(0);
        assertTrue(c.validSyntax());
        assertEquals("[cite: src/main/java/Parser.java:15-30#parseDeclaration]", c.rawText());
        assertEquals("src/main/java/Parser.java", c.filePath());
        assertEquals(15, c.startLine());
        assertEquals(30, c.endLine());
        assertEquals("parseDeclaration", c.symbol());
        assertTrue(c.hasSymbol());
    }

    @Test
    @DisplayName("Extract valid symbol-free range citation")
    void testExtractSymbolFreeRangeCitation() {
        String text = "See implementation details at [cite: com/example/Foo.java:40-60].";
        List<Citation> citations = parser.parseCitations(text);

        assertEquals(1, citations.size());
        Citation c = citations.get(0);
        assertTrue(c.validSyntax());
        assertEquals("[cite: com/example/Foo.java:40-60]", c.rawText());
        assertEquals("com/example/Foo.java", c.filePath());
        assertEquals(40, c.startLine());
        assertEquals(60, c.endLine());
        assertNull(c.symbol());
        assertFalse(c.hasSymbol());
    }

    @Test
    @DisplayName("Retain malformed citation markers as invalid citations for denominator tracking")
    void testRetainMalformedMarkersAsInvalid() {
        String text = "Invalid markers: [cite: invalid], [cite: ], [cite: Foo.java], [cite: Foo.java:abc-def], and [cite: Foo.java:20-10].";
        List<Citation> citations = parser.parseCitations(text);

        assertEquals(5, citations.size());
        for (Citation c : citations) {
            assertFalse(c.validSyntax(), "Expected invalid syntax for marker: " + c.rawText());
            assertNotNull(c.rawText());
        }

        assertEquals("[cite: invalid]", citations.get(0).rawText());
        assertEquals("[cite: ]", citations.get(1).rawText());
        assertEquals("[cite: Foo.java]", citations.get(2).rawText());
        assertEquals("[cite: Foo.java:abc-def]", citations.get(3).rawText());
        assertEquals("[cite: Foo.java:20-10]", citations.get(4).rawText());
    }

    @Test
    @DisplayName("Extract multiple valid citations from a single sentence")
    void testMultipleCitationsInOneSentence() {
        String text = "Class A defines config [cite: A.java:5-12#Config] while B applies it [cite: B.java:25-35].";
        List<Citation> citations = parser.parseCitations(text);

        assertEquals(2, citations.size());

        Citation c1 = citations.get(0);
        assertTrue(c1.validSyntax());
        assertEquals("A.java", c1.filePath());
        assertEquals(5, c1.startLine());
        assertEquals(12, c1.endLine());
        assertEquals("Config", c1.symbol());

        Citation c2 = citations.get(1);
        assertTrue(c2.validSyntax());
        assertEquals("B.java", c2.filePath());
        assertEquals(25, c2.startLine());
        assertEquals(35, c2.endLine());
        assertNull(c2.symbol());
    }

    @Test
    @DisplayName("Normalize Windows backslashes in citation paths")
    void testNormalizeWindowsBackslashes() {
        String text = "Coordinate with Windows path: [cite: src\\main\\java\\Bar.java:1-10#init].";
        List<Citation> citations = parser.parseCitations(text);

        assertEquals(1, citations.size());
        Citation c = citations.get(0);
        assertTrue(c.validSyntax());
        assertEquals("src/main/java/Bar.java", c.filePath());
        assertEquals(1, c.startLine());
        assertEquals(10, c.endLine());
        assertEquals("init", c.symbol());
    }

    @Test
    @DisplayName("Empty, blank, or citation-free input returns empty list")
    void testEmptyAndNoCitations() {
        assertTrue(parser.parseCitations(null).isEmpty());
        assertTrue(parser.parseCitations("").isEmpty());
        assertTrue(parser.parseCitations("   ").isEmpty());
        assertTrue(parser.parseCitations("This text contains no citation brackets at all.").isEmpty());
    }
}
