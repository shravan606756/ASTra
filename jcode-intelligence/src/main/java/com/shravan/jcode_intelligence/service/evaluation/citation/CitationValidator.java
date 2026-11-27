package com.shravan.jcode_intelligence.service.evaluation.citation;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Range;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.*;
import com.shravan.jcode_intelligence.cli.ui.BenchmarkTableFormatter;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Stream;

/**
 * Validates inline citations against git checkouts, filesystem constraints,
 * and Java abstract syntax trees.
 *
 * <p>
 * Validates:
 * <ul>
 * <li>JGit revision checkout and dirty-state reporting. Skips validation for
 * {@code MISMATCHED} or
 * {@code UNAVAILABLE} revisions rather than returning syntax-only
 * validity.</li>
 * <li>Path traversal rejection, symlink-safe real-path containment, regular
 * file existence.</li>
 * <li>Positive ordered line bounds within file line count.</li>
 * <li>Java AST entity verification (classes, interfaces, records, enums,
 * methods, constructors, fields).
 * Distinguishes {@code VALID}, {@code AMBIGUOUS_MATCH}, {@code PARSE_ERROR},
 * and {@code ENTITY_NOT_FOUND}.</li>
 * <li>Non-Java files check existence and bounds, returning
 * {@code NOT_APPLICABLE} for entity status.</li>
 * </ul>
 */
@Component
public class CitationValidator {

    private static final Logger log = LoggerFactory.getLogger(CitationValidator.class);

    private final JavaParser javaParser;

    public CitationValidator(JavaParser javaParser) {
        this.javaParser = (javaParser != null) ? javaParser : createDefaultJavaParser();
    }

    public CitationValidator() {
        this(createDefaultJavaParser());
    }

    private static JavaParser createDefaultJavaParser() {
        ParserConfiguration config = new ParserConfiguration();
        config.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        return new JavaParser(config);
    }

    /**
     * Checks repository git status against expected revision.
     *
     * @param repositoryRoot   path to repository root
     * @param expectedRevision commit hash or tag (nullable)
     * @return revision status
     */
    public RevisionStatus checkRevision(Path repositoryRoot, String expectedRevision) {
        if (repositoryRoot == null || !Files.exists(repositoryRoot) || !Files.isDirectory(repositoryRoot)) {
            return RevisionStatus.UNAVAILABLE;
        }

        Path gitDir = repositoryRoot.resolve(".git");
        if (!Files.exists(gitDir)) {
            return RevisionStatus.UNAVAILABLE;
        }

        try (Git git = Git.open(repositoryRoot.toFile())) {
            Repository repo = git.getRepository();
            ObjectId head = repo.resolve("HEAD");
            if (head == null) {
                return RevisionStatus.UNAVAILABLE;
            }

            if (expectedRevision != null && !expectedRevision.isBlank()) {
                ObjectId expected = repo.resolve(expectedRevision);
                if (expected == null || !head.equals(expected)) {
                    log.info("Repository revision mismatched: HEAD={} expected={}", head.name(), expectedRevision);
                    return RevisionStatus.MISMATCHED;
                }
            }

            Status status = git.status().call();
            if (!status.isClean()) {
                log.info("Repository working tree is dirty: {} uncommitted changes",
                        status.getUncommittedChanges().size());
                return RevisionStatus.DIRTY;
            }

            return RevisionStatus.MATCHED;
        } catch (Exception e) {
            log.warn("JGit failed to inspect repository at {}: {}", repositoryRoot, e.getMessage());
            return RevisionStatus.UNAVAILABLE;
        }
    }

    /**
     * Validates a collection of citations against a target repository root and
     * revision.
     *
     * @param citations        list of citations to validate
     * @param repositoryRoot   repository root directory path
     * @param expectedRevision target revision commit ID (nullable)
     * @return validation results
     */
    public List<CitationValidationResult> validateAll(List<Citation> citations, String repositoryRoot,
            String expectedRevision) {
        if (citations == null || citations.isEmpty()) {
            return List.of();
        }

        Path rootPath = (repositoryRoot != null && !repositoryRoot.isBlank()) ? Paths.get(repositoryRoot) : null;
        RevisionStatus revisionStatus = checkRevision(rootPath, expectedRevision);

        // Skip validation for MISMATCHED, UNAVAILABLE, or DIRTY rather than validating
        // against dirty working tree
        if (revisionStatus == RevisionStatus.MISMATCHED || revisionStatus == RevisionStatus.UNAVAILABLE
                || revisionStatus == RevisionStatus.DIRTY) {
            String msg = "Validation skipped: repository revision is " + revisionStatus;
            return citations.stream()
                    .map(c -> CitationValidationResult.skipped(c, revisionStatus, msg))
                    .toList();
        }

        return citations.stream()
                .map(c -> validateSingle(c, rootPath, revisionStatus))
                .toList();
    }

