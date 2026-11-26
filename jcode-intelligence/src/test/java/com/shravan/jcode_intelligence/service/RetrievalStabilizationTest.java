package com.shravan.jcode_intelligence.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.shravan.jcode_intelligence.config.IntentRetrievalConfig;
import com.shravan.jcode_intelligence.model.QueryIntent;
import com.shravan.jcode_intelligence.model.RetrievalStrategy;
import com.shravan.jcode_intelligence.model.benchmark.RelevantChunkDefinition;
import com.shravan.jcode_intelligence.parser.MetadataExtractor;
import com.shravan.jcode_intelligence.sparse.fusion.ReciprocalRankFusion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.lang.reflect.Method;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ASTra HYBRID Retrieval Stabilization Tests")
class RetrievalStabilizationTest {

    private TestJdbcTemplate jdbcTemplate;
    private TestVectorStore vectorStore;
    private IntentRetrievalConfig config;
    private SymbolExtractor symbolExtractor;
    private ArchitectureContextBuilder archBuilder;
    private RetrievalStrategySelector selector;
    private RetrievalEvaluator evaluator;

    @BeforeEach
    void setUp() {
        jdbcTemplate = new TestJdbcTemplate();
        vectorStore = new TestVectorStore();
        config = new IntentRetrievalConfig() {
            @Override public int getFinalTopK(QueryIntent intent) { return 10; }
            @Override public int getRawTopK(QueryIntent intent) { return 16; }
            @Override public double getSimilarityThreshold() { return 0.65; }
        };
        symbolExtractor = new SymbolExtractor();
        archBuilder = new ArchitectureContextBuilder(jdbcTemplate);
        selector = new RetrievalStrategySelector(
                vectorStore,
                jdbcTemplate,
                symbolExtractor,
                config,
                archBuilder,
                new ObjectMapper()
        );
        evaluator = new RetrievalEvaluator();
    }

    // ─────────────────────────────────────────────────────────────
    // 1. Q02 Candidate Explosion Prevention
    // ─────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("Q02 Candidate Explosion & Bounded Class Lookup")
    class CandidateExplosionTests {

        @Test
        @DisplayName("lookupClassBySymbol restricts to type declaration chunks (CLASS, INTERFACE, RECORD, ENUM)")
        void testLookupClassBySymbol_restrictsType() {
            selector.lookupClassBySymbol("Node", "repo-jp");

            assertEquals(1, jdbcTemplate.executedQueries.size());
            String sql = jdbcTemplate.executedQueries.get(0);

            assertTrue(sql.contains("metadata->>'type' IN ('CLASS', 'INTERFACE', 'RECORD', 'ENUM')"),
                    "SQL must restrict type to declarations to prevent matching member chunks: " + sql);
            assertTrue(sql.contains("metadata->>'className' = ? OR metadata->>'elementName' = ?"),
                    "SQL must match className or elementName: " + sql);
            assertTrue(sql.contains("metadata->>'repositoryId' = ?"),
                    "SQL must enforce repository isolation: " + sql);

            Object[] args = jdbcTemplate.executedArgs.get(0);
            assertArrayEquals(new Object[]{"Node", "Node", "repo-jp"}, args);
        }

        @Test
        @DisplayName("lookupByMetadata guards className with type constraint to prevent member chunk leak")
        void testLookupByMetadata_guardsClassName() throws Exception {
            Method method = RetrievalStrategySelector.class.getDeclaredMethod("lookupByMetadata", String.class, String.class);
            method.setAccessible(true);
            method.invoke(selector, "Node", "repo-jp");

            assertEquals(1, jdbcTemplate.executedQueries.size());
            String sql = jdbcTemplate.executedQueries.get(0);

            assertTrue(sql.contains("metadata->>'className' = ? AND metadata->>'type' IN ('CLASS', 'INTERFACE', 'RECORD', 'ENUM')"),
                    "lookupByMetadata must guard className with type constraint: " + sql);
            assertTrue(sql.contains("metadata->>'repositoryId' = ?"),
                    "SQL must filter by repositoryId: " + sql);

            Object[] args = jdbcTemplate.executedArgs.get(0);
            assertArrayEquals(new Object[]{"Node", "Node", "Node", "Node#%", "repo-jp"}, args);
        }

