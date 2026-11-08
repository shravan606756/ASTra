package com.shravan.jcode_intelligence.llm;

import com.shravan.jcode_intelligence.dto.request.ChatMode;
import com.shravan.jcode_intelligence.model.QueryIntent;
import com.shravan.jcode_intelligence.service.ArchitectureAnalyzer;
import com.shravan.jcode_intelligence.service.ArchitectureContextBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.lang.reflect.Field;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class PromptBuilderTest {

    private PromptRouter router;
    private PromptTemplateLoader templateLoader;
    private ArchitectureContextBuilder archBuilder;
    private ArchitectureAnalyzer archAnalyzer;
    private PromptBuilder promptBuilder;

    @BeforeEach
    void setUp() {
        router = new PromptRouter();
        templateLoader = new PromptTemplateLoader(new DefaultResourceLoader());
        archBuilder = new ArchitectureContextBuilder(new StubJdbcTemplate());
        archAnalyzer = new ArchitectureAnalyzer();
        promptBuilder = new PromptBuilder(router, templateLoader, archBuilder, archAnalyzer);
    }

    @Test
    @DisplayName("Within budget: all candidate documents are supplied and omitted list is empty")
    void testBuildPromptContext_withinBudget() {
        Map<String, Object> meta1 = Map.of("type", "CLASS", "className", "ServiceA", "filePath", "src/ServiceA.java");
        Map<String, Object> meta2 = Map.of("type", "METHOD", "elementName", "doSomething", "filePath", "src/ServiceA.java");

        Document doc1 = new Document("class ServiceA {}", meta1);
        Document doc2 = new Document("void doSomething() {}", meta2);

        PromptContextResult result = promptBuilder.buildPromptContext("Explain ServiceA", List.of(doc1, doc2), "repo-1", QueryIntent.CLASS);

        assertNotNull(result);
        assertNotNull(result.promptText());
        assertFalse(result.promptText().isBlank());
        assertEquals(2, result.suppliedDocuments().size());
        assertTrue(result.omittedDocuments().isEmpty());
    }

    @Test
    @DisplayName("Exceeds max document count: truncates at limit and accurately populates omitted documents")
    void testBuildPromptContext_exceedsMaxDocuments() {
        List<Document> docs = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            Map<String, Object> meta = Map.of("type", "METHOD", "elementName", "m" + i, "chunkId", "CHUNK_" + i);
            docs.add(new Document("void m" + i + "() {}", meta));
        }

        PromptContextResult result = promptBuilder.buildPromptContext("query", docs, "repo-1", QueryIntent.METHOD);

        assertEquals(20, result.suppliedDocuments().size(), "Must cap at default maxContextDocuments (20)");
        assertEquals(5, result.omittedDocuments().size(), "Remaining 5 documents must be in omitted list");
        assertEquals("CHUNK_20", result.omittedDocuments().get(0).getMetadata().get("chunkId"));
        assertEquals("CHUNK_24", result.omittedDocuments().get(4).getMetadata().get("chunkId"));
    }

    @Test
    @DisplayName("Exceeds character size budget: truncates at budget threshold and tracks omitted documents")
    void testBuildPromptContext_exceedsSizeBudget() throws Exception {
        // Set a small budget using reflection for precise boundary verification
        Field maxContextSizeField = PromptBuilder.class.getDeclaredField("maxContextSize");
        maxContextSizeField.setAccessible(true);
        maxContextSizeField.setInt(promptBuilder, 1000);

        Field reservedField = PromptBuilder.class.getDeclaredField("reservedResponseSize");
        reservedField.setAccessible(true);
        reservedField.setInt(promptBuilder, 200);
        // Available budget = 800 chars

        // Doc text length = 200 chars + 150 header = 350 chars per doc.
        // 2 docs = 700 chars (< 800). 3rd doc would exceed 800 (1050 > 800).
        String payload = "A".repeat(200);

        Document d1 = new Document(payload, Map.of("type", "METHOD", "chunkId", "D1"));
        Document d2 = new Document(payload, Map.of("type", "METHOD", "chunkId", "D2"));
        Document d3 = new Document(payload, Map.of("type", "METHOD", "chunkId", "D3"));

        PromptContextResult result = promptBuilder.buildPromptContext("query", List.of(d1, d2, d3), "repo-1", QueryIntent.METHOD);

        assertEquals(2, result.suppliedDocuments().size());
        assertEquals(1, result.omittedDocuments().size());
        assertEquals("D3", result.omittedDocuments().get(0).getMetadata().get("chunkId"));
    }

    @Test
    @DisplayName("Architecture mode: drops FIELD and METHOD_FRAGMENT chunks from supplied context and marks them omitted")
    void testBuildPromptContext_architectureModeExcludesFieldAndMethodFragments() {
        Map<String, Object> pkgMeta = Map.of("type", "PACKAGE", "packageName", "com.example", "chunkId", "PKG");
        Map<String, Object> classMeta = Map.of("type", "CLASS", "className", "AppService", "chunkId", "CLS");
        Map<String, Object> ifaceMeta = Map.of("type", "INTERFACE", "className", "AppInterface", "chunkId", "IFACE");
        Map<String, Object> fieldMeta = Map.of("type", "FIELD", "elementName", "logger", "chunkId", "FLD");
        Map<String, Object> fragMeta = Map.of("type", "METHOD_FRAGMENT", "elementName", "loopFrag", "chunkId", "FRAG");

        Document pkgDoc = new Document("package com.example summary", pkgMeta);
        Document clsDoc = new Document("class AppService {}", classMeta);
        Document ifaceDoc = new Document("interface AppInterface {}", ifaceMeta);
        Document fieldDoc = new Document("private static final Logger log;", fieldMeta);
        Document fragDoc = new Document("for (int i=0; i<10; i++) {}", fragMeta);

        List<Document> candidates = List.of(pkgDoc, clsDoc, ifaceDoc, fieldDoc, fragDoc);

        PromptContextResult result = promptBuilder.buildPromptContext("Explain architecture", candidates, "repo-1", QueryIntent.ARCHITECTURE);

        List<String> suppliedIds = result.suppliedDocuments().stream()
                .map(d -> String.valueOf(d.getMetadata().get("chunkId")))
                .toList();

        List<String> omittedIds = result.omittedDocuments().stream()
                .map(d -> String.valueOf(d.getMetadata().get("chunkId")))
                .toList();

        // FIELD and METHOD_FRAGMENT must NOT be in supplied context
        assertFalse(suppliedIds.contains("FLD"), "FIELD chunks must be excluded from architecture supplied context");
        assertFalse(suppliedIds.contains("FRAG"), "METHOD_FRAGMENT chunks must be excluded from architecture supplied context");

        // FIELD and METHOD_FRAGMENT must be recorded as omitted
        assertTrue(omittedIds.contains("FLD"), "FIELD chunk must be present in omitted documents");
        assertTrue(omittedIds.contains("FRAG"), "METHOD_FRAGMENT chunk must be present in omitted documents");
    }

    @Test
    @DisplayName("Legacy buildPrompt overloads delegate to buildPromptContext seamlessly")
    void testLegacyBuildPromptDelegation() {
        Document doc = new Document("class Foo {}", Map.of("type", "CLASS", "className", "Foo"));

        String legacyText = promptBuilder.buildPrompt("query", List.of(doc));
        PromptContextResult contextResult = promptBuilder.buildPromptContext("query", List.of(doc));

        assertNotNull(legacyText);
        assertEquals(contextResult.promptText(), legacyText);
    }

    private static class StubJdbcTemplate extends JdbcTemplate {
        @Override
        @SuppressWarnings("unchecked")
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            if (requiredType == Integer.class) {
                return (T) Integer.valueOf(1);
            }
            return null;
        }

        @Override
        public <T> List<T> query(String sql, Object[] args, RowMapper<T> rowMapper) {
            return List.of();
        }
    }
}

