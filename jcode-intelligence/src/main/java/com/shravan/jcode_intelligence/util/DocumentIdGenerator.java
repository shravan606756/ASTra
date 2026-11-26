package com.shravan.jcode_intelligence.util;

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
