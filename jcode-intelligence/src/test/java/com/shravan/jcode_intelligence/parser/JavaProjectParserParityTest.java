package com.shravan.jcode_intelligence.parser;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.shravan.jcode_intelligence.config.ChunkingConfig;
import com.shravan.jcode_intelligence.config.JavaParserConfig;
import com.shravan.jcode_intelligence.model.CodeChunk;
import com.shravan.jcode_intelligence.parser.spi.ChunkBudgetEstimator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JavaProjectParserParityTest {

    private JavaProjectParser parser;
    private JavaSourceDiscovery discovery;

    @BeforeEach
    void setUp() {
        JavaParserConfig configFactory = new JavaParserConfig();
        JavaParser javaParser = configFactory.javaParser();

        MetadataExtractor metadataExtractor = new MetadataExtractor();
        ClassSummaryBuilder summaryBuilder = new ClassSummaryBuilder();
        ChunkingConfig chunkingConfig = new ChunkingConfig() {
            @Override
            public int getMaxMethodChars() { return 6000; }
            @Override
            public int getMaxFragmentChars() { return 3000; }
        };
        ChunkBudgetEstimator budgetEstimator = new CharBasedBudgetEstimator(chunkingConfig);

        MethodFragmenter methodFragmenter = new MethodFragmenter(metadataExtractor, budgetEstimator);
        ChunkGenerator chunkGenerator = new ChunkGenerator(
                metadataExtractor, summaryBuilder, methodFragmenter, budgetEstimator);
        AstVisitor astVisitor = new AstVisitor(chunkGenerator);

        discovery = new JavaSourceDiscovery();
        parser = new JavaProjectParser(javaParser, astVisitor, discovery);
    }

    @Test
    @DisplayName("Parser parity: root-based parse and discovered-list parse produce identical chunk IDs")
    void testParserParityRootBasedVsDiscoveredList(@TempDir Path tempDir) throws IOException {
        // Create sample project structure with multiple classes, methods, and fields
        Path serviceFile = tempDir.resolve("src/main/java/com/example/UserService.java");
        Files.createDirectories(serviceFile.getParent());
        Files.writeString(serviceFile, """
                package com.example;

                public class UserService {
                    private String serviceName;

                    public UserService(String name) {
                        this.serviceName = name;
                    }

                    public String getServiceName() {
                        return serviceName;
                    }

                    public void processUser(String userId) {
                        System.out.println("Processing: " + userId);
                    }
                }
                """);

        Path modelFile = tempDir.resolve("src/main/java/com/example/User.java");
        Files.createDirectories(modelFile.getParent());
        Files.writeString(modelFile, """
                package com.example;

                public class User {
                    private Long id;
                    private String email;

                    public Long getId() { return id; }
                    public String getEmail() { return email; }
                }
                """);

        Path testFile = tempDir.resolve("src/test/java/com/example/UserServiceTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, """
                package com.example;

                public class UserServiceTest {
                    public void testProcessUser() {
                        // test logic
                    }
                }
                """);

        // Excluded target file
        Path excludedFile = tempDir.resolve("target/classes/Excluded.java");
        Files.createDirectories(excludedFile.getParent());
        Files.writeString(excludedFile, "public class Excluded {}");

        // 1. Parse using root-based method
        List<CodeChunk> rootChunks = parser.parse(tempDir);
        List<String> rootChunkIds = rootChunks.stream().map(CodeChunk::getId).toList();

        // 2. Discover files first, then parse using list-based method
        List<Path> discoveredFiles = discovery.discover(tempDir);
        List<CodeChunk> listChunks = parser.parse(discoveredFiles);
        List<String> listChunkIds = listChunks.stream().map(CodeChunk::getId).toList();

        // Verify chunk ID parity
        assertFalse(rootChunkIds.isEmpty(), "Root-based parse must produce chunks");
        assertEquals(rootChunkIds, listChunkIds,
                "Root-based parsing and list-based parsing must yield identical chunk IDs in the same order");

        // Verify full chunk parity (type, className, chunkId)
        assertEquals(rootChunks.size(), listChunks.size());
        for (int i = 0; i < rootChunks.size(); i++) {
            CodeChunk c1 = rootChunks.get(i);
            CodeChunk c2 = listChunks.get(i);
            assertEquals(c1.getId(), c2.getId());
            assertEquals(c1.getClassName(), c2.getClassName());
            assertEquals(c1.getType(), c2.getType());
            assertEquals(c1.getContent(), c2.getContent());
        }

        // 3. Parity for parseCompilationUnits
        List<CompilationUnit> rootUnits = parser.parseCompilationUnits(tempDir);
        List<CompilationUnit> listUnits = parser.parseCompilationUnits(discoveredFiles);
        assertEquals(rootUnits.size(), listUnits.size(),
                "parseCompilationUnits(Path) and parseCompilationUnits(List<Path>) must produce equal unit counts");
    }

    @Test
    @DisplayName("Default discovery constructor operates identically")
    void testDefaultDiscoveryConstructor(@TempDir Path tempDir) throws IOException {
        JavaParserConfig configFactory = new JavaParserConfig();
        JavaParser javaParser = configFactory.javaParser();

        MetadataExtractor metadataExtractor = new MetadataExtractor();
        ClassSummaryBuilder summaryBuilder = new ClassSummaryBuilder();
        ChunkingConfig chunkingConfig = new ChunkingConfig();
        ChunkBudgetEstimator budgetEstimator = new CharBasedBudgetEstimator(chunkingConfig);
        MethodFragmenter methodFragmenter = new MethodFragmenter(metadataExtractor, budgetEstimator);
        ChunkGenerator chunkGenerator = new ChunkGenerator(
                metadataExtractor, summaryBuilder, methodFragmenter, budgetEstimator);
        AstVisitor astVisitor = new AstVisitor(chunkGenerator);

        // Uses the 2-arg constructor that retains default discovery
        JavaProjectParser defaultParser = new JavaProjectParser(javaParser, astVisitor);

        Path file = tempDir.resolve("src/main/java/DefaultTest.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "public class DefaultTest { public void hello() {} }");

        List<CodeChunk> chunks = defaultParser.parse(tempDir);
        assertFalse(chunks.isEmpty(), "Default discovery constructor must successfully discover and parse sources");
    }
}
