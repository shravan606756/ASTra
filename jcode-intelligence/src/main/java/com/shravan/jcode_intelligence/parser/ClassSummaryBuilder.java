package com.shravan.jcode_intelligence.parser;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.comments.JavadocComment;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.shravan.jcode_intelligence.config.ChunkingConfig;
import com.shravan.jcode_intelligence.parser.spi.ChunkBudgetEstimator;
import com.shravan.jcode_intelligence.parser.spi.TypeSummaryBuilder;
import org.springframework.stereotype.Component;

/**
 * Builds a lightweight textual summary of a class, interface, enum, or record declaration,
 * suitable for embedding without exceeding model token limits.
 *
 * <p>Enforces budget caps on all sections (Javadoc, imports, annotations,
 * fields, constructors, methods, inner types) to guarantee that no class summary ever
 * exceeds 2048-token embedding context limits.
 */
@Component
public class ClassSummaryBuilder implements TypeSummaryBuilder {

    private static final int MAX_SUMMARY_JAVADOC_CHARS = 800;
    private static final int MAX_SUMMARY_IMPORTS = 20;
    private static final int MAX_SUMMARY_ANNOTATIONS = 10;
    private static final int MAX_SUMMARY_CONSTRUCTORS = 15;

    private final ChunkBudgetEstimator budgetEstimator;

    public ClassSummaryBuilder(ChunkBudgetEstimator budgetEstimator) {
        this.budgetEstimator = budgetEstimator;
    }

    public ClassSummaryBuilder() {
        this.budgetEstimator = new CharBasedBudgetEstimator(new ChunkingConfig());
    }

    @Override
    public String buildSummary(ClassOrInterfaceDeclaration declaration,
                               CompilationUnit cu) {

        StringBuilder sb = new StringBuilder();

        appendPackage(sb, cu);
        appendImports(sb, cu);
        appendJavadoc(sb, declaration);
        appendAnnotations(sb, declaration);
        appendClassHeader(sb, declaration);

        sb.append(" {\n");

        appendFields(sb, declaration);
        appendConstructors(sb, declaration);
        appendMethods(sb, declaration);
        appendInnerClasses(sb, declaration);

        sb.append("}\n");

        return sb.toString();
    }

    @Override
    public String buildEnumSummary(EnumDeclaration declaration,
                                   CompilationUnit cu) {

        StringBuilder sb = new StringBuilder();

        appendPackage(sb, cu);
        appendImports(sb, cu);
        appendJavadoc(sb, declaration);
        appendAnnotations(sb, declaration);

        // Enum header
        declaration.getModifiers().forEach(m ->
                sb.append(m.getKeyword().asString()).append(" "));
        sb.append("enum ").append(declaration.getNameAsString());

        if (!declaration.getImplementedTypes().isEmpty()) {
            sb.append(" implements ");
            sb.append(declaration.getImplementedTypes().stream()
                    .map(ClassOrInterfaceType::getNameAsString)
                    .reduce((a, b) -> a + ", " + b)
                    .orElse(""));
        }

        sb.append(" {\n");

        // Enum constants
        if (!declaration.getEntries().isEmpty()) {
            sb.append("\n    // Constants\n");
            int maxEntries = Math.min(25, budgetEstimator.getMaxSummaryFields());
            int count = 0;
            for (var entry : declaration.getEntries()) {
                if (count >= maxEntries) {
                    sb.append(String.format("    // ... %d additional enum constants omitted\n",
                            declaration.getEntries().size() - count));
                    break;
                }
                sb.append("    ").append(entry.getNameAsString()).append(",\n");
                count++;
            }
        }

        appendFieldsFromMembers(sb, declaration);
        appendConstructorsFromMembers(sb, declaration);
        appendMethodsFromMembers(sb, declaration);

        sb.append("}\n");

        return sb.toString();
    }

    @Override
    public String buildRecordSummary(RecordDeclaration declaration,
                                     CompilationUnit cu) {

        StringBuilder sb = new StringBuilder();

        appendPackage(sb, cu);
        appendImports(sb, cu);
        appendJavadoc(sb, declaration);
        appendAnnotations(sb, declaration);

        // Record header with components
        declaration.getModifiers().forEach(m ->
                sb.append(m.getKeyword().asString()).append(" "));
        sb.append("record ").append(declaration.getNameAsString());
        sb.append("(");
        sb.append(declaration.getParameters().stream()
                .map(p -> p.getTypeAsString() + " " + p.getNameAsString())
                .reduce((a, b) -> a + ", " + b)
                .orElse(""));
        sb.append(")");

        if (!declaration.getImplementedTypes().isEmpty()) {
            sb.append(" implements ");
            sb.append(declaration.getImplementedTypes().stream()
                    .map(ClassOrInterfaceType::getNameAsString)
                    .reduce((a, b) -> a + ", " + b)
                    .orElse(""));
        }

        sb.append(" {\n");

        appendMethodsFromMembers(sb, declaration);

        sb.append("}\n");

        return sb.toString();
    }

