package com.shravan.jcode_intelligence.parser;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.*;
import com.shravan.jcode_intelligence.model.ChunkType;
import com.shravan.jcode_intelligence.model.CodeChunk;
import com.shravan.jcode_intelligence.parser.spi.ChunkBudgetEstimator;
import com.shravan.jcode_intelligence.parser.spi.CodeFragmenter;
import com.shravan.jcode_intelligence.parser.spi.TypeSummaryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Orchestrates hierarchical AST-aware chunk generation for Java source code.
 */
@Component
public class ChunkGenerator {

    private static final Logger log = LoggerFactory.getLogger(ChunkGenerator.class);

    private final MetadataExtractor extractor;
    private final TypeSummaryBuilder summaryBuilder;
    private final CodeFragmenter methodFragmenter;
    private final ChunkBudgetEstimator budgetEstimator;

    public ChunkGenerator(MetadataExtractor extractor,
                          TypeSummaryBuilder summaryBuilder,
                          CodeFragmenter methodFragmenter,
                          ChunkBudgetEstimator budgetEstimator) {
        this.extractor = extractor;
        this.summaryBuilder = summaryBuilder;
        this.methodFragmenter = methodFragmenter;
        this.budgetEstimator = budgetEstimator;
    }

    // ── Public API ────────────────────────────────────────────

    public List<CodeChunk> generateChunksForType(ClassOrInterfaceDeclaration declaration,
                                                  CompilationUnit cu) {

        return generateChunksForTypeDeclaration(declaration, cu, 0, null);
    }

    public List<CodeChunk> generateChunksForEnum(EnumDeclaration declaration,
                                                  CompilationUnit cu) {

        String summary = summaryBuilder.buildEnumSummary(declaration, cu);
        String chunkId = buildChunkId(declaration, cu, ChunkType.ENUM);

        CodeChunk typeChunk = buildTypeChunk(declaration, cu, ChunkType.ENUM, summary, chunkId, null, 0, null);

        List<CodeChunk> chunks = new ArrayList<>();
        chunks.add(typeChunk);

        generateFieldChunks(declaration, cu, chunkId, chunks);
        generateConstructorChunks(declaration, cu, chunkId, chunks);
        generateMethodChunks(declaration, cu, chunkId, chunks);

        log.info("Generated {} chunk(s) for ENUM '{}'", chunks.size(), declaration.getNameAsString());
        return chunks;
    }

    public List<CodeChunk> generateChunksForRecord(RecordDeclaration declaration,
                                                    CompilationUnit cu) {

        String summary = summaryBuilder.buildRecordSummary(declaration, cu);
        String chunkId = buildChunkId(declaration, cu, ChunkType.RECORD);

        CodeChunk typeChunk = buildTypeChunk(declaration, cu, ChunkType.RECORD, summary, chunkId, null, 0, null);

        List<CodeChunk> chunks = new ArrayList<>();
        chunks.add(typeChunk);

        generateMethodChunks(declaration, cu, chunkId, chunks);

        log.info("Generated {} chunk(s) for RECORD '{}'", chunks.size(), declaration.getNameAsString());
        return chunks;
    }

    // ── Unified Recursive Type Decomposition ─────────────────

    private List<CodeChunk> generateChunksForTypeDeclaration(ClassOrInterfaceDeclaration declaration,
                                                             CompilationUnit cu,
                                                             int nestingDepth,
                                                             String parentChunkId) {

        ChunkType chunkType = declaration.isInterface() ? ChunkType.INTERFACE : ChunkType.CLASS;
        String summary = summaryBuilder.buildSummary(declaration, cu);
        String chunkId = buildChunkId(declaration, cu, chunkType);

        String outerClassName = (nestingDepth > 0 && declaration.getParentNode().isPresent())
                ? declaration.findAncestor(TypeDeclaration.class)
                        .map(TypeDeclaration::getNameAsString).orElse(null)
                : null;

        CodeChunk typeChunk = buildTypeChunk(declaration, cu, chunkType, summary, chunkId, parentChunkId, nestingDepth, outerClassName);

        List<CodeChunk> chunks = new ArrayList<>();
        chunks.add(typeChunk);

        generateFieldChunks(declaration, cu, chunkId, chunks);
        generateConstructorChunks(declaration, cu, chunkId, chunks);
        generateMethodChunks(declaration, cu, chunkId, chunks);
        generateNestedTypeChunks(declaration, cu, chunkId, nestingDepth + 1, chunks);

        log.info("Generated {} chunk(s) for {} '{}' (depth {})",
                chunks.size(), chunkType, declaration.getNameAsString(), nestingDepth);

        return chunks;
    }

