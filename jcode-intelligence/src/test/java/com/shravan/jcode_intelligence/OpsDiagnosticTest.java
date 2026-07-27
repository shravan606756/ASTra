package com.shravan.jcode_intelligence;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.shravan.jcode_intelligence.config.JavaParserConfig;
import com.shravan.jcode_intelligence.parser.ClassSummaryBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class OpsDiagnosticTest {

    @Test
    public void testSectionalMeasurementOfOpsClassSummary() {
        System.out.println("=== SECTIONAL MEASUREMENT OF OPS.JAVA CLASS SUMMARY ===");

        // Synthetic Ops.java with 15,000 char Javadoc comment
        StringBuilder sbCode = new StringBuilder();
        sbCode.append("package com.github.javaparser.issue_samples.issue_2627;\n\n");
        sbCode.append("import java.util.*;\n\n");
        
        sbCode.append("/**\n");
        sbCode.append(" * ").append("A".repeat(15000)).append("\n"); // 15,000 char Javadoc comment
        sbCode.append(" */\n");

        sbCode.append("public class Ops {\n");
        for (int i = 0; i < 50; i++) {
            sbCode.append("    private int op_field_").append(i).append(" = ").append(i).append(";\n");
        }
        for (int i = 0; i < 30; i++) {
            sbCode.append("    public void executeOp_").append(i).append("() {\n");
            sbCode.append("        System.out.println(").append(i).append(");\n");
            sbCode.append("    }\n");
        }
        sbCode.append("}\n");

        JavaParser parser = new JavaParserConfig().javaParser();
        CompilationUnit cu = parser.parse(sbCode.toString()).getResult().orElseThrow();
        ClassOrInterfaceDeclaration decl = cu.getClassByName("Ops").orElseThrow();

        ClassSummaryBuilder builder = new ClassSummaryBuilder();
        String summary = builder.buildSummary(decl, cu);

        System.out.println("SUMMARY CONTENT SIZE AFTER FIX: " + summary.length() + " chars (~" + (summary.length() / 4) + " tokens)");
        assertTrue(summary.length() < 16000, "Summary MUST be less than 16,000 chars");
        assertTrue(summary.contains("Javadoc truncated for summary"), "Summary MUST contain Javadoc truncation comment");
    }
}