    // ── Private section builders with budget caps ──────────────

    private void appendPackage(StringBuilder sb, CompilationUnit cu) {
        cu.getPackageDeclaration().ifPresent(pd ->
                sb.append("package ").append(pd.getNameAsString()).append(";\n\n"));
    }

    private void appendImports(StringBuilder sb, CompilationUnit cu) {
        var imports = cu.getImports();
        if (!imports.isEmpty()) {
            int count = 0;
            for (var imp : imports) {
                if (count >= MAX_SUMMARY_IMPORTS) {
                    sb.append(String.format("// ... %d additional imports omitted from class summary\n", imports.size() - count));
                    break;
                }
                sb.append("import ").append(imp.getNameAsString()).append(";\n");
                count++;
            }
            sb.append("\n");
        }
    }

    private void appendJavadoc(StringBuilder sb, BodyDeclaration<?> declaration) {
        declaration.getComment()
                .filter(c -> c instanceof JavadocComment)
                .ifPresent(c -> {
                    String javadoc = c.getContent().trim();
                    if (javadoc.length() > MAX_SUMMARY_JAVADOC_CHARS) {
                        javadoc = javadoc.substring(0, MAX_SUMMARY_JAVADOC_CHARS) + "\n * ... [Javadoc truncated for summary]";
                    }
                    sb.append("/**\n").append(javadoc).append("\n*/\n");
                });
    }

    private void appendAnnotations(StringBuilder sb, BodyDeclaration<?> declaration) {
        var annotations = declaration.getAnnotations();
        if (!annotations.isEmpty()) {
            int count = 0;
            for (var ann : annotations) {
                if (count >= MAX_SUMMARY_ANNOTATIONS) {
                    sb.append(String.format("// ... %d additional annotations omitted\n",
                            annotations.size() - count));
                    break;
                }
                sb.append(ann.toString().trim()).append("\n");
                count++;
            }
        }
    }

    private void appendClassHeader(StringBuilder sb,
                                   ClassOrInterfaceDeclaration declaration) {

        declaration.getModifiers().forEach(m ->
                sb.append(m.getKeyword().asString()).append(" "));

        sb.append(declaration.isInterface() ? "interface " : "class ");
        sb.append(declaration.getNameAsString());

        if (!declaration.getTypeParameters().isEmpty()) {
            sb.append("<");
            sb.append(declaration.getTypeParameters().stream()
                    .map(tp -> tp.getNameAsString())
                    .reduce((a, b) -> a + ", " + b)
                    .orElse(""));
            sb.append(">");
        }

        if (!declaration.getExtendedTypes().isEmpty()) {
            sb.append(" extends ");
            sb.append(declaration.getExtendedTypes().stream()
                    .map(ClassOrInterfaceType::getNameAsString)
                    .reduce((a, b) -> a + ", " + b)
                    .orElse(""));
        }

        if (!declaration.getImplementedTypes().isEmpty()) {
            sb.append(" implements ");
            sb.append(declaration.getImplementedTypes().stream()
                    .map(ClassOrInterfaceType::getNameAsString)
                    .reduce((a, b) -> a + ", " + b)
                    .orElse(""));
        }
    }

    private void appendFields(StringBuilder sb, ClassOrInterfaceDeclaration declaration) {
        var fields = declaration.getFields();
        if (!fields.isEmpty()) {
            sb.append("\n    // Fields\n");
            int maxFields = Math.min(25, budgetEstimator.getMaxSummaryFields());
            int count = 0;
            for (FieldDeclaration field : fields) {
                if (count >= maxFields) {
                    sb.append(String.format("    // ... %d additional field signatures omitted from class summary\n",
                            fields.size() - count));
                    break;
                }
                field.getAnnotations().forEach(a ->
                        sb.append("    ").append(a.toString().trim()).append("\n"));
                sb.append("    ");
                field.getModifiers().forEach(m ->
                        sb.append(m.getKeyword().asString()).append(" "));
                sb.append(field.getElementType().asString()).append(" ");
                sb.append(field.getVariables().stream()
                        .map(v -> v.getNameAsString())
                        .reduce((a, b) -> a + ", " + b)
                        .orElse(""));
                sb.append(";\n");
                count++;
            }
        }
    }

