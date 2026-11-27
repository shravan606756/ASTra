package com.shravan.jcode_intelligence.sparse.analyzer;

import org.apache.lucene.analysis.Analyzer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JavaSourceAnalyzerTest {

    @Test
    @DisplayName("Index analyzer splits CamelCase, numbers, and preserves original terms")
    void testIndexAnalyzerCamelCase() {
        Analyzer analyzer = JavaSourceAnalyzer.createIndexAnalyzer();
        List<String> tokens = JavaSourceAnalyzer.extractTokens(analyzer, "content", "UserServiceImpl");

        assertTrue(tokens.contains("userserviceimpl"), "Should preserve original (lowercased)");
        assertTrue(tokens.contains("user"), "Should extract 'user'");
        assertTrue(tokens.contains("service"), "Should extract 'service'");
        assertTrue(tokens.contains("impl"), "Should extract 'impl'");
    }

    @Test
    @DisplayName("Index analyzer splits snake_case and alphanumeric tokens")
    void testIndexAnalyzerSnakeAndAlphanumeric() {
        Analyzer analyzer = JavaSourceAnalyzer.createIndexAnalyzer();
        List<String> tokens = JavaSourceAnalyzer.extractTokens(analyzer, "content", "calculate_total_v2");

        assertTrue(tokens.contains("calculate"), "Should extract 'calculate'");
        assertTrue(tokens.contains("total"), "Should extract 'total'");
        assertTrue(tokens.contains("v"), "Should extract 'v'");
        assertTrue(tokens.contains("2"), "Should extract '2'");
    }

    @Test
    @DisplayName("Search analyzer tokenizes query terms effectively")
    void testSearchAnalyzerQueryTerms() {
        Analyzer analyzer = JavaSourceAnalyzer.createSearchAnalyzer();
        List<String> tokens = JavaSourceAnalyzer.extractTokens(analyzer, "elementName", "findById");

        assertTrue(tokens.contains("findbyid"), "Should contain full lowercased token");
        assertTrue(tokens.contains("find"), "Should extract 'find'");
        assertTrue(tokens.contains("by"), "Should extract 'by'");
        assertTrue(tokens.contains("id"), "Should extract 'id'");
    }

    @Test
    @DisplayName("Per-field analyzer preserves exact tokens for identity fields")
    void testPerFieldAnalyzerExactFields() {
        Analyzer analyzer = JavaSourceAnalyzer.createPerFieldIndexAnalyzer();

        List<String> idTokens = JavaSourceAnalyzer.extractTokens(analyzer, "id", "repo-1:com.example.Foo::bar");
        assertEquals(1, idTokens.size(), "Exact ID field should not be split or lowercased");
        assertEquals("repo-1:com.example.Foo::bar", idTokens.get(0));

        List<String> repoTokens = JavaSourceAnalyzer.extractTokens(analyzer, "repositoryId", "MyRepo-v1");
        assertEquals(1, repoTokens.size());
        assertEquals("MyRepo-v1", repoTokens.get(0));
    }
}