        @Test
        @DisplayName("retrieveForClass produces strictly bounded candidates (no 206 explosion)")
        void testRetrieveForClass_boundedCandidateCount() {
            // Setup DB mock to return 1 class chunk and 1 package summary chunk
            Map<String, Object> classMeta = new HashMap<>();
            classMeta.put("type", "CLASS");
            classMeta.put("className", "Node");
            classMeta.put("elementName", "Node");
            classMeta.put("packageName", "com.github.javaparser.ast");
            classMeta.put("chunkId", "NODE_CLASS");
            jdbcTemplate.classLookupResponses.add(new Document("public abstract class Node", classMeta));

            Map<String, Object> pkgMeta = new HashMap<>();
            pkgMeta.put("type", "PACKAGE");
            pkgMeta.put("packageName", "com.github.javaparser.ast");
            pkgMeta.put("chunkId", "PKG_AST");
            jdbcTemplate.packageLookupResponses.add(new Document("package com.github.javaparser.ast;", pkgMeta));

            // Setup vector store to return rawTopK = 16 candidates with distinct file/line coordinates
            List<Document> vectorDocs = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                Map<String, Object> meta = new HashMap<>();
                meta.put("type", "METHOD");
                meta.put("chunkId", "VEC_" + i);
                meta.put("elementName", "vecMethod" + i);
                meta.put("filePath", "src/test/VectorFile" + i + ".java");
                meta.put("startLine", i * 10 + 1);
                meta.put("endLine", i * 10 + 9);
                vectorDocs.add(new Document("vector content " + i, meta));
            }
            vectorStore.defaultSearchResults = vectorDocs;

            List<Document> retrieved = selector.retrieve(
                    "Explain Node class responsibilities",
                    QueryIntent.CLASS,
                    "repo-jp",
                    RetrievalStrategy.HYBRID
            );

            // Candidate count must be bounded: 1 class + 1 package + 16 vector docs = 18 candidates
            // (Previously 189 member chunks + 1 package + 16 vector = 206 candidates!)
            assertNotNull(retrieved);
            assertTrue(retrieved.size() <= 20, "Candidate count must be strictly bounded, actual: " + retrieved.size());
            assertEquals(18, retrieved.size(), "Expected exactly 18 candidates (1 class + 1 pkg + 16 vector)");

            // First document must be the exact class chunk
            assertEquals("NODE_CLASS", retrieved.get(0).getMetadata().get("chunkId"));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 2. Repository Filtering Isolation
    // ─────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("Repository Filtering & Multi-tenant Isolation")
    class RepositoryFilteringTests {

        @Test
        @DisplayName("All lookup methods enforce repositoryId parameter when present")
        void testLookupsWithRepositoryId() throws Exception {
            String repoId = "tenant-repo-123";

            selector.lookupClassBySymbol("Foo", repoId);
            String sqlClass = jdbcTemplate.executedQueries.get(jdbcTemplate.executedQueries.size() - 1);
            assertTrue(sqlClass.contains("metadata->>'repositoryId' = ?"));

            Method lookupMethod = RetrievalStrategySelector.class.getDeclaredMethod("lookupMethodBySymbol", String.class, String.class);
            lookupMethod.setAccessible(true);
            lookupMethod.invoke(selector, "myMethod", repoId);
            String sqlMethod = jdbcTemplate.executedQueries.get(jdbcTemplate.executedQueries.size() - 1);
            assertTrue(sqlMethod.contains("metadata->>'repositoryId' = ?"));

            Method lookupType = RetrievalStrategySelector.class.getDeclaredMethod("lookupByType", String.class, String.class);
            lookupType.setAccessible(true);
            lookupType.invoke(selector, "INTERFACE", repoId);
            String sqlType = jdbcTemplate.executedQueries.get(jdbcTemplate.executedQueries.size() - 1);
            assertTrue(sqlType.contains("metadata->>'repositoryId' = ?"));

            Method lookupPkg = RetrievalStrategySelector.class.getDeclaredMethod("lookupByPackageName", String.class, String.class);
            lookupPkg.setAccessible(true);
            lookupPkg.invoke(selector, "com.example", repoId);
            String sqlPkg = jdbcTemplate.executedQueries.get(jdbcTemplate.executedQueries.size() - 1);
            assertTrue(sqlPkg.contains("metadata->>'repositoryId' = ?"));
        }

