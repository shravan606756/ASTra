package com.shravan.jcode_intelligence.parser;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.shravan.jcode_intelligence.model.CodeChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * AST visitor that traverses JavaParser CompilationUnits and delegates
 * chunk generation to {@link ChunkGenerator}.
 *
 * <p>This visitor only handles top-level type declarations (classes,
 * interfaces, enums, records). Individual members (methods, constructors,
 * fields) and inner classes are decomposed inside ChunkGenerator's
 * hierarchical processing. This prevents the double-counting that occurred
 * in the previous implementation, where both the class visit and
 * individual method/constructor visits produced overlapping chunks.
 *
 * <p><b>Key design decision:</b> We override the {@code visit} methods
 * but do NOT call {@code super.visit()} for ClassOrInterfaceDeclaration.
 * This prevents the VoidVisitorAdapter from descending into inner classes
 * and generating duplicate chunks — inner class decomposition is handled
 * recursively by ChunkGenerator.
 */
@Component
public class AstVisitor extends VoidVisitorAdapter<List<CodeChunk>> {

    private static final Logger log = LoggerFactory.getLogger(AstVisitor.class);

    private final ChunkGenerator chunkGenerator;

    public AstVisitor(ChunkGenerator chunkGenerator) {
        this.chunkGenerator = chunkGenerator;
    }

    @Override
    public void visit(ClassOrInterfaceDeclaration declaration, List<CodeChunk> chunks) {
        // Only process top-level types here. Inner classes are handled
        // recursively by ChunkGenerator.generateInnerClassChunks().
        if (isTopLevelType(declaration)) {
            CompilationUnit cu = declaration.findCompilationUnit().orElseThrow();
            List<CodeChunk> generated = chunkGenerator.generateChunksForType(declaration, cu);
            chunks.addAll(generated);
        }
        // Do NOT call super.visit() — we handle inner classes recursively
        // inside ChunkGenerator, not via visitor traversal.
    }

    @Override
    public void visit(EnumDeclaration declaration, List<CodeChunk> chunks) {
        if (isTopLevelType(declaration)) {
            CompilationUnit cu = declaration.findCompilationUnit().orElseThrow();
            List<CodeChunk> generated = chunkGenerator.generateChunksForEnum(declaration, cu);
            chunks.addAll(generated);
        }
    }

    @Override
    public void visit(RecordDeclaration declaration, List<CodeChunk> chunks) {
        if (isTopLevelType(declaration)) {
            CompilationUnit cu = declaration.findCompilationUnit().orElseThrow();
            List<CodeChunk> generated = chunkGenerator.generateChunksForRecord(declaration, cu);
            chunks.addAll(generated);
        }
    }

    // NOTE: visit(MethodDeclaration) and visit(ConstructorDeclaration)
    // are deliberately NOT overridden. Methods and constructors are
    // generated as part of the hierarchical decomposition inside
    // ChunkGenerator.generateChunksForType(), not by separate visitor calls.

    /**
     * Returns true if this type declaration is a top-level type
     * (i.e., not nested inside another type). Inner classes and
     * inner enums are processed recursively by ChunkGenerator.
     */
    private boolean isTopLevelType(TypeDeclaration<?> declaration) {
        return declaration.findAncestor(TypeDeclaration.class).isEmpty();
    }
}