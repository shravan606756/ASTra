package com.shravan.jcode_intelligence.service;

import com.shravan.jcode_intelligence.cli.ui.BenchmarkTableFormatter;
import com.shravan.jcode_intelligence.dto.response.ChunkResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * Validates that source coordinates (filePath, startLine, endLine) in {@link ChunkResponse}
 * instances represent well-formed coordinate bounds and point to real, accessible files
 * when a local repository root is available.
 */
@Component
public class SourceLocationValidator {

    private static final Logger log = LoggerFactory.getLogger(SourceLocationValidator.class);

    /**
     * Result of verifying a chunk's source location coordinates and filesystem existence.
     *
     * @param chunk        target chunk being verified
     * @param valid        whether the source location is valid
     * @param reason       detailed reason or diagnostic message
     * @param existsOnDisk whether the file was physically located on disk
     * @param linesValid   whether line coordinates are within physical file bounds
     */
    public record LocationValidationResult(
            ChunkResponse chunk,
            boolean valid,
            String reason,
            boolean existsOnDisk,
            boolean linesValid
    ) {}

    /**
     * Validates a collection of chunk responses against a specified repository root directory.
     *
     * @param chunks         list of chunk responses
     * @param repositoryRoot repository base directory path (nullable)
     * @return list of validation results
     */
    public List<LocationValidationResult> validateAll(List<ChunkResponse> chunks, String repositoryRoot) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        List<LocationValidationResult> results = new ArrayList<>();
        for (ChunkResponse chunk : chunks) {
            results.add(validate(chunk, repositoryRoot));
        }
        return results;
    }

    /**
     * Validates a single chunk response against a repository root directory.
     *
     * @param chunk          chunk response to validate
     * @param repositoryRoot repository base directory path (nullable)
     * @return validation result
     */
    public LocationValidationResult validate(ChunkResponse chunk, String repositoryRoot) {
        if (chunk == null) {
            return new LocationValidationResult(null, false, "Chunk is null", false, false);
        }

        // 1. Synthetic chunk exemption (e.g. PACKAGE summary with no physical file)
        if ("PACKAGE".equalsIgnoreCase(chunk.getType()) && (chunk.getFilePath() == null || chunk.getFilePath().isBlank())) {
            return new LocationValidationResult(chunk, true, "Synthetic chunk exempted from filesystem verification", false, true);
        }

        String rawPath = chunk.getFilePath();
        if (rawPath == null || rawPath.isBlank()) {
            return new LocationValidationResult(chunk, false, "File path is empty or blank", false, false);
        }

        // 2. Normalize and check for security violations (directory traversal)
        String normalizedPath = rawPath.replace('\\', '/').trim();
        boolean hasTraversal = Arrays.stream(normalizedPath.split("/+"))
                .anyMatch(segment -> segment.equals(".."));
        if (hasTraversal) {
            return new LocationValidationResult(chunk, false, "Path contains illegal directory traversal sequence: " + rawPath, false, false);
        }

        // Clean temporary repository prefixes if present
        String cleanedPath = BenchmarkTableFormatter.cleanRepoPath(normalizedPath);
        if (cleanedPath.equals("-")) {
            cleanedPath = normalizedPath;
        }

        // 3. Line number sanity check
        int startLine = chunk.getStartLine();
        int endLine = chunk.getEndLine();
        if (startLine <= 0) {
            return new LocationValidationResult(chunk, false, "Invalid startLine: " + startLine + " (must be positive)", false, false);
        }
        if (endLine < startLine) {
            return new LocationValidationResult(chunk, false, "Invalid line range: endLine (" + endLine + ") precedes startLine (" + startLine + ")", false, false);
        }

        // 4. Filesystem verification if repositoryRoot is provided and accessible
        if (repositoryRoot != null && !repositoryRoot.isBlank()) {
            Path rootPath = Paths.get(repositoryRoot);
            if (Files.exists(rootPath) && Files.isDirectory(rootPath)) {
                Path targetFile = rootPath.resolve(cleanedPath).normalize();

                // Prevent path traversal outside root
                if (!targetFile.startsWith(rootPath.normalize())) {
                    return new LocationValidationResult(chunk, false, "Resolved path escapes repository root: " + targetFile, false, false);
                }

                if (!Files.exists(targetFile) || !Files.isRegularFile(targetFile)) {
                    return new LocationValidationResult(chunk, false, "Source file does not exist on disk: " + targetFile, false, false);
                }

                // Check line bounds against actual file line count
                try (Stream<String> lines = Files.lines(targetFile)) {
                    long totalLines = lines.count();
                    if (endLine > totalLines) {
                        return new LocationValidationResult(chunk, false,
                                String.format("endLine (%d) exceeds total file lines (%d) in %s", endLine, totalLines, targetFile.getFileName()),
                                true, false);
                    }
                    return new LocationValidationResult(chunk, true, "Source location verified on disk", true, true);
                } catch (IOException | UncheckedIOException e) {
                    log.warn("Failed to read file lines for validation: {}", targetFile, e);
                    return new LocationValidationResult(chunk, false, "Failed to read file lines: " + e.getMessage(), true, false);
                }
            }
        }

        // When repository root is not provided or not locally on disk (e.g. remote git clones cleaned up post-indexing)
        return new LocationValidationResult(chunk, true, "Valid coordinate syntax (repository root not available locally)", false, true);
    }
}

