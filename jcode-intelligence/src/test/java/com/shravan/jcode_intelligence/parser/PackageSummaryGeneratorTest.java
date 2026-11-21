package com.shravan.jcode_intelligence.parser;

import com.shravan.jcode_intelligence.model.ChunkType;
import com.shravan.jcode_intelligence.model.CodeChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PackageSummaryGeneratorTest {

    private final PackageSummaryGenerator generator = new PackageSummaryGenerator();

    @Test
    @DisplayName("Accumulator retains nonblank packages and produces summary with qualifying depth-zero types")
    void testAccumulatorBasic() {
        PackageSummaryGenerator.Accumulator acc = generator.createAccumulator();

        CodeChunk classChunk = createChunk("com.example.service", "CLASS", "UserService", 0);
        CodeChunk methodChunk = createChunk("com.example.service", "METHOD", "findUser", 1);
        CodeChunk interfaceChunk = createChunk("com.example.service", "INTERFACE", "UserRepository", 0);

        acc.accumulate(List.of(classChunk, methodChunk, interfaceChunk));

        List<CodeChunk> summaries = acc.generateSummaries();
        assertEquals(1, summaries.size());

        CodeChunk summary = summaries.get(0);
        assertEquals("package:com.example.service::PACKAGE", summary.getId());
        assertEquals("PACKAGE", summary.getType());
        assertEquals("com.example.service", summary.getPackageName());
        assertEquals("com.example.service", summary.getElementName());
        assertEquals("", summary.getClassName());
        assertEquals("", summary.getFilePath());
        assertEquals(1, summary.getStartLine());
        assertEquals(1, summary.getEndLine());
        assertEquals("Java", summary.getLanguage());
        assertTrue(summary.isSummarized());
        assertFalse(summary.isFragmented());

        String content = summary.getContent();
        assertTrue(content.contains("Package: com.example.service"));
        assertTrue(content.contains("Total 2 top-level types"));
        assertTrue(content.contains("- CLASS UserService"));
        assertTrue(content.contains("- INTERFACE UserRepository"));
        assertFalse(content.contains("findUser"));
        assertTrue(content.contains("CLASS: 1"));
        assertTrue(content.contains("INTERFACE: 1"));
    }

    @Test
    @DisplayName("Packages with no qualifying depth-zero types are still retained")
    void testPackageWithNoQualifyingTypes() {
        PackageSummaryGenerator.Accumulator acc = generator.createAccumulator();

        // Only method chunks and inner classes (depth > 0)
        CodeChunk methodChunk = createChunk("com.example.empty", "METHOD", "doSomething", 1);
        CodeChunk innerClass = createChunk("com.example.empty", "CLASS", "InnerHelper", 1);

        acc.accumulate(List.of(methodChunk, innerClass));

        List<CodeChunk> summaries = acc.generateSummaries();
        assertEquals(1, summaries.size());

        CodeChunk summary = summaries.get(0);
        assertEquals("package:com.example.empty::PACKAGE", summary.getId());
        assertTrue(summary.getContent().contains("Total 0 top-level types"));
    }

    @Test
    @DisplayName("Blank or null package names are ignored")
    void testBlankPackagesIgnored() {
        PackageSummaryGenerator.Accumulator acc = generator.createAccumulator();

        CodeChunk nullPkg = createChunk(null, "CLASS", "A", 0);
        CodeChunk blankPkg = createChunk("   ", "CLASS", "B", 0);

        acc.accumulate(List.of(nullPkg, blankPkg));

        List<CodeChunk> summaries = acc.generateSummaries();
        assertTrue(summaries.isEmpty());
    }

    @Test
    @DisplayName("First 100 type entries are retained in encounter order and excess types are omitted with comment")
    void testFirst100TypesLimit() {
        PackageSummaryGenerator.Accumulator acc = generator.createAccumulator();

        List<CodeChunk> chunks = new ArrayList<>();
        for (int i = 1; i <= 120; i++) {
            chunks.add(createChunk("com.example.huge", "CLASS", "Type" + i, 0));
        }

        acc.accumulate(chunks);

        List<CodeChunk> summaries = acc.generateSummaries();
        assertEquals(1, summaries.size());

        CodeChunk summary = summaries.get(0);
        String content = summary.getContent();

        assertTrue(content.contains("Total 120 top-level types"));
        assertTrue(content.contains("- CLASS Type1\n"));
        assertTrue(content.contains("- CLASS Type100\n"));
        assertFalse(content.contains("- CLASS Type101\n"));
        assertTrue(content.contains("// ... 20 additional top-level types omitted from package summary"));
        assertTrue(content.contains("CLASS: 120"));
    }

    @Test
    @DisplayName("generatePackageSummaries(List) produces identical output to Accumulator")
    void testParityBetweenGenerateListAndAccumulator() {
        List<CodeChunk> chunks = List.of(
                createChunk("com.shravan.test", "CLASS", "App", 0),
                createChunk("com.shravan.test", "ENUM", "Status", 0),
                createChunk("com.shravan.test", "RECORD", "Point", 0),
                createChunk("com.shravan.other", "INTERFACE", "Runner", 0)
        );

        List<CodeChunk> listSummaries = generator.generatePackageSummaries(chunks);

        PackageSummaryGenerator.Accumulator acc = generator.createAccumulator();
        acc.accumulate(chunks);
        List<CodeChunk> accSummaries = acc.generateSummaries();

        assertEquals(listSummaries.size(), accSummaries.size());
        for (int i = 0; i < listSummaries.size(); i++) {
            CodeChunk c1 = listSummaries.get(i);
            CodeChunk c2 = accSummaries.get(i);
            assertEquals(c1.getId(), c2.getId());
            assertEquals(c1.getType(), c2.getType());
            assertEquals(c1.getPackageName(), c2.getPackageName());
            assertEquals(c1.getContent(), c2.getContent());
        }
    }

    private CodeChunk createChunk(String pkg, String type, String name, int depth) {
        CodeChunk chunk = new CodeChunk();
        chunk.setId("chunk-" + name);
        chunk.setPackageName(pkg);
        chunk.setType(type);
        chunk.setElementName(name);
        chunk.setNestingDepth(depth);
        return chunk;
    }
}