    /**
     * Validates a single citation against a validated repository checkout.
     */
    public CitationValidationResult validateSingle(Citation citation, Path repositoryRoot,
            RevisionStatus revisionStatus) {
        if (citation == null) {
            return CitationValidationResult.malformed(Citation.malformed(""), revisionStatus, "Citation is null");
        }

        if (!citation.validSyntax()) {
            return CitationValidationResult.malformed(citation, revisionStatus, "Malformed citation syntax: " + citation.rawText());
        }

        if (revisionStatus == RevisionStatus.MISMATCHED || revisionStatus == RevisionStatus.UNAVAILABLE
                || revisionStatus == RevisionStatus.DIRTY) {
            return CitationValidationResult.skipped(citation, revisionStatus,
                    "Validation skipped: repository revision is " + revisionStatus);
        }

        if (citation.startLine() == null || citation.endLine() == null ||
                citation.startLine() <= 0 || citation.endLine() < citation.startLine()) {
            return new CitationValidationResult(
                    citation, false, revisionStatus, FileStatus.OUT_OF_BOUNDS,
                    EntityStatus.NOT_APPLICABLE,
                    "Invalid line bounds: " + citation.startLine() + "-" + citation.endLine());
        }

        String rawPath = citation.filePath();
        if (rawPath == null || rawPath.isBlank()) {
            return new CitationValidationResult(
                    citation, false, revisionStatus, FileStatus.NOT_FOUND,
                    EntityStatus.NOT_APPLICABLE, "File path is empty");
        }

        // Normalize and reject traversal
        String normalizedPath = rawPath.replace('\\', '/').trim();
        boolean hasTraversal = Arrays.stream(normalizedPath.split("/+"))
                .anyMatch(segment -> segment.equals(".."));
        if (hasTraversal || normalizedPath.startsWith("/")) {
            return new CitationValidationResult(
                    citation, false, revisionStatus, FileStatus.TRAVERSAL_REJECTED,
                    EntityStatus.NOT_APPLICABLE, "Path traversal sequence detected: " + rawPath);
        }

        // Clean temporary repository prefixes if present
        String cleanedPath = BenchmarkTableFormatter.cleanRepoPath(normalizedPath);
        if (cleanedPath.equals("-")) {
            cleanedPath = normalizedPath;
        }

        if (repositoryRoot == null) {
            return CitationValidationResult.skipped(citation, revisionStatus, "Repository root is null");
        }

        Path resolved = repositoryRoot.resolve(cleanedPath).normalize();
        if (!resolved.startsWith(repositoryRoot.normalize())) {
            return new CitationValidationResult(
                    citation, false, revisionStatus, FileStatus.TRAVERSAL_REJECTED,
                    EntityStatus.NOT_APPLICABLE, "Resolved path escapes repository root: " + resolved);
        }

        if (!Files.exists(resolved)) {
            return new CitationValidationResult(
                    citation, false, revisionStatus, FileStatus.NOT_FOUND,
                    EntityStatus.NOT_APPLICABLE, "File does not exist on disk: " + cleanedPath);
        }

        Path realTarget;
        Path realRoot;
        try {
            realTarget = resolved.toRealPath();
            realRoot = repositoryRoot.toRealPath();
        } catch (IOException e) {
            return new CitationValidationResult(
                    citation, false, revisionStatus, FileStatus.NOT_FOUND,
                    EntityStatus.NOT_APPLICABLE, "Failed to resolve real path: " + e.getMessage());
        }

        // Symlink escape check
        if (!realTarget.startsWith(realRoot)) {
            return new CitationValidationResult(
                    citation, false, revisionStatus, FileStatus.TRAVERSAL_REJECTED,
                    EntityStatus.NOT_APPLICABLE, "Symlink target escapes repository root: " + realTarget);
        }

        if (!Files.isRegularFile(realTarget)) {
            return new CitationValidationResult(
                    citation, false, revisionStatus, FileStatus.NOT_FOUND,
                    EntityStatus.NOT_APPLICABLE, "Target is not a regular file: " + realTarget);
        }

        // Check line bounds against actual file line count
        long totalLines;
        try (Stream<String> lines = Files.lines(realTarget)) {
            totalLines = lines.count();
        } catch (IOException e) {
            return new CitationValidationResult(
                    citation, false, revisionStatus, FileStatus.NOT_FOUND,
                    EntityStatus.NOT_APPLICABLE, "Failed to read file lines: " + e.getMessage());
        }

        if (citation.endLine() > totalLines) {
            return new CitationValidationResult(
                    citation, false, revisionStatus, FileStatus.OUT_OF_BOUNDS,
                    EntityStatus.NOT_APPLICABLE,
                    "Citation endLine (" + citation.endLine() + ") exceeds total file lines (" + totalLines + ")");
        }

        // For non-Java files: bounds verified, entity check not applicable
        if (!cleanedPath.toLowerCase(Locale.ROOT).endsWith(".java")) {
            return new CitationValidationResult(
                    citation, true, revisionStatus, FileStatus.VALID,
                    EntityStatus.NOT_APPLICABLE, "Non-Java file bounds verified");
        }

        // For Java files: parse AST and verify declaration containment
        return validateJavaEntity(citation, realTarget, revisionStatus);
    }

