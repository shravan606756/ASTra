package com.shravan.jcode_intelligence.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SymbolExtractor Regression Tests")
class SymbolExtractorTest {

    private SymbolExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new SymbolExtractor();
    }

    @Test
    @DisplayName("Q10: Extracts method with parens even when class is prefixed")
    void testQ10MethodWithParens() {
        Optional<String> symbol = extractor.extract("How does JavaParser.parse() work?");
        assertTrue(symbol.isPresent());
        assertEquals("parse", symbol.get(), "Method with parens 'parse()' must be extracted instead of 'JavaParser'");
    }

    @Test
    @DisplayName("Q11: Extracts camelCase method findFirst")
    void testQ11CamelCaseMethod() {
        Optional<String> symbol = extractor.extract("Find where findFirst method is defined");
        assertTrue(symbol.isPresent());
        assertEquals("findFirst", symbol.get());
    }

    @Test
    @DisplayName("Q12: Extracts camelCase method getParentNode")
    void testQ12CamelCaseMethod() {
        Optional<String> symbol = extractor.extract("Explain getParentNode implementation");
        assertTrue(symbol.isPresent());
        assertEquals("getParentNode", symbol.get());
    }

    @Test
    @DisplayName("Q13: Extracts method with parens 'remove()' over enclosing class 'Node'")
    void testQ13RemoveInNode() {
        Optional<String> symbol = extractor.extract("Explain remove() method in Node");
        assertTrue(symbol.isPresent());
        assertEquals("remove", symbol.get(), "Must extract 'remove' rather than enclosing class 'Node'");
    }

    @Test
    @DisplayName("Q14: Extracts method with parens 'replace()' over enclosing class 'Node'")
    void testQ14ReplaceInNode() {
        Optional<String> symbol = extractor.extract("How does replace() work in Node?");
        assertTrue(symbol.isPresent());
        assertEquals("replace", symbol.get(), "Must extract 'replace' rather than enclosing class 'Node'");
    }

    @Test
    @DisplayName("Q15: Extracts method with parens 'clone()' over enclosing class 'Node'")
    void testQ15CloneInNode() {
        Optional<String> symbol = extractor.extract("Explain clone() implementation on Node");
        assertTrue(symbol.isPresent());
        assertEquals("clone", symbol.get(), "Must extract 'clone' rather than enclosing class 'Node'");
    }

    @Test
    @DisplayName("Q16: Extracts method with parens 'walk()' over enclosing class 'Node'")
    void testQ16WalkInNode() {
        Optional<String> symbol = extractor.extract("Explain walk() method in Node");
        assertTrue(symbol.isPresent());
        assertEquals("walk", symbol.get(), "Must extract 'walk' rather than enclosing class 'Node'");
    }

    @Test
    @DisplayName("Q02: Extracts class 'Node' when query targets class responsibilities")
    void testQ02NodeClass() {
        Optional<String> symbol = extractor.extract("Explain Node class responsibilities");
        assertTrue(symbol.isPresent());
        assertEquals("Node", symbol.get(), "Must extract class 'Node'");
    }

    @ParameterizedTest(name = "Query: {0} -> Expected symbol: {1}")
    @CsvSource({
            "'Where is LexicalPreservingPrinter implemented?', LexicalPreservingPrinter",
            "'What is the purpose of AstObserver?', AstObserver",
            "'Explain CompilationUnit structure', CompilationUnit",
            "'How does doFragment split large methods?', doFragment",
            "'Trace indexProject execution', indexProject"
    })
    @DisplayName("Parameterized test for standard class and method queries")
    void testStandardQueries(String query, String expectedSymbol) {
        Optional<String> symbol = extractor.extract(query);
        assertTrue(symbol.isPresent(), "Expected symbol for query: " + query);
        assertEquals(expectedSymbol, symbol.get());
    }

    @Test
    @DisplayName("Common English stop words are ignored")
    void testStopWordsIgnored() {
        assertTrue(extractor.extract("How does this work?").isEmpty());
        assertTrue(extractor.extract("What should be done?").isEmpty());
        assertTrue(extractor.extract("Where can we find it?").isEmpty());
        assertTrue(extractor.extract("").isEmpty());
        assertTrue(extractor.extract(null).isEmpty());
    }
}