    // ── Member Chunks (Consolidated on TypeDeclaration) ───────

    private void generateFieldChunks(TypeDeclaration<?> declaration,
                                     CompilationUnit cu,
                                     String parentChunkId,
                                     List<CodeChunk> chunks) {

        for (FieldDeclaration field : declaration.getFields()) {
            CodeChunk chunk = new CodeChunk();
            chunk.setType(ChunkType.FIELD.name());
            extractor.populateCommonMetadata(chunk, field, cu);

            String fieldName = extractor.getFieldName(field);
            chunk.setId(buildMemberChunkId(parentChunkId, ChunkType.FIELD, fieldName));
            chunk.setParentChunkId(parentChunkId);

            String fullDecl = extractor.getFieldDeclaration(field);
            int origLength = fullDecl.length();
            int maxFieldBudget = budgetEstimator.getMaxFieldBudget();

            // If a single field declaration (e.g. giant generated array) exceeds budget, summarize it safely
            if (origLength > maxFieldBudget) {
                log.info("Field '{}' in '{}' exceeds budget ({} chars > {}), generating summarized field chunk",
                        fieldName, declaration.getNameAsString(), origLength, maxFieldBudget);
                
                String summarizedDecl = buildSummarizedFieldDeclaration(field, fullDecl, maxFieldBudget);
                chunk.setContent(summarizedDecl);
                chunk.setSummarized(true);
            } else {
                chunk.setContent(fullDecl);
                chunk.setSummarized(false);
            }

            chunk.setOriginalElementLength(origLength);
            chunk.setFragmented(false);

            chunks.add(chunk);
        }
    }

    private String buildSummarizedFieldDeclaration(FieldDeclaration field, String fullDecl, int maxBudget) {
        StringBuilder sb = new StringBuilder();
        field.getAnnotations().forEach(a -> sb.append(a.toString().trim()).append("\n"));
        field.getModifiers().forEach(m -> sb.append(m.getKeyword().asString()).append(" "));
        sb.append(field.getElementType().asString()).append(" ");
        sb.append(field.getVariables().stream().map(VariableDeclarator::getNameAsString).reduce((a, b) -> a + ", " + b).orElse(""));
        sb.append(" = ");
        
        // Show first 200 chars of initializer then summarize
        if (fullDecl.length() > 200) {
            sb.append(fullDecl.substring(0, 200).replace("\n", " "));
            sb.append(String.format(" ... /* %d characters of oversized field initializer omitted */;", fullDecl.length() - 200));
        } else {
            sb.append("/* ... oversized field initializer omitted */;");
        }
        return sb.toString();
    }

    private void generateConstructorChunks(TypeDeclaration<?> declaration,
                                            CompilationUnit cu,
                                            String parentChunkId,
                                            List<CodeChunk> chunks) {

        for (ConstructorDeclaration constructor : declaration.getConstructors()) {
            String bodyText = constructor.toString();
            int origLength = bodyText.length();

            if (budgetEstimator.exceedsMethodBudget(bodyText)) {
                log.info("Constructor '{}' exceeds budget ({} units), fragmenting", constructor.getNameAsString(), origLength);
                chunks.addAll(methodFragmenter.fragment(constructor, cu, parentChunkId));
            } else {
                CodeChunk chunk = new CodeChunk();
                chunk.setType(ChunkType.CONSTRUCTOR.name());
                extractor.populateCommonMetadata(chunk, constructor, cu);

                chunk.setId(buildMemberChunkId(parentChunkId, ChunkType.CONSTRUCTOR, constructor.getNameAsString()));
                chunk.setParentChunkId(parentChunkId);
                chunk.setContent(bodyText);
                chunk.setOriginalElementLength(origLength);
                chunk.setSummarized(false);
                chunk.setFragmented(false);

                chunks.add(chunk);
            }
        }
    }

