package com.shravan.jcode_intelligence.parser;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.shravan.jcode_intelligence.model.CodeChunk;
import org.springframework.stereotype.Component;

@Component
public class ChunkGenerator {

    private final MetadataExtractor extractor;

    public ChunkGenerator(MetadataExtractor extractor) {
        this.extractor = extractor;
    }

    public CodeChunk generateTypeChunk(ClassOrInterfaceDeclaration declaration,
                                       CompilationUnit cu) {

        return buildChunk(
                declaration.isInterface() ? "INTERFACE" : "CLASS",
                declaration,
                cu
        );
    }

    public CodeChunk generateMethodChunk(MethodDeclaration declaration,
                                         CompilationUnit cu) {

        return buildChunk("METHOD", declaration, cu);
    }

    public CodeChunk generateConstructorChunk(ConstructorDeclaration declaration,
                                              CompilationUnit cu) {

        return buildChunk("CONSTRUCTOR", declaration, cu);
    }

    public CodeChunk generateEnumChunk(EnumDeclaration declaration,
                                       CompilationUnit cu) {

        return buildChunk("ENUM", declaration, cu);
    }

    public CodeChunk generateRecordChunk(RecordDeclaration declaration,
                                         CompilationUnit cu) {

        return buildChunk("RECORD", declaration, cu);
    }

    private CodeChunk buildChunk(String type,
                                 Node node,
                                 CompilationUnit cu) {

        CodeChunk chunk = new CodeChunk();

        chunk.setType(type);

        extractor.populateMetadata(chunk, node, cu);

        return chunk;
    }
}