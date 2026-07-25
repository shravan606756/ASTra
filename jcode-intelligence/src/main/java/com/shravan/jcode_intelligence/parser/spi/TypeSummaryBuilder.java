package com.shravan.jcode_intelligence.parser.spi;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;

/**
 * Strategy interface for building lightweight textual summaries of AST type declarations.
 */
public interface TypeSummaryBuilder {

    String buildSummary(ClassOrInterfaceDeclaration declaration, CompilationUnit cu);

    String buildEnumSummary(EnumDeclaration declaration, CompilationUnit cu);

    String buildRecordSummary(RecordDeclaration declaration, CompilationUnit cu);
}
