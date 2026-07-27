package com.shravan.jcode_intelligence.parser.spi;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.shravan.jcode_intelligence.model.CodeChunk;

import java.util.List;

/**
 * Strategy interface for splitting oversized AST methods/constructors into fragments.
 */
public interface CodeFragmenter {

    List<CodeChunk> fragment(MethodDeclaration method, CompilationUnit cu, String parentChunkId);

    List<CodeChunk> fragment(ConstructorDeclaration constructor, CompilationUnit cu, String parentChunkId);
}
