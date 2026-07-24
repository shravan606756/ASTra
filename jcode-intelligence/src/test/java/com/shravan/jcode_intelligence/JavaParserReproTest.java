package com.shravan.jcode_intelligence;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import org.junit.jupiter.api.Test;

public class JavaParserReproTest {

    private static final String CODE_WITH_PATTERN_INSTANCEOF = """
        package com.test;
        public class MetadataExtractorRepro {
            public void test(Object obj) {
                if (obj instanceof String s) {
                    System.out.println(s);
                }
            }
        }
        """;

    private static final String CODE_WITH_SWITCH_EXPR = """
        package com.test;
        public class SymbolExtractorRepro {
            public boolean test(String candidate) {
                return switch (candidate) {
                    case "How", "What" -> true;
                    default -> false;
                };
            }
        }
        """;

    @Test
    public void testParserConfigMutationOrder() {
        System.out.println("=== TEST A: Instantiate JavaParser then mutate config ===");
        ParserConfiguration config = new ParserConfiguration();
        config.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_11);
        JavaParser parser = new JavaParser(config);

        System.out.println("Before parsing, JavaParser language level: " + parser.getParserConfiguration().getLanguageLevel());
        ParseResult<CompilationUnit> resBefore = parser.parse(CODE_WITH_PATTERN_INSTANCEOF);
        System.out.println("Result with JAVA_11: isSuccessful=" + resBefore.isSuccessful());
        if (!resBefore.isSuccessful()) {
            System.out.println("Error msg JAVA_11: " + resBefore.getProblems().get(0).getMessage());
        }

        // Now mutate the config object that was passed to JavaParser
        config.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        System.out.println("After mutating config to JAVA_21, JavaParser language level: " + parser.getParserConfiguration().getLanguageLevel());
        ParseResult<CompilationUnit> resAfter = parser.parse(CODE_WITH_PATTERN_INSTANCEOF);
        System.out.println("Result after mutating config to JAVA_21: isSuccessful=" + resAfter.isSuccessful());
        if (!resAfter.isSuccessful()) {
            System.out.println("Error msg after mutate: " + resAfter.getProblems().get(0).getMessage());
        }
    }

    @Test
    public void testStaticJavaParserLifecycle() {
        System.out.println("=== TEST B: StaticJavaParser lifecycle ===");
        System.out.println("Initial StaticJavaParser language level: " + StaticJavaParser.getParserConfiguration().getLanguageLevel());

        // Parse with default/initial
        try {
            StaticJavaParser.parse(CODE_WITH_SWITCH_EXPR);
            System.out.println("StaticJavaParser parsed switch expr before mutation: SUCCESS");
        } catch (Exception e) {
            System.out.println("StaticJavaParser parsed switch expr before mutation: FAILED - " + e.getMessage());
        }

        // Mutate StaticJavaParser configuration
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        System.out.println("StaticJavaParser mutated to JAVA_17. Configured language level: " + StaticJavaParser.getParserConfiguration().getLanguageLevel());

        try {
            StaticJavaParser.parse(CODE_WITH_PATTERN_INSTANCEOF);
            System.out.println("StaticJavaParser parsed instanceof with JAVA_17: SUCCESS");
        } catch (Exception e) {
            System.out.println("StaticJavaParser parsed instanceof with JAVA_17: FAILED - " + e.getMessage());
        }
    }

    @Test
    public void testAllLanguageLevels() {
        System.out.println("=== TEST C: Testing all LanguageLevel enum values ===");
        for (ParserConfiguration.LanguageLevel level : ParserConfiguration.LanguageLevel.values()) {
            ParserConfiguration config = new ParserConfiguration();
            config.setLanguageLevel(level);
            JavaParser jp = new JavaParser(config);

            ParseResult<CompilationUnit> rInstanceof = jp.parse(CODE_WITH_PATTERN_INSTANCEOF);
            ParseResult<CompilationUnit> rSwitch = jp.parse(CODE_WITH_SWITCH_EXPR);

            System.out.println(String.format("Level %-15s | instanceof: %-5b | switch: %-5b",
                    level, rInstanceof.isSuccessful(), rSwitch.isSuccessful()));
            if (!rInstanceof.isSuccessful()) {
                System.out.println("   instanceof error: " + rInstanceof.getProblems().get(0).getMessage());
            }
            if (!rSwitch.isSuccessful()) {
                System.out.println("   switch error: " + rSwitch.getProblems().get(0).getMessage());
            }
        }
    }
}
