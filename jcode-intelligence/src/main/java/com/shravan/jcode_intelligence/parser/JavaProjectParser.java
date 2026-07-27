package com.shravan.jcode_intelligence.parser;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.shravan.jcode_intelligence.model.CodeChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Component
public class JavaProjectParser {

    private static final Logger log = LoggerFactory.getLogger(JavaProjectParser.class);

    private final JavaParser javaParser;
    private final AstVisitor visitor;

    public JavaProjectParser(JavaParser javaParser, AstVisitor visitor) {
        this.javaParser = javaParser;
        this.visitor = visitor;
    }

    public List<CompilationUnit> parseCompilationUnits(Path projectRoot) throws IOException {
        List<CompilationUnit> units = new ArrayList<>();

        Files.walk(projectRoot)
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".java"))
                .filter(this::isIndexableJavaFile)
                .forEach(path -> {
                    try {
                        ParseResult<CompilationUnit> result = javaParser.parse(path);
                        if (result.isSuccessful() && result.getResult().isPresent()) {
                            units.add(result.getResult().get());
                        } else {
                            log.error("Failed to parse file: {} | Problems: {}", path, result.getProblems());
                        }
                    } catch (Exception e) {
                        log.error("Exception parsing file: {}", path, e);
                    }
                });

        return units;
    }

    public List<CodeChunk> parse(Path projectRoot) throws IOException {
        List<CompilationUnit> units = parseCompilationUnits(projectRoot);
        List<CodeChunk> chunks = new ArrayList<>();

        for (CompilationUnit unit : units) {
            visitor.visit(unit, chunks);
        }

        return chunks;
    }

    /**
     * Determines whether a Java file should be included in AST semantic indexing.
     * Excludes build directories (target, build, .git) and non-source test fixture resources (src/test/resources).
     */
    private boolean isIndexableJavaFile(Path path) {
        String normalized = path.toString().replace('\\', '/');
        if (normalized.contains("/target/") || normalized.contains("/build/") || normalized.contains("/.git/")) {
            return false;
        }
        if (normalized.contains("/src/test/resources/") || normalized.contains("/test-resources/")) {
            log.debug("Skipping test fixture resource file: {}", path);
            return false;
        }
        return true;
    }
}