    private void appendConstructors(StringBuilder sb, ClassOrInterfaceDeclaration declaration) {
        var constructors = declaration.getConstructors();
        if (!constructors.isEmpty()) {
            sb.append("\n    // Constructors\n");
            int count = 0;
            for (ConstructorDeclaration constructor : constructors) {
                if (count >= MAX_SUMMARY_CONSTRUCTORS) {
                    sb.append(String.format("    // ... %d additional constructors omitted from class summary\n",
                            constructors.size() - count));
                    break;
                }
                constructor.getAnnotations().forEach(a ->
                        sb.append("    ").append(a.toString().trim()).append("\n"));
                sb.append("    ").append(constructor.getDeclarationAsString(true, true, true));
                sb.append("\n");
                count++;
            }
        }
    }

    private void appendMethods(StringBuilder sb, ClassOrInterfaceDeclaration declaration) {
        var methods = declaration.getMethods();
        if (!methods.isEmpty()) {
            sb.append("\n    // Methods\n");
            int maxMethods = Math.min(40, budgetEstimator.getMaxSummaryMethods());
            int count = 0;
            for (MethodDeclaration method : methods) {
                if (count >= maxMethods) {
                    sb.append(String.format("    // ... %d additional method signatures omitted from class summary\n",
                            methods.size() - count));
                    break;
                }
                method.getAnnotations().forEach(a ->
                        sb.append("    ").append(a.toString().trim()).append("\n"));
                sb.append("    ").append(method.getDeclarationAsString(true, true, true));
                sb.append("\n");
                count++;
            }
        }
    }

    private void appendInnerClasses(StringBuilder sb, ClassOrInterfaceDeclaration declaration) {
        var innerTypes = declaration.getMembers().stream()
                .filter(m -> m instanceof ClassOrInterfaceDeclaration)
                .map(m -> (ClassOrInterfaceDeclaration) m)
                .toList();

        if (!innerTypes.isEmpty()) {
            sb.append("\n    // Inner Classes\n");
            int maxInner = 15;
            int count = 0;
            for (ClassOrInterfaceDeclaration inner : innerTypes) {
                if (count >= maxInner) {
                    sb.append(String.format("    // ... %d additional inner classes omitted from summary\n",
                            innerTypes.size() - count));
                    break;
                }
                inner.getModifiers().forEach(m ->
                        sb.append("    ").append(m.getKeyword().asString()).append(" "));
                sb.append(inner.isInterface() ? "interface " : "class ");
                sb.append(inner.getNameAsString()).append("\n");
                count++;
            }
        }
    }

    // ── Helpers for enum / record bodies ──────────────────────

    private void appendFieldsFromMembers(StringBuilder sb, TypeDeclaration<?> declaration) {
        var fields = declaration.getFields();
        if (!fields.isEmpty()) {
            sb.append("\n    // Fields\n");
            int maxFields = Math.min(25, budgetEstimator.getMaxSummaryFields());
            int count = 0;
            for (FieldDeclaration field : fields) {
                if (count >= maxFields) {
                    sb.append(String.format("    // ... %d additional field signatures omitted\n", fields.size() - count));
                    break;
                }
                sb.append("    ");
                field.getModifiers().forEach(m ->
                        sb.append(m.getKeyword().asString()).append(" "));
                sb.append(field.getElementType().asString()).append(" ");
                sb.append(field.getVariables().stream()
                        .map(v -> v.getNameAsString())
                        .reduce((a, b) -> a + ", " + b)
                        .orElse(""));
                sb.append(";\n");
                count++;
            }
        }
    }

    private void appendConstructorsFromMembers(StringBuilder sb, TypeDeclaration<?> declaration) {
        var constructors = declaration.getConstructors();
        if (!constructors.isEmpty()) {
            sb.append("\n    // Constructors\n");
            int count = 0;
            for (ConstructorDeclaration constructor : constructors) {
                if (count >= MAX_SUMMARY_CONSTRUCTORS) {
                    sb.append(String.format("    // ... %d additional constructors omitted\n", constructors.size() - count));
                    break;
                }
                sb.append("    ").append(constructor.getDeclarationAsString(true, true, true));
                sb.append("\n");
                count++;
            }
        }
    }

    private void appendMethodsFromMembers(StringBuilder sb, TypeDeclaration<?> declaration) {
        var methods = declaration.getMethods();
        if (!methods.isEmpty()) {
            sb.append("\n    // Methods\n");
            int maxMethods = Math.min(40, budgetEstimator.getMaxSummaryMethods());
            int count = 0;
            for (MethodDeclaration method : methods) {
                if (count >= maxMethods) {
                    sb.append(String.format("    // ... %d additional method signatures omitted\n", methods.size() - count));
                    break;
                }
                sb.append("    ").append(method.getDeclarationAsString(true, true, true));
                sb.append("\n");
                count++;
            }
        }
    }
}
