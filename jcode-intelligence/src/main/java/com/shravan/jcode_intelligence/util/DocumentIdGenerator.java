package com.shravan.jcode_intelligence.util;

import com.shravan.jcode_intelligence.model.CodeChunk;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Utility for generating deterministic document identifiers.
 *
 * <p>Produces an RFC-4122 type 3 deterministic UUID string from repository coordinates
 * and chunk identity. Identical coordinates produce the same UUID across re-indexing runs,
 * streaming passes, vector storage, and Lucene sparse index documents.
 */
public final class DocumentIdGenerator {

    private DocumentIdGenerator() {}

    /**
     * Normalizes a file path for deterministic seed construction, replacing backslashes with
     * forward slashes and stripping leading slashes and whitespace.
     *
     * @param path file path
     * @return normalized path, or empty string if null/blank
     */
    public static String normalizeFilePath(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        String normalized = path.replace('\\', '/').trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    /**
     * Generates a unique, deterministic RFC-4122 type 3 UUID from chunk coordinates, disambiguating
     * overloaded methods, same-named classes in different files, and method fragments.
     *
     * @param repositoryId   repository identifier (nullable, defaults to "default")
     * @param filePath       source file path (nullable)
     * @param chunkId         stable chunk identifier (nullable)
     * @param signature      method/type signature (nullable)
     * @param startLine      element start line number
     * @param endLine        element end line number
     * @param fragmentIndex  fragment sequence index for fragmented methods (nullable)
     * @param content        fallback content when other disambiguating coordinates are absent
     * @return deterministic UUID string
     */
    public static String generateDeterministicId(
            String repositoryId,
            String filePath,
            String chunkId,
            String signature,
            int startLine,
            int endLine,
            Integer fragmentIndex,
            String content) {
        String repo = (repositoryId != null && !repositoryId.isBlank()) ? repositoryId.trim() : "default";
        String normPath = normalizeFilePath(filePath);
        String safeChunkId = (chunkId != null && !chunkId.isBlank()) ? chunkId.trim() : "";
        String safeSig = (signature != null && !signature.isBlank()) ? signature.trim() : "";
        String lineRange = (startLine > 0 || endLine > 0) ? (startLine + "-" + endLine) : "";
        String frag = (fragmentIndex != null) ? String.valueOf(fragmentIndex) : "";

        // Backward compatibility: if no disambiguating fields are provided, preserve simple seed
        if (normPath.isEmpty() && safeSig.isEmpty() && lineRange.isEmpty() && frag.isEmpty()) {
            return generateDeterministicId(repo, safeChunkId, content);
        }

        StringBuilder seed = new StringBuilder(repo);
        if (!normPath.isEmpty()) {
            seed.append(':').append(normPath);
        }
        if (!safeChunkId.isEmpty()) {
            seed.append(':').append(safeChunkId);
        }
        if (!safeSig.isEmpty()) {
            seed.append(':').append(safeSig);
        }
        if (!lineRange.isEmpty()) {
            seed.append(':').append(lineRange);
        }
        if (!frag.isEmpty()) {
            seed.append(":frag").append(frag);
        }

        return UUID.nameUUIDFromBytes(seed.toString().getBytes(StandardCharsets.UTF_8)).toString();
    }

    /**
     * Generates a deterministic UUID directly from a {@link CodeChunk}.
     *
     * @param chunk code chunk
     * @return deterministic UUID string
     */
    public static String generateDeterministicId(CodeChunk chunk) {
        if (chunk == null) {
            return generateDeterministicId("default", null, "");
        }
        String repoId = (chunk.getRepositoryId() == null || chunk.getRepositoryId().isBlank())
                ? "default" : chunk.getRepositoryId();
        String chunkId = chunk.getId();
        if ((chunkId == null || chunkId.isBlank()) && chunk.getParentChunkId() != null && !chunk.getParentChunkId().isBlank()) {
            String elem = chunk.getElementName() != null ? chunk.getElementName() : "";
            chunkId = chunk.getParentChunkId() + "::" + (chunk.getType() != null ? chunk.getType() : "CHUNK") + "::" + elem;
        }
        return generateDeterministicId(
                repoId,
                chunk.getFilePath(),
                chunkId,
                chunk.getSignature(),
                chunk.getStartLine(),
                chunk.getEndLine(),
                chunk.getFragmentIndex(),
                chunk.getContent()
        );
    }

    /**
     * Generates an RFC-4122 type 3 deterministic UUID string from repository coordinates and chunk identity.
     * Identical inputs produce the same ID across indexing attempts and network retries.
     * A nonblank chunk ID takes precedence over content. Nonblank repository and chunk IDs are
     * used unchanged (not trimmed); a null or blank repository ID uses {@code default}.
     *
     * @param repositoryId repository identifier
     * @param chunkId      stable chunk identifier (nullable)
     * @param content      fallback when chunkId is null or blank; null becomes an empty string
     * @return deterministic UUID string
     */
    public static String generateDeterministicId(String repositoryId, String chunkId, String content) {
        String repo = (repositoryId != null && !repositoryId.isBlank()) ? repositoryId : "default";
        String seed;
        if (chunkId != null && !chunkId.isBlank()) {
            seed = repo + ":" + chunkId;
        } else {
            seed = repo + ":" + (content != null ? content : "");
        }
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