    private void generateMethodChunks(TypeDeclaration<?> declaration,
                                       CompilationUnit cu,
                                       String parentChunkId,
                                       List<CodeChunk> chunks) {

        for (MethodDeclaration method : declaration.getMethods()) {
            String bodyText = method.toString();
            int origLength = bodyText.length();

            if (budgetEstimator.exceedsMethodBudget(bodyText)) {
                log.info("Method '{}' exceeds budget ({} units), fragmenting", method.getNameAsString(), origLength);
                chunks.addAll(methodFragmenter.fragment(method, cu, parentChunkId));
            } else {
                CodeChunk chunk = new CodeChunk();
                chunk.setType(ChunkType.METHOD.name());
                extractor.populateCommonMetadata(chunk, method, cu);

                chunk.setId(buildMemberChunkId(parentChunkId, ChunkType.METHOD, method.getNameAsString()));
                chunk.setParentChunkId(parentChunkId);
                chunk.setContent(bodyText);
                chunk.setOriginalElementLength(origLength);
                chunk.setSummarized(false);
                chunk.setFragmented(false);

                chunks.add(chunk);
            }
        }
    }

    private void generateNestedTypeChunks(ClassOrInterfaceDeclaration declaration,
                                           CompilationUnit cu,
                                           String parentChunkId,
                                           int nestingDepth,
                                           List<CodeChunk> chunks) {

        for (BodyDeclaration<?> member : declaration.getMembers()) {
            if (member instanceof ClassOrInterfaceDeclaration innerClass) {
                chunks.addAll(generateChunksForTypeDeclaration(innerClass, cu, nestingDepth, parentChunkId));
            } else if (member instanceof EnumDeclaration innerEnum) {
                String enumSummary = summaryBuilder.buildEnumSummary(innerEnum, cu);
                String enumChunkId = buildChunkId(innerEnum, cu, ChunkType.ENUM);

                CodeChunk enumChunk = buildTypeChunk(innerEnum, cu, ChunkType.ENUM, enumSummary, enumChunkId, parentChunkId, nestingDepth, declaration.getNameAsString());
                chunks.add(enumChunk);

                generateFieldChunks(innerEnum, cu, enumChunkId, chunks);
                generateMethodChunks(innerEnum, cu, enumChunkId, chunks);
            }
        }
    }

    // ── Type Chunk Builder & Relationship Populator ───────────

    private CodeChunk buildTypeChunk(TypeDeclaration<?> declaration,
                                     CompilationUnit cu,
                                     ChunkType chunkType,
                                     String summaryContent,
                                     String chunkId,
                                     String parentChunkId,
                                     int nestingDepth,
                                     String outerClassName) {

        CodeChunk chunk = new CodeChunk();
        chunk.setType(chunkType.name());
        chunk.setId(chunkId);
        chunk.setParentChunkId(parentChunkId);
        chunk.setNestingDepth(nestingDepth);
        chunk.setOuterClassName(outerClassName);
        chunk.setContent(summaryContent);
        chunk.setOriginalElementLength(declaration.toString().length());
        chunk.setSummarized(true);
        chunk.setFragmented(false);

        extractor.populateCommonMetadata(chunk, declaration, cu);

        Map<String, List<String>> rels = new HashMap<>();
        String pkg = extractor.getPackageName(cu);
        if (!pkg.isBlank()) {
            rels.put("belongsTo", List.of(pkg));
        }

        if (declaration instanceof ClassOrInterfaceDeclaration classDecl) {
            String superClass = extractor.getSuperClass(classDecl);
            if (!superClass.isBlank()) {
                chunk.setSuperClass(superClass);
                rels.put("extends", List.of(superClass));
            }
            List<String> interfaces = extractor.getImplementedInterfaces(classDecl);
            if (!interfaces.isEmpty()) {
                chunk.setInterfaces(interfaces);
                rels.put("implements", interfaces);
            }
        }

        chunk.setRelationships(rels);
        return chunk;
    }

    // ── ID Generation ─────────────────────────────────────────

    private String buildChunkId(TypeDeclaration<?> declaration, CompilationUnit cu, ChunkType chunkType) {
        String packageName = extractor.getPackageName(cu);
        String className = declaration.getNameAsString();

        String outerName = declaration.findAncestor(TypeDeclaration.class)
                .filter(t -> t != declaration)
                .map(TypeDeclaration::getNameAsString)
                .orElse(null);

        String qualifiedName = outerName != null
                ? packageName + "." + outerName + "." + className
                : packageName + "." + className;

        return qualifiedName + "::" + chunkType.name();
    }

    private String buildMemberChunkId(String parentChunkId, ChunkType chunkType, String elementName) {
        return parentChunkId + "::" + chunkType.name() + "::" + elementName;
    }
}