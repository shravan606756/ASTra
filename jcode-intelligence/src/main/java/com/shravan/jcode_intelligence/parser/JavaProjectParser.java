package com.shravan.jcode_intelligence.parser;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.shravan.jcode_intelligence.model.CodeChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Component
public class JavaProjectParser {

    private static final Logger log = LoggerFactory.getLogger(JavaProjectParser.class);

    private final JavaParser javaParser;
    private final AstVisitor visitor;
    private final JavaSourceDiscovery discovery;

    public JavaProjectParser(JavaParser javaParser, AstVisitor visitor) {
        this(javaParser, visitor, new JavaSourceDiscovery());
    }

    @Autowired
    public JavaProjectParser(JavaParser javaParser, AstVisitor visitor, JavaSourceDiscovery discovery) {
        this.javaParser = javaParser;
        this.visitor = visitor;
        this.discovery = discovery != null ? discovery : new JavaSourceDiscovery();
    }

    public List<CompilationUnit> parseCompilationUnits(Path projectRoot) throws IOException {
        List<Path> files = discovery.discover(projectRoot);
        return parseCompilationUnits(files);
    }

    public List<CompilationUnit> parseCompilationUnits(List<Path> files) {
        List<CompilationUnit> units = new ArrayList<>();
        if (files == null) {
            return units;
        }

        for (Path path : files) {
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
        }

        return units;
    }

    /**
     * Discovers and parses all Java source files under the given project root into {@link CodeChunk}s.
     *
     * @param projectRoot directory root of the project
     * @return list of parsed CodeChunks across all successfully visited files
     * @throws IOException if source discovery or directory scanning fails
     */
    public List<CodeChunk> parse(Path projectRoot) throws IOException {
        List<Path> files = discovery.discover(projectRoot);
        return parse(files);
    }

    /**
     * Parses the specified list of Java source files into {@link CodeChunk}s with per-file error isolation.
     *
     * <p><strong>Error Isolation Guarantees:</strong>
     * Each file's AST is visited into an isolated temporary chunk list. Chunks are added to the returned
     * list only if parsing and AST visitation for that file complete successfully without exceptions.
     * A parse or visitor failure in an individual file logs an error and skips that file entirely without
     * leaving partial or corrupt chunks in the result.
     *
     * @param files list of Java source file paths to parse
     * @return list of parsed CodeChunks for all valid files
     */
    public List<CodeChunk> parse(List<Path> files) {
        List<CodeChunk> chunks = new ArrayList<>();
        if (files == null) {
            return chunks;
        }

        for (Path path : files) {
            try {
                ParseResult<CompilationUnit> result = javaParser.parse(path);
                if (result.isSuccessful() && result.getResult().isPresent()) {
                    List<CodeChunk> fileChunks = new ArrayList<>();
                    visitor.visit(result.getResult().get(), fileChunks);
                    chunks.addAll(fileChunks);
                } else {
                    log.error("Failed to parse file: {} | Problems: {}", path, result.getProblems());
                }
            } catch (Exception e) {
                log.error("Exception parsing file: {}", path, e);
            }
        }

        return chunks;
    }
}
