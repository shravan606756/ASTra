package com.shravan.jcode_intelligence.dto.response;

import java.util.ArrayList;
import java.util.List;

/**
 * Consolidated source representation grouping multiple code chunks and coordinate ranges
 * under a single file and class header.
 */
public class GroupedSourceResponse {

    private String filePath;
    private String repositoryId;
    private String className;
    private List<String> lineRanges = new ArrayList<>();
    private List<ChunkResponse> chunks = new ArrayList<>();

    /**
     * Default constructor for Jackson and serialization frameworks.
     */
    public GroupedSourceResponse() {
    }

    /**
     * Constructs a grouped source response with coordinate metadata.
     *
     * @param filePath     normalized relative file path
     * @param repositoryId repository identifier
     * @param className    primary class or element name
     */
    public GroupedSourceResponse(String filePath, String repositoryId, String className) {
        this.filePath = filePath;
        this.repositoryId = repositoryId;
        this.className = className;
    }

    /**
     * Constructs a grouped source response with coordinate metadata, line ranges, and chunk list.
     *
     * @param filePath     normalized relative file path
     * @param repositoryId repository identifier
     * @param className    primary class or element name
     * @param lineRanges   list of formatted line ranges
     * @param chunks       list of granular chunk responses
     */
    public GroupedSourceResponse(String filePath, String repositoryId, String className,
                                 List<String> lineRanges, List<ChunkResponse> chunks) {
        this.filePath = filePath;
        this.repositoryId = repositoryId;
        this.className = className;
        this.lineRanges = (lineRanges != null) ? lineRanges : new ArrayList<>();
        this.chunks = (chunks != null) ? chunks : new ArrayList<>();
    }

    /**
     * Returns the normalized relative file path.
     *
     * @return normalized file path
     */
    public String getFilePath() {
        return filePath;
    }

    /**
     * Sets the normalized relative file path.
     *
     * @param filePath normalized file path
     */
    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    /**
     * Returns the repository identifier.
     *
     * @return repository ID
     */
    public String getRepositoryId() {
        return repositoryId;
    }

    /**
     * Sets the repository identifier.
     *
     * @param repositoryId repository ID
     */
    public void setRepositoryId(String repositoryId) {
        this.repositoryId = repositoryId;
    }

    /**
     * Returns the primary class or element name for this source file.
     *
     * @return class name
     */
    public String getClassName() {
        return className;
    }

    /**
     * Sets the primary class or element name for this source file.
     *
     * @param className class name
     */
    public void setClassName(String className) {
        this.className = className;
    }

    /**
     * Returns formatted line ranges (e.g., ["10-25", "30-50"]) of chunks in this file.
     *
     * @return list of line range strings
     */
    public List<String> getLineRanges() {
        return lineRanges;
    }

    /**
     * Sets formatted line ranges of chunks in this file.
     *
     * @param lineRanges list of line range strings
     */
    public void setLineRanges(List<String> lineRanges) {
        this.lineRanges = lineRanges;
    }

    /**
     * Returns the granular chunk responses associated with this source file.
     *
     * @return list of chunk responses
     */
    public List<ChunkResponse> getChunks() {
        return chunks;
    }

    /**
     * Sets the granular chunk responses associated with this source file.
     *
     * @param chunks list of chunk responses
     */
    public void setChunks(List<ChunkResponse> chunks) {
        this.chunks = chunks;
    }
}