        @Test
        @DisplayName("Lookup methods omit repositoryId filter when repositoryId is null or blank")
        void testLookupsWithoutRepositoryId() throws Exception {
            selector.lookupClassBySymbol("Foo", null);
            String sqlClass = jdbcTemplate.executedQueries.get(jdbcTemplate.executedQueries.size() - 1);
            assertFalse(sqlClass.contains("metadata->>'repositoryId' = ?"), "Unscoped class lookup must omit repo filter");

            Method lookupMethod = RetrievalStrategySelector.class.getDeclaredMethod("lookupMethodBySymbol", String.class, String.class);
            lookupMethod.setAccessible(true);
            lookupMethod.invoke(selector, "myMethod", "   ");
            String sqlMethod = jdbcTemplate.executedQueries.get(jdbcTemplate.executedQueries.size() - 1);
            assertFalse(sqlMethod.contains("metadata->>'repositoryId' = ?"), "Blank repo lookup must omit repo filter");
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 3. Reciprocal Rank Fusion & Ordering
    // ─────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("RRF Fusion & Ranking Stability")
    class RrfFusionTests {

        @Test
        @DisplayName("RRF fuses dense and sparse rankings with reciprocal rank formula")
        void testRrfFusionFormula() {
            ReciprocalRankFusion rrf = new ReciprocalRankFusion();

            Map<String, Object> metaA = new HashMap<>(Map.of("chunkId", "CHUNK_A", "type", "METHOD"));
            Map<String, Object> metaB = new HashMap<>(Map.of("chunkId", "CHUNK_B", "type", "METHOD"));
            Map<String, Object> metaC = new HashMap<>(Map.of("chunkId", "CHUNK_C", "type", "METHOD"));

            Document docA = new Document("Chunk A", metaA);
            Document docB = new Document("Chunk B", metaB);
            Document docC = new Document("Chunk C", metaC);

            // Dense ranking: A (rank 1), B (rank 2)
            List<Document> denseDocs = List.of(docA, docB);
            // Sparse ranking: B (rank 1), C (rank 2)
            List<Document> sparseDocs = List.of(docB, docC);

            List<Document> fused = rrf.fuse(denseDocs, sparseDocs, QueryIntent.METHOD, 5);

            assertNotNull(fused);
            assertEquals(3, fused.size());

            // CHUNK_B appears in both lists:
            // RRF(B) = w_dense/(60+2) + w_sparse/(60+1)
            // While A is only in dense (60+1), C is only in sparse (60+2).
            // Thus CHUNK_B must rank first (#1) due to dual-retrieval consensus!
            assertEquals("CHUNK_B", fused.get(0).getMetadata().get("chunkId"),
                    "Dual-retrieved document CHUNK_B must achieve rank 1 in RRF");
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 4. Line Handling & Chunk Lifecycle
    // ─────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("Line Handling & Evaluation Match Diagnostics")
    class LineHandlingTests {

        @Test
        @DisplayName("MetadataExtractor extracts positive 1-based ordered line numbers from AST")
        void testMetadataExtractorLineNumbers() {
            String source = """
                    package com.example;
                    
                    public class Calculator {
                        public int add(int a, int b) {
                            return a + b;
                        }
                    }
                    """;
            CompilationUnit cu = StaticJavaParser.parse(source);
            ClassOrInterfaceDeclaration classDecl = cu.getClassByName("Calculator").orElseThrow();
            MethodDeclaration methodDecl = classDecl.getMethodsByName("add").get(0);

            MetadataExtractor extractor = new MetadataExtractor();
            int classStart = extractor.getStartLine(classDecl);
            int classEnd = extractor.getEndLine(classDecl);
            int methodStart = extractor.getStartLine(methodDecl);
            int methodEnd = extractor.getEndLine(methodDecl);

            assertTrue(classStart >= 1, "Class start line must be >= 1");
            assertTrue(classEnd >= classStart, "Class end line must be >= start line");
            assertTrue(methodStart >= classStart, "Method start line must be inside class");
            assertTrue(methodEnd <= classEnd, "Method end line must be inside class");
        }

        @Test
        @DisplayName("Evaluator line-range check confirms root cause of Q11-Q16 version drift rejection")
        void testEvaluatorLineMismatchRootCause() throws Exception {
            // Ground truth for Q11 in default-benchmark.json: lines 335-355
            RelevantChunkDefinition gt = new RelevantChunkDefinition();
            gt.setFilePath("javaparser-core/src/main/java/com/github/javaparser/ast/Node.java");
            gt.setSymbol("findFirst");
            gt.setStartLine(335);
            gt.setEndLine(355);

            // Document extracted from javaparser-parent-3.28.2: lines 992-1004
            Map<String, Object> docMeta = new HashMap<>();
            docMeta.put("filePath", "javaparser-core/src/main/java/com/github/javaparser/ast/Node.java");
            docMeta.put("elementName", "findFirst");
            docMeta.put("methodName", "findFirst");
            docMeta.put("startLine", 992);
            docMeta.put("endLine", 1004);
            Document extractedDoc = new Document("public <T extends Node> Optional<T> findFirst(...)", docMeta);

            Method matchesMethod = RetrievalEvaluator.class.getDeclaredMethod("matches", Document.class, RelevantChunkDefinition.class);
            matchesMethod.setAccessible(true);

            boolean matchResult = (boolean) matchesMethod.invoke(evaluator, extractedDoc, gt);

            // Because docStart (992) > gtEndLine (355), the strict line guard rejects the match!
            assertFalse(matchResult,
                    "Evaluator correctly rejects match because docStart(992) > gtEndLine(355) due to repository version drift");
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Test Doubles (JdbcTemplate and VectorStore)
    // ─────────────────────────────────────────────────────────────
    private static class TestJdbcTemplate extends JdbcTemplate {
        final List<String> executedQueries = new ArrayList<>();
        final List<Object[]> executedArgs = new ArrayList<>();
        final List<Document> classLookupResponses = new ArrayList<>();
        final List<Document> packageLookupResponses = new ArrayList<>();

        @Override
        @SuppressWarnings("unchecked")
        public <T> List<T> query(String sql, Object[] args, RowMapper<T> rowMapper) {
            executedQueries.add(sql);
            executedArgs.add(args != null ? args.clone() : new Object[0]);

            List<T> results = new ArrayList<>();
            if (sql.contains("metadata->>'type' IN ('CLASS', 'INTERFACE', 'RECORD', 'ENUM')")) {
                results.addAll((List<T>) classLookupResponses);
            } else if (sql.contains("metadata->>'type' = 'PACKAGE'")) {
                results.addAll((List<T>) packageLookupResponses);
            }
            return results;
        }
    }

    private static class TestVectorStore implements VectorStore {
        List<Document> defaultSearchResults = new ArrayList<>();

        @Override public void add(List<Document> documents) {}
        @Override public void delete(List<String> idList) {}
        @Override public void delete(Filter.Expression filterExpression) {}
        @Override public List<Document> similaritySearch(String query) { return defaultSearchResults; }
        @Override public List<Document> similaritySearch(SearchRequest request) { return defaultSearchResults; }
    }
}
