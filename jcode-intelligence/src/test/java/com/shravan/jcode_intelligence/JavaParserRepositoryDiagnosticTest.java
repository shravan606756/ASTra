package com.shravan.jcode_intelligence;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.shravan.jcode_intelligence.config.ChunkingConfig;
import com.shravan.jcode_intelligence.config.JavaParserConfig;
import com.shravan.jcode_intelligence.converter.DocumentConverter;
import com.shravan.jcode_intelligence.model.CodeChunk;
import com.shravan.jcode_intelligence.parser.*;
import com.shravan.jcode_intelligence.parser.spi.ChunkBudgetEstimator;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class JavaParserRepositoryDiagnosticTest {

    @Test
    public void testPermanentFixOnGiantGeneratedClass() {
        System.out.println("=== VERIFYING PERMANENT ARCHITECTURAL FIX ON JAVAPARSER GENERATED CLASS ===");

        // 1. Setup components with budgets
        JavaParser javaParser = new JavaParserConfig().javaParser();
        MetadataExtractor extractor = new MetadataExtractor();
        ChunkingConfig config = new ChunkingConfig();
        ChunkBudgetEstimator budgetEstimator = new CharBasedBudgetEstimator(config);

        ClassSummaryBuilder summaryBuilder = new ClassSummaryBuilder(budgetEstimator);
        MethodFragmenter fragmenter = new MethodFragmenter(extractor, budgetEstimator);
        ChunkGenerator generator = new ChunkGenerator(extractor, summaryBuilder, fragmenter, budgetEstimator);
        PackageSummaryGenerator packageSummaryGenerator = new PackageSummaryGenerator();
        DocumentConverter converter = new DocumentConverter();
        EmbeddingBudgetValidator validator = new EmbeddingBudgetValidator(budgetEstimator);

        // 2. Build a synthetic giant class resembling GeneratedJavaParser (1,200 methods, 2,000-element array)
        StringBuilder code = new StringBuilder();
        code.append("package com.github.javaparser;\n\n");
        code.append("import com.github.javaparser.ast.expr.Expression;\n");
        code.append("import com.github.javaparser.ast.stmt.Statement;\n\n");
        code.append("public class GeneratedJavaParser {\n");
        code.append("    private int jj_kind;\n");
        code.append("    private int jj_la;\n");

        for (int i = 0; i < 1200; i++) {
            code.append(String.format("""
                /**
                 * Parses generated grammar rule %d.
                 */
                public final Statement parseRule_%d(final Expression exprParam, final String tokenVal) throws Exception {
                    if (jj_kind == %d) {
                        return null;
                    }
                    return null;
                }
                """, i, i, i));
        }

        code.append("    public static final int[] jj_la1_0 = new int[] {\n        ");
        for (int i = 0; i < 2000; i++) {
            code.append("0x").append(Integer.toHexString(i)).append(", ");
            if (i % 10 == 0) code.append("\n        ");
        }
        code.append("0x0\n    };\n");

        code.append("}\n");

        // 3. Parse AST and generate element chunks
        CompilationUnit cu = javaParser.parse(code.toString()).getResult().orElseThrow();
        ClassOrInterfaceDeclaration classDecl = cu.getClassByName("GeneratedJavaParser").orElseThrow();

        List<CodeChunk> elementChunks = generator.generateChunksForType(classDecl, cu);

        // 4. Generate package summaries
        List<CodeChunk> packageChunks = packageSummaryGenerator.generatePackageSummaries(elementChunks);

        List<CodeChunk> allChunks = new ArrayList<>(elementChunks);
        allChunks.addAll(packageChunks);

        // 5. Convert to Spring AI Documents
        List<Document> documents = converter.convert(allChunks);

        // 6. Pre-embedding validation (Must pass without throwing EmbeddingException)
        assertDoesNotThrow(() -> validator.validateAndAudit(documents, allChunks),
                "Pre-embedding validation MUST succeed for giant generated classes");

        // 7. Verify size bounds
        CodeChunk classSummaryChunk = allChunks.stream()
                .filter(c -> "CLASS".equals(c.getType()))
                .findFirst().orElseThrow();

        System.out.println("FIX VERIFICATION METRICS:");
        System.out.println("  Class Summary Content Size: " + classSummaryChunk.getContent().length() + " chars (~" + (classSummaryChunk.getContent().length() / 4) + " tokens)");
        assertTrue(classSummaryChunk.getContent().length() < config.getMaxDocumentChars(),
                "Class summary MUST fit well below maxDocumentChars (16,000 chars)");
        assertTrue(classSummaryChunk.getContent().contains("omitted from class summary"),
                "Class summary MUST contain semantic omission comment");

        CodeChunk fieldChunk = allChunks.stream()
                .filter(c -> "FIELD".equals(c.getType()) && "jj_la1_0".equals(c.getElementName()))
                .findFirst().orElseThrow();

        System.out.println("  Giant Field Content Size:   " + fieldChunk.getContent().length() + " chars (~" + (fieldChunk.getContent().length() / 4) + " tokens)");
        assertTrue(fieldChunk.getContent().length() <= config.getMaxFieldChars(),
                "Giant field chunk MUST be summarized below maxFieldChars (3,000 chars)");
        assertTrue(fieldChunk.getContent().contains("oversized field initializer omitted"),
                "Field chunk MUST contain semantic omission comment");
    }
}
