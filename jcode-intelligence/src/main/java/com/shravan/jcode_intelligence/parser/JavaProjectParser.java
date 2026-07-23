package com.shravan.jcode_intelligence.parser;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.shravan.jcode_intelligence.model.CodeChunk;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Component
public class JavaProjectParser {

    private final AstVisitor visitor;

    public JavaProjectParser(AstVisitor visitor) {

        this.visitor = visitor;

        StaticJavaParser.getParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
    }

    public List<CompilationUnit> parseCompilationUnits(Path projectRoot) throws IOException {

        List<CompilationUnit> units = new ArrayList<>();

        Files.walk(projectRoot)
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".java"))
                .forEach(path -> {
                    try {
                        units.add(StaticJavaParser.parse(path));
                    } catch (Exception e) {
                        System.out.println("Failed to parse: " + path);
                        e.printStackTrace();
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
}