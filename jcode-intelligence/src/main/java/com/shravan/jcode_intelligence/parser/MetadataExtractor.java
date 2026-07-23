package com.shravan.jcode_intelligence.parser;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.*;
import com.shravan.jcode_intelligence.model.CodeChunk;
import org.springframework.stereotype.Component;
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers;

import java.util.List;

@Component
public class MetadataExtractor {

    public String getPackageName(CompilationUnit cu) {
        return cu.getPackageDeclaration()
                .map(pd -> pd.getNameAsString())
                .orElse("");
    }

    public List<String> getImports(CompilationUnit cu) {
        return cu.getImports()
                .stream()
                .map(ImportDeclaration::getNameAsString)
                .toList();
    }

    public String getFilePath(CompilationUnit cu) {
        return cu.getStorage()
                .map(storage -> storage.getPath().toString())
                .orElse("");
    }

    public String getClassName(Node node) {

        return node.findAncestor(TypeDeclaration.class)
                .map(TypeDeclaration::getNameAsString)
                .orElse("");
    }

    public List<String> getAnnotations(BodyDeclaration<?> node) {
        return node.getAnnotations()
                .stream()
                .map(a -> a.getNameAsString())
                .toList();
    }

    public List<String> getModifiers(Node node) {

        if (node instanceof NodeWithModifiers<?> modifiable) {
            return modifiable.getModifiers()
                    .stream()
                    .map(modifier -> modifier.getKeyword().asString())
                    .toList();
        }

        return List.of();
    }

    public int getStartLine(Node node) {
        return node.getBegin()
                .map(position -> position.line)
                .orElse(-1);
    }

    public int getEndLine(Node node) {
        return node.getEnd()
                .map(position -> position.line)
                .orElse(-1);
    }

    public void populateMetadata(CodeChunk chunk,
                                 Node node,
                                 CompilationUnit cu) {

        chunk.setPackageName(getPackageName(cu));
        chunk.setImports(getImports(cu));
        chunk.setFilePath(getFilePath(cu));

        if (node instanceof TypeDeclaration<?> type) {

            chunk.setClassName(type.getNameAsString());
            chunk.setElementName(type.getNameAsString());

        } else {

            chunk.setClassName(getClassName(node));

            if (node instanceof MethodDeclaration method) {

                chunk.setElementName(method.getNameAsString());
                chunk.setSignature(method.getDeclarationAsString(false, false, false));

            } else if (node instanceof ConstructorDeclaration constructor) {

                chunk.setElementName(constructor.getNameAsString());
                chunk.setSignature(constructor.getDeclarationAsString(false, false, false));

            } else if (node instanceof EnumDeclaration enumDecl) {

                chunk.setElementName(enumDecl.getNameAsString());

            } else if (node instanceof RecordDeclaration recordDecl) {

                chunk.setElementName(recordDecl.getNameAsString());
            }
        }

        if (node instanceof BodyDeclaration<?> body) {
            chunk.setAnnotations(getAnnotations(body));
        } else {
            chunk.setAnnotations(List.of());
        }

        chunk.setModifiers(getModifiers(node));
        chunk.setStartLine(getStartLine(node));
        chunk.setEndLine(getEndLine(node));
        chunk.setContent(node.toString());
        chunk.setLanguage("Java");
    }
}