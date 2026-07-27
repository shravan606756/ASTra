package com.shravan.jcode_intelligence.parser;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.shravan.jcode_intelligence.model.ChunkType;
import com.shravan.jcode_intelligence.model.CodeChunk;
import com.shravan.jcode_intelligence.parser.spi.ChunkBudgetEstimator;
import com.shravan.jcode_intelligence.parser.spi.CodeFragmenter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits oversized methods and constructors into semantically meaningful
 * {@link ChunkType#METHOD_FRAGMENT} chunks at AST statement boundaries.
 *
 * <p>Includes a fallback text sub-fragmentation strategy for giant individual AST
 * statements (e.g. 50,000-character generated switch blocks or array initializers)
 * to guarantee that no fragment ever exceeds the embedding budget.
 */
@Component
public class MethodFragmenter implements CodeFragmenter {

    private static final Logger log = LoggerFactory.getLogger(MethodFragmenter.class);

    private final MetadataExtractor extractor;
    private final ChunkBudgetEstimator budgetEstimator;

    public MethodFragmenter(MetadataExtractor extractor, ChunkBudgetEstimator budgetEstimator) {
        this.extractor = extractor;
        this.budgetEstimator = budgetEstimator;
    }

    @Override
    public List<CodeChunk> fragment(MethodDeclaration method,
                                    CompilationUnit cu,
                                    String parentChunkId) {

        String signature = extractor.getMethodSignature(method);
        String methodName = method.getNameAsString();

        return doFragment(method, method.getBody().orElse(null),
                signature, methodName, cu, parentChunkId);
    }

    @Override
    public List<CodeChunk> fragment(ConstructorDeclaration constructor,
                                    CompilationUnit cu,
                                    String parentChunkId) {

        String signature = extractor.getConstructorSignature(constructor);
        String methodName = constructor.getNameAsString();

        return doFragment(constructor, constructor.getBody(),
                signature, methodName, cu, parentChunkId);
    }

    // ── Core fragmentation logic ──────────────────────────────

    private List<CodeChunk> doFragment(Node declaration,
                                       BlockStmt body,
                                       String signature,
                                       String methodName,
                                       CompilationUnit cu,
                                       String parentChunkId) {

        List<CodeChunk> fragments = new ArrayList<>();

        if (body == null || body.getStatements().isEmpty()) {
            CodeChunk chunk = buildFragmentChunk(
                    declaration, cu, parentChunkId, methodName,
                    signature, signature + " { }", 0, declaration.toString().length());
            fragments.add(chunk);
            return fragments;
        }

        List<Statement> statements = body.getStatements();
        int originalLength = declaration.toString().length();

        List<Statement> currentFragmentStatements = new ArrayList<>();
        List<Statement> previousFragmentStatements = new ArrayList<>();
        StringBuilder currentFragmentText = new StringBuilder();

        int fragmentIndex = 0;
        int fragmentStartLine = extractor.getStartLine(statements.get(0));

        for (int i = 0; i < statements.size(); i++) {
            Statement stmt = statements.get(i);
            String stmtText = stmt.toString();

            // If a single AST statement exceeds the fragment budget by itself (e.g. giant generated switch or array init)
            if (budgetEstimator.exceedsFragmentBudget(stmtText)) {
                // First flush any accumulated normal statements
                if (currentFragmentText.length() > 0) {
                    int fragmentEndLine = (i > 0) ? extractor.getEndLine(statements.get(i - 1)) : fragmentStartLine;
                    String contextPreamble = extractVariableContext(previousFragmentStatements);
                    String fullContent = formatFragmentContent(signature, contextPreamble, currentFragmentText.toString(), fragmentIndex);

                    CodeChunk chunk = buildFragmentChunk(declaration, cu, parentChunkId, methodName, signature, fullContent, fragmentIndex, originalLength);
                    chunk.setStartLine(fragmentStartLine);
                    chunk.setEndLine(fragmentEndLine);
                    fragments.add(chunk);

                    previousFragmentStatements = new ArrayList<>(currentFragmentStatements);
                    currentFragmentStatements = new ArrayList<>();
                    currentFragmentText = new StringBuilder();
                    fragmentIndex++;
                }

                // Apply fallback line-based sub-fragmentation to the giant single statement
                fragmentIndex = subFragmentSingleStatement(
                        stmt, stmtText, declaration, cu, parentChunkId, methodName, signature,
                        fragmentIndex, originalLength, previousFragmentStatements, fragments);

                if (i + 1 < statements.size()) {
                    fragmentStartLine = extractor.getStartLine(statements.get(i + 1));
                }
                continue;
            }

            // Normal statement accumulation
            if (currentFragmentText.length() > 0 &&
                    budgetEstimator.exceedsFragmentBudget(currentFragmentText.toString() + stmtText)) {

                int fragmentEndLine = (i > 0) ? extractor.getEndLine(statements.get(i - 1)) : fragmentStartLine;

                String contextPreamble = extractVariableContext(previousFragmentStatements);
                String fullContent = formatFragmentContent(signature, contextPreamble, currentFragmentText.toString(), fragmentIndex);

                CodeChunk chunk = buildFragmentChunk(
                        declaration, cu, parentChunkId, methodName, signature,
                        fullContent, fragmentIndex, originalLength);
                chunk.setStartLine(fragmentStartLine);
                chunk.setEndLine(fragmentEndLine);
                fragments.add(chunk);

                previousFragmentStatements = new ArrayList<>(currentFragmentStatements);
                currentFragmentStatements = new ArrayList<>();
                currentFragmentText = new StringBuilder();
                fragmentIndex++;
                fragmentStartLine = extractor.getStartLine(stmt);
            }

            currentFragmentStatements.add(stmt);
            currentFragmentText.append(stmtText).append("\n");
        }

        // Flush remaining statements
        if (currentFragmentText.length() > 0) {
            int fragmentEndLine = extractor.getEndLine(statements.get(statements.size() - 1));

            String contextPreamble = extractVariableContext(previousFragmentStatements);
            String fullContent = formatFragmentContent(signature, contextPreamble, currentFragmentText.toString(), fragmentIndex);

            CodeChunk chunk = buildFragmentChunk(
                    declaration, cu, parentChunkId, methodName, signature,
                    fullContent, fragmentIndex, originalLength);
            chunk.setStartLine(fragmentStartLine);
            chunk.setEndLine(fragmentEndLine);
            fragments.add(chunk);
        }

        log.info("Fragmented method '{}' into {} fragment(s)", methodName, fragments.size());
        return fragments;
    }

    /**
     * Fallback sub-fragmentation for giant single AST statements (e.g. 50,000-character generated switch blocks).
     */
    private int subFragmentSingleStatement(Statement stmt,
                                           String stmtText,
                                           Node declaration,
                                           CompilationUnit cu,
                                           String parentChunkId,
                                           String methodName,
                                           String signature,
                                           int startFragmentIndex,
                                           int originalLength,
                                           List<Statement> previousFragmentStatements,
                                           List<CodeChunk> fragments) {

        int maxFragmentChars = budgetEstimator.getMaxFragmentBudget();
        String[] lines = stmtText.split("\r?\n");

        StringBuilder currentSubBuffer = new StringBuilder();
        int currentFragmentIndex = startFragmentIndex;

        int stmtStartLine = extractor.getStartLine(stmt);
        int stmtEndLine = extractor.getEndLine(stmt);

        for (String line : lines) {
            if (currentSubBuffer.length() > 0 && currentSubBuffer.length() + line.length() > maxFragmentChars) {
                String contextPreamble = extractVariableContext(previousFragmentStatements);
                String fullContent = formatFragmentContent(signature, contextPreamble, currentSubBuffer.toString(), currentFragmentIndex);

                CodeChunk chunk = buildFragmentChunk(declaration, cu, parentChunkId, methodName, signature, fullContent, currentFragmentIndex, originalLength);
                chunk.setStartLine(stmtStartLine);
                chunk.setEndLine(stmtEndLine);
                fragments.add(chunk);

                currentSubBuffer = new StringBuilder();
                currentFragmentIndex++;
            }

            currentSubBuffer.append(line).append("\n");

            // If a single line is absurdly large (> maxFragmentChars), truncate or hard-cut that line safely
            if (currentSubBuffer.length() > maxFragmentChars) {
                String contextPreamble = extractVariableContext(previousFragmentStatements);
                String lineText = currentSubBuffer.substring(0, Math.min(currentSubBuffer.length(), maxFragmentChars));
                String fullContent = formatFragmentContent(signature, contextPreamble, lineText + "\n// ... [sub-fragment line cut]", currentFragmentIndex);

                CodeChunk chunk = buildFragmentChunk(declaration, cu, parentChunkId, methodName, signature, fullContent, currentFragmentIndex, originalLength);
                chunk.setStartLine(stmtStartLine);
                chunk.setEndLine(stmtEndLine);
                fragments.add(chunk);

                currentSubBuffer = new StringBuilder();
                currentFragmentIndex++;
            }
        }

        if (currentSubBuffer.length() > 0) {
            String contextPreamble = extractVariableContext(previousFragmentStatements);
            String fullContent = formatFragmentContent(signature, contextPreamble, currentSubBuffer.toString(), currentFragmentIndex);

            CodeChunk chunk = buildFragmentChunk(declaration, cu, parentChunkId, methodName, signature, fullContent, currentFragmentIndex, originalLength);
            chunk.setStartLine(stmtStartLine);
            chunk.setEndLine(stmtEndLine);
            fragments.add(chunk);

            currentFragmentIndex++;
        }

        return currentFragmentIndex;
    }

    /**
     * Extracts top-level variable declarations ONLY from the immediately preceding fragment.
     */
    private String extractVariableContext(List<Statement> precedingStatements) {
        if (precedingStatements == null || precedingStatements.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (Statement stmt : precedingStatements) {
            if (stmt instanceof ExpressionStmt exprStmt &&
                    exprStmt.getExpression() instanceof VariableDeclarationExpr varExpr) {
                sb.append("    ").append(varExpr.toString()).append(";\n");
            }
        }

        if (sb.length() == 0) {
            return "";
        }

        return "// Declarations from preceding fragment:\n" + sb.toString();
    }

    private String formatFragmentContent(String signature, String contextPreamble, String body, int fragmentIndex) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("// Fragment %d of method\n%s {\n", fragmentIndex, signature));
        if (!contextPreamble.isBlank()) {
            sb.append(contextPreamble);
        }
        sb.append(body);
        sb.append("}\n");
        return sb.toString();
    }

    private CodeChunk buildFragmentChunk(Node declaration,
                                          CompilationUnit cu,
                                          String parentChunkId,
                                          String methodName,
                                          String signature,
                                          String content,
                                          int fragmentIndex,
                                          int originalLength) {

        CodeChunk chunk = new CodeChunk();
        chunk.setType(ChunkType.METHOD_FRAGMENT.name());
        extractor.populateCommonMetadata(chunk, declaration, cu);

        chunk.setParentChunkId(parentChunkId);
        chunk.setMethodName(methodName);
        chunk.setFragmentIndex(fragmentIndex);
        chunk.setSignature(signature);
        chunk.setContent(content);
        chunk.setOriginalElementLength(originalLength);
        chunk.setFragmented(true);

        chunk.setElementName(methodName + "#fragment" + fragmentIndex);

        return chunk;
    }
}
