package com.shravan.jcode_intelligence;

import com.shravan.jcode_intelligence.dto.request.ChatMode;
import com.shravan.jcode_intelligence.llm.PromptBuilder;
import com.shravan.jcode_intelligence.llm.PromptRouter;
import com.shravan.jcode_intelligence.llm.PromptTemplateLoader;
import com.shravan.jcode_intelligence.service.SymbolExtractor;
import com.shravan.jcode_intelligence.service.impl.RetrievalServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class RetrievalAndPromptQualityTest {

    private SymbolExtractor symbolExtractor;

    @BeforeEach
    void setUp() {
        symbolExtractor = new SymbolExtractor();
    }

    @Test
    void testSymbolExtractorExtractsCamelCaseMethodNames() {
        Optional<String> doFragment = symbolExtractor.extract("Explain doFragment");
        assertTrue(doFragment.isPresent(), "Should extract standalone camelCase method name 'doFragment'");
        assertEquals("doFragment", doFragment.get());

        Optional<String> doFragmentParens = symbolExtractor.extract("Explain doFragment()");
        assertTrue(doFragmentParens.isPresent(), "Should extract method with parens 'doFragment'");
        assertEquals("doFragment", doFragmentParens.get());

        Optional<String> classSymbol = symbolExtractor.extract("Explain MethodFragmenter");
        assertTrue(classSymbol.isPresent(), "Should extract class symbol 'MethodFragmenter'");
        assertEquals("MethodFragmenter", classSymbol.get());
    }

    @Test
    void testPromptBuilderIncludesRelationshipHeadersInContext() {
        PromptRouter router = new PromptRouter();
        PromptTemplateLoader templateLoader = new PromptTemplateLoader(new DefaultResourceLoader());
        PromptBuilder promptBuilder = new PromptBuilder(router, templateLoader, null, null);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("filePath", "src/main/java/MethodFragmenter.java");
        metadata.put("startLine", 1);
        metadata.put("endLine", 50);
        metadata.put("type", "CLASS");
        metadata.put("packageName", "com.shravan.jcode_intelligence.parser");
        metadata.put("className", "MethodFragmenter");
        metadata.put("elementName", "MethodFragmenter");
        metadata.put("superClass", "Object");
        metadata.put("interfaces", List.of("CodeFragmenter"));
        metadata.put("relationships", Map.of("implements", List.of("CodeFragmenter")));

        Document doc = new Document("public class MethodFragmenter implements CodeFragmenter {}", metadata);

        String prompt = promptBuilder.buildPrompt("Explain MethodFragmenter", List.of(doc), "test-repo", ChatMode.EXPLAIN_CLASS);

        assertNotNull(prompt);
        assertTrue(prompt.contains("SuperClass: Object"), "Prompt context should contain SuperClass header");
        assertTrue(prompt.contains("Interfaces: [CodeFragmenter]"), "Prompt context should contain Interfaces header");
        assertTrue(prompt.contains("Relationships: {implements=[CodeFragmenter]}"), "Prompt context should contain Relationships header");
        assertTrue(prompt.contains("MethodFragmenter implements CodeFragmenter"), "Prompt should contain code content");
    }

    private RetrievalServiceImpl createTestRetrievalService(MockVectorStore mockVectorStore, MockJdbcTemplate mockJdbcTemplate) {
        com.shravan.jcode_intelligence.config.IntentRetrievalConfig config = new com.shravan.jcode_intelligence.config.IntentRetrievalConfig() {
            @Override public int getFinalTopK(com.shravan.jcode_intelligence.model.QueryIntent intent) { return 10; }
            @Override public int getRawTopK(com.shravan.jcode_intelligence.model.QueryIntent intent) { return 20; }
            @Override public double getSimilarityThreshold() { return 0.65; }
        };
        com.shravan.jcode_intelligence.service.ArchitectureContextBuilder archBuilder = 
            new com.shravan.jcode_intelligence.service.ArchitectureContextBuilder(mockJdbcTemplate);
        com.shravan.jcode_intelligence.service.RetrievalStrategySelector selector = 
            new com.shravan.jcode_intelligence.service.RetrievalStrategySelector(
                mockVectorStore, mockJdbcTemplate, symbolExtractor, config, archBuilder);
        com.shravan.jcode_intelligence.service.RetrievalReranker reranker = 
            new com.shravan.jcode_intelligence.service.RetrievalReranker(config);
        return new RetrievalServiceImpl(null, selector, reranker, symbolExtractor, config);
    }

    @Test
    void testRetrievalArchitectureModeQueriesPackageSummariesAndCoreClasses() {
        MockVectorStore mockVectorStore = new MockVectorStore();
        MockJdbcTemplate mockJdbcTemplate = new MockJdbcTemplate();

        RetrievalServiceImpl retrievalService = createTestRetrievalService(mockVectorStore, mockJdbcTemplate);

        List<Document> docs = retrievalService.retrieve("Explain the indexing architecture", 10, "test-repo", ChatMode.ARCHITECTURE);

        assertNotNull(docs);
        assertTrue(mockJdbcTemplate.executedQueries.stream()
                .anyMatch(q -> q.contains("metadata->>'type' = 'PACKAGE'")),
                "ARCHITECTURE mode must query for PACKAGE summaries");
        assertTrue(mockJdbcTemplate.executedQueries.stream()
                .anyMatch(q -> q.contains("metadata->>'type' IN ('CLASS', 'ENUM', 'RECORD')")),
                "ARCHITECTURE mode must query for classes");
    }

    @Test
    void testRetrievalExplainMethodQueriesMethodNameAndFragments() {
        MockVectorStore mockVectorStore = new MockVectorStore();
        MockJdbcTemplate mockJdbcTemplate = new MockJdbcTemplate();

        RetrievalServiceImpl retrievalService = createTestRetrievalService(mockVectorStore, mockJdbcTemplate);

        List<Document> docs = retrievalService.retrieve("Explain doFragment", 5, "test-repo", ChatMode.EXPLAIN_METHOD);

        assertNotNull(docs);
        assertTrue(mockJdbcTemplate.executedQueries.stream()
                .anyMatch(q -> q.contains("metadata->>'methodName' = ?") || q.contains("metadata->>'elementName' LIKE ?")),
                "EXPLAIN_METHOD mode must query metadata->>'methodName' and fragment elementName pattern");
    }

    // ── Mocks for testing SQL queries ─────────────────────────

    private static class MockVectorStore implements VectorStore {
        @Override
        public void add(List<Document> documents) {}

        @Override
        public void delete(List<String> idList) {}

        @Override
        public void delete(Filter.Expression filterExpression) {}

        @Override
        public List<Document> similaritySearch(String query) { return List.of(); }

        @Override
        public List<Document> similaritySearch(SearchRequest request) { return List.of(); }
    }

    private static class MockJdbcTemplate extends JdbcTemplate {
        final List<String> executedQueries = new ArrayList<>();

        @Override
        @SuppressWarnings("unchecked")
        public <T> List<T> query(String sql, Object[] args, RowMapper<T> rowMapper) {
            executedQueries.add(sql + " | Args: " + Arrays.toString(args));
            return List.of();
        }

        @Override
        public void query(String sql, Object[] args, org.springframework.jdbc.core.RowCallbackHandler rch) {
            executedQueries.add(sql + " | Args: " + Arrays.toString(args));
        }
    }
}
