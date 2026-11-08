package com.shravan.jcode_intelligence.model;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Self-contained representation of an embedded code chunk staged in the {@code EmbeddingQueue}.
 * Holds no references to AST nodes, {@code CompilationUnit} objects, or raw HTTP client contexts.
 *
 * @param id              deterministic unique identifier (RFC-4122 UUID string)
 * @param jobId           identifier of the indexing job owning this record
 * @param repositoryId    target repository identifier
 * @param content         text content of the code chunk
 * @param metadata        sanitized metadata attributes for vector store JSON serialization
 * @param embedding       dense floating-point vector embedding
 * @param acknowledgement future resolved when the record has been successfully committed to PGVector
 */
public record EmbeddedRecord(
        String id,
        String jobId,
        String repositoryId,
        String content,
        Map<String, Object> metadata,
        float[] embedding,
        CompletableFuture<Void> acknowledgement
) {

    /**
     * Validates the identity fields. A null metadata map becomes empty; otherwise the
     * map is exposed through an unmodifiable view, not copied. A null acknowledgement
     * is replaced with a new future.
     *
     * @throws IllegalArgumentException if the id, job id, or repository id is null or blank
     */
    public EmbeddedRecord {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Record id must not be null or blank");
        }
        if (jobId == null || jobId.isBlank()) {
            throw new IllegalArgumentException("Job id must not be null or blank");
        }
        if (repositoryId == null || repositoryId.isBlank()) {
            throw new IllegalArgumentException("Repository id must not be null or blank");
        }
        metadata = (metadata != null) ? Collections.unmodifiableMap(metadata) : Collections.emptyMap();
        acknowledgement = (acknowledgement != null) ? acknowledgement : new CompletableFuture<>();
    }

    /**
     * Generates an RFC-4122 type 3 deterministic UUID string from repository coordinates and chunk identity.
     * Identical inputs produce the same ID across indexing attempts and network retries.
     * A nonblank chunk ID takes precedence over content. Repository and chunk IDs are
     * trimmed; a null or blank repository ID uses {@code default}.
     *
     * @param repositoryId repository identifier
     * @param chunkId      stable chunk identifier (nullable)
     * @param content      fallback when chunkId is null or blank; null becomes an empty string
     * @return deterministic UUID string
     */
    public static String generateDeterministicId(String repositoryId, String chunkId, String content) {
        String repo = (repositoryId != null && !repositoryId.isBlank()) ? repositoryId.trim() : "default";
        String seed;
        if (chunkId != null && !chunkId.isBlank()) {
            seed = repo + ":" + chunkId.trim();
        } else {
            seed = repo + ":" + (content != null ? content : "");
        }
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
    }
}

