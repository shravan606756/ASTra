package com.shravan.jcode_intelligence.parser;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.shravan.jcode_intelligence.model.CodeChunk;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AstVisitor extends VoidVisitorAdapter<List<CodeChunk>> {

    private final ChunkGenerator chunkGenerator;

    public AstVisitor(ChunkGenerator chunkGenerator) {
        this.chunkGenerator = chunkGenerator;
    }

    @Override
    public void visit(ClassOrInterfaceDeclaration declaration, List<CodeChunk> chunks) {
        super.visit(declaration, chunks);

        CompilationUnit cu = declaration.findCompilationUnit().orElseThrow();

        chunks.add(chunkGenerator.generateTypeChunk(declaration, cu));
    }

    @Override
    public void visit(MethodDeclaration declaration, List<CodeChunk> chunks) {
        super.visit(declaration, chunks);

        CompilationUnit cu = declaration.findCompilationUnit().orElseThrow();

        chunks.add(chunkGenerator.generateMethodChunk(declaration, cu));
    }

    @Override
    public void visit(ConstructorDeclaration declaration, List<CodeChunk> chunks) {
        super.visit(declaration, chunks);

        CompilationUnit cu = declaration.findCompilationUnit().orElseThrow();

        chunks.add(chunkGenerator.generateConstructorChunk(declaration, cu));
    }

    @Override
    public void visit(EnumDeclaration declaration, List<CodeChunk> chunks) {
        super.visit(declaration, chunks);

        CompilationUnit cu = declaration.findCompilationUnit().orElseThrow();

        chunks.add(chunkGenerator.generateEnumChunk(declaration, cu));
    }

    @Override
    public void visit(RecordDeclaration declaration, List<CodeChunk> chunks) {
        super.visit(declaration, chunks);

        CompilationUnit cu = declaration.findCompilationUnit().orElseThrow();

        chunks.add(chunkGenerator.generateRecordChunk(declaration, cu));
    }
}