package com.shravan.jcode_intelligence.parser;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.comments.JavadocComment;
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.shravan.jcode_intelligence.model.CodeChunk;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Extracts AST metadata from JavaParser nodes and populates CodeChunk fields.
 *
 * <p>This class is responsible only for reading the AST — it does NOT
 * determine the content of a chunk. Content is set by the caller
 * (ChunkGenerator, ClassSummaryBuilder, MethodFragmenter).
 */
@Component
public class MetadataExtractor {

    // ── CompilationUnit-level extraction ──────────────────────

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

    // ── Type-level extraction ─────────────────────────────────

    /**
     * Returns the name of the nearest enclosing TypeDeclaration.
     * Used for members (methods, constructors, fields) that need
     * their owning class name.
     */
    public String getEnclosingClassName(Node node) {
        return node.findAncestor(TypeDeclaration.class)
                .map(TypeDeclaration::getNameAsString)
                .orElse("");
    }

    /**
     * Returns the extended superclass name, or empty string if none.
     */
    public String getSuperClass(ClassOrInterfaceDeclaration declaration) {
        return declaration.getExtendedTypes()
                .stream()
                .findFirst()
                .map(ClassOrInterfaceType::getNameAsString)
                .orElse("");
    }

    /**
     * Returns the list of implemented interface names.
     */
    public List<String> getImplementedInterfaces(ClassOrInterfaceDeclaration declaration) {
        return declaration.getImplementedTypes()
                .stream()
                .map(ClassOrInterfaceType::getNameAsString)
                .toList();
    }

    // ── Member-level extraction ───────────────────────────────

    public List<String> getAnnotations(BodyDeclaration<?> node) {
        return node.getAnnotations()
                .stream()
                .map(a -> a.toString().trim())
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

    /**
     * Extracts the JavaDoc comment from a body declaration, or null if absent.
     */
    public String getJavadoc(BodyDeclaration<?> node) {
        return node.getComment()
                .filter(c -> c instanceof JavadocComment)
                .map(c -> c.getContent().trim())
                .orElse(null);
    }

    // ── Position extraction ───────────────────────────────────

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

    // ── Field-specific extraction ─────────────────────────────

    /**
     * Builds a field declaration string including annotations, modifiers,
     * type, and variable names. Example output:
     * <pre>
     *   {@literal @}Autowired
     *   private final UserRepository userRepository;
     * </pre>
     */
    public String getFieldDeclaration(FieldDeclaration field) {
        StringBuilder sb = new StringBuilder();

        // Annotations
        field.getAnnotations().forEach(a -> sb.append(a.toString().trim()).append("\n"));

        // Modifiers
        field.getModifiers().forEach(m -> sb.append(m.getKeyword().asString()).append(" "));

        // Type
        sb.append(field.getElementType().asString()).append(" ");

        // Variable names (may be multiple: int x, y, z;)
        sb.append(field.getVariables().stream()
                .map(v -> {
                    String name = v.getNameAsString();
                    return v.getInitializer()
                            .map(init -> name + " = " + init)
                            .orElse(name);
                })
                .reduce((a, b) -> a + ", " + b)
                .orElse(""));

        sb.append(";");
        return sb.toString();
    }

    /**
     * Returns the primary variable name of a field declaration.
     */
    public String getFieldName(FieldDeclaration field) {
        return field.getVariables().stream()
                .findFirst()
                .map(v -> v.getNameAsString())
                .orElse("");
    }

    // ── Signature extraction ──────────────────────────────────

    public String getMethodSignature(MethodDeclaration method) {
        return method.getDeclarationAsString(true, true, true);
    }

    public String getConstructorSignature(ConstructorDeclaration constructor) {
        return constructor.getDeclarationAsString(true, true, true);
    }

    // ── Chunk population ──────────────────────────────────────

    /**
     * Populates common metadata fields on a CodeChunk from the given
     * AST node and CompilationUnit.
     *
     * <p>This method sets everything EXCEPT {@code content} and
     * hierarchy-specific fields (parentChunkId, fragmentIndex, etc.).
     * Those are set by the caller based on the chunking strategy.
     */
    public void populateCommonMetadata(CodeChunk chunk,
                                       Node node,
                                       CompilationUnit cu) {

        chunk.setPackageName(getPackageName(cu));
        chunk.setImports(getImports(cu));
        chunk.setFilePath(getFilePath(cu));

        if (node instanceof TypeDeclaration<?> type) {

            chunk.setClassName(type.getNameAsString());
            chunk.setElementName(type.getNameAsString());

        } else {

            chunk.setClassName(getEnclosingClassName(node));

            if (node instanceof MethodDeclaration method) {

                chunk.setElementName(method.getNameAsString());
                chunk.setSignature(getMethodSignature(method));

            } else if (node instanceof ConstructorDeclaration constructor) {

                chunk.setElementName(constructor.getNameAsString());
                chunk.setSignature(getConstructorSignature(constructor));

            } else if (node instanceof FieldDeclaration field) {

                chunk.setElementName(getFieldName(field));
                chunk.setSignature(getFieldDeclaration(field));

            } else if (node instanceof EnumDeclaration enumDecl) {

                chunk.setElementName(enumDecl.getNameAsString());

            } else if (node instanceof RecordDeclaration recordDecl) {

                chunk.setElementName(recordDecl.getNameAsString());
            }
        }

        if (node instanceof BodyDeclaration<?> body) {
            chunk.setAnnotations(getAnnotations(body));
            chunk.setJavadoc(getJavadoc(body));
        } else {
            chunk.setAnnotations(List.of());
        }

        chunk.setModifiers(getModifiers(node));
        chunk.setStartLine(getStartLine(node));
        chunk.setEndLine(getEndLine(node));
        chunk.setLanguage("Java");

        // NOTE: content is NOT set here — callers provide it.
    }
}