    private CitationValidationResult validateJavaEntity(Citation citation, Path realTarget,
            RevisionStatus revisionStatus) {
        ParseResult<CompilationUnit> parseResult;
        try {
            parseResult = javaParser.parse(realTarget);
        } catch (Exception e) {
            return new CitationValidationResult(
                    citation, false, revisionStatus, FileStatus.VALID,
                    EntityStatus.PARSE_ERROR, "Failed to parse Java file: " + e.getMessage());
        }

        if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) {
            return new CitationValidationResult(
                    citation, false, revisionStatus, FileStatus.VALID,
                    EntityStatus.PARSE_ERROR, "JavaParser syntax errors: " + parseResult.getProblems());
        }

        if (!citation.hasSymbol()) {
            return new CitationValidationResult(
                    citation, true, revisionStatus, FileStatus.VALID,
                    EntityStatus.NOT_APPLICABLE, "Symbol-free range verified in valid Java file");
        }

        CompilationUnit cu = parseResult.getResult().get();
        String symbol = citation.symbol().trim();

        List<Node> matchingNodes = new ArrayList<>();

        cu.walk(node -> {
            if (isSupportedDeclaration(node)) {
                if (matchesSymbol(node, symbol)) {
                    Range r = node.getRange().orElse(null);
                    if (r != null) {
                        int declStart = r.begin.line;
                        int declEnd = r.end.line;
                        // Full range containment: citation must contain declaration
                        if (citation.startLine() <= declStart && declEnd <= citation.endLine()) {
                            matchingNodes.add(node);
                        }
                    }
                }
            }
        });

        if (matchingNodes.isEmpty()) {
            return new CitationValidationResult(
                    citation, false, revisionStatus, FileStatus.VALID,
                    EntityStatus.ENTITY_NOT_FOUND,
                    "Symbol '" + symbol + "' not found within range " + citation.startLine() + "-"
                            + citation.endLine());
        }

        if (matchingNodes.size() > 1) {
            return new CitationValidationResult(
                    citation, false, revisionStatus, FileStatus.VALID,
                    EntityStatus.AMBIGUOUS_MATCH,
                    "Found " + matchingNodes.size() + " declarations matching '" + symbol + "' in cited range");
        }

        return new CitationValidationResult(
                citation, true, revisionStatus, FileStatus.VALID,
                EntityStatus.VALID,
                "Declaration '" + symbol + "' verified within range " + citation.startLine() + "-"
                        + citation.endLine());
    }

    private boolean isSupportedDeclaration(Node node) {
        return node instanceof ClassOrInterfaceDeclaration
                || node instanceof RecordDeclaration
                || node instanceof EnumDeclaration
                || node instanceof MethodDeclaration
                || node instanceof ConstructorDeclaration
                || node instanceof FieldDeclaration;
    }

    private boolean matchesSymbol(Node node, String symbol) {
        if (node instanceof FieldDeclaration fieldDecl) {
            for (VariableDeclarator var : fieldDecl.getVariables()) {
                if (var.getNameAsString().equals(symbol)) {
                    return true;
                }
            }
            return false;
        }
        if (node instanceof ClassOrInterfaceDeclaration decl) {
            return decl.getNameAsString().equals(symbol)
                    || decl.getFullyQualifiedName().map(fqn -> fqn.equals(symbol) || fqn.endsWith("." + symbol))
                            .orElse(false);
        }
        if (node instanceof RecordDeclaration decl) {
            return decl.getNameAsString().equals(symbol);
        }
        if (node instanceof EnumDeclaration decl) {
            return decl.getNameAsString().equals(symbol);
        }
        if (node instanceof MethodDeclaration decl) {
            return decl.getNameAsString().equals(symbol)
                    || symbol.equals(decl.getDeclarationAsString(false, false, false))
                    || symbol.endsWith("#" + decl.getNameAsString())
                    || symbol.endsWith("." + decl.getNameAsString());
        }
        if (node instanceof ConstructorDeclaration decl) {
            return decl.getNameAsString().equals(symbol)
                    || symbol.endsWith("#" + decl.getNameAsString())
                    || symbol.endsWith("." + decl.getNameAsString());
        }
        return false;
    }
}
