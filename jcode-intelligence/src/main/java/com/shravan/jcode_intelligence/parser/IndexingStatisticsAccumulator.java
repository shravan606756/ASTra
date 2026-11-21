package com.shravan.jcode_intelligence.parser;

import com.shravan.jcode_intelligence.model.ChunkType;
import com.shravan.jcode_intelligence.model.CodeChunk;
import com.shravan.jcode_intelligence.model.IndexingStatistics;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Statefully accumulates indexing diagnostics across streaming chunk waves.
 *
 * <p>Tracks distinct packages, elemental type counts, total chunks (including package summaries),
 * and largest class/method with strict tie handling (earlier encounter retained on tie).
 */
public class IndexingStatisticsAccumulator {

    private final Set<String> uniquePackages = new HashSet<>();
    private int classes = 0;
    private int interfaces = 0;
    private int enums = 0;
    private int records = 0;
    private int fields = 0;
    private int constructors = 0;
    private int methods = 0;
    private int fragments = 0;
    private int packageSummaries = 0;
    private int totalChunks = 0;

    private String largestClassName = "N/A";
    private int largestClassSize = 0;

    private String largestMethodName = "N/A";
    private int largestMethodSize = 0;

    /**
     * Accumulates a list of code chunks into diagnostic metrics.
     *
     * @param chunks chunks to accumulate
     */
    public void accumulate(List<CodeChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        for (CodeChunk chunk : chunks) {
            accumulate(chunk);
        }
    }

    /**
     * Accumulates an individual code chunk into diagnostic metrics.
     *
     * @param chunk chunk to accumulate
     */
    public void accumulate(CodeChunk chunk) {
        if (chunk == null) {
            return;
        }

        totalChunks++;

        if (chunk.getPackageName() != null && !chunk.getPackageName().isBlank()) {
            uniquePackages.add(chunk.getPackageName());
        }

        String type = chunk.getType();
        if (ChunkType.PACKAGE.name().equals(type)) {
            packageSummaries++;
        } else if (ChunkType.CLASS.name().equals(type)) {
            classes++;
            if (chunk.getOriginalElementLength() > largestClassSize) {
                largestClassSize = chunk.getOriginalElementLength();
                largestClassName = chunk.getClassName();
            }
        } else if (ChunkType.INTERFACE.name().equals(type)) {
            interfaces++;
        } else if (ChunkType.ENUM.name().equals(type)) {
            enums++;
        } else if (ChunkType.RECORD.name().equals(type)) {
            records++;
        } else if (ChunkType.FIELD.name().equals(type)) {
            fields++;
        } else if (ChunkType.CONSTRUCTOR.name().equals(type)) {
            constructors++;
        } else if (ChunkType.METHOD.name().equals(type)) {
            methods++;
            if (chunk.getOriginalElementLength() > largestMethodSize) {
                largestMethodSize = chunk.getOriginalElementLength();
                largestMethodName = chunk.getClassName() + "#" + chunk.getElementName();
            }
        } else if (ChunkType.METHOD_FRAGMENT.name().equals(type)) {
            fragments++;
            if (chunk.getOriginalElementLength() > largestMethodSize) {
                largestMethodSize = chunk.getOriginalElementLength();
                largestMethodName = chunk.getClassName() + "#" + chunk.getMethodName();
            }
        }
    }

    /**
     * Builds an immutable {@link IndexingStatistics} snapshot with the supplied total indexing duration.
     *
     * @param durationMs total indexing duration in milliseconds
     * @return populated statistics summary
     */
    public IndexingStatistics build(long durationMs) {
        IndexingStatistics stats = new IndexingStatistics();
        stats.setIndexingDurationMs(durationMs);
        stats.setPackages(uniquePackages.size());
        stats.setClasses(classes);
        stats.setInterfaces(interfaces);
        stats.setEnums(enums);
        stats.setRecords(records);
        stats.setFields(fields);
        stats.setConstructors(constructors);
        stats.setMethods(methods);
        stats.setFragments(fragments);
        stats.setTotalChunks(totalChunks);
        stats.setLargestClassName(largestClassName);
        stats.setLargestClassSize(largestClassSize);
        stats.setLargestMethodName(largestMethodName);
        stats.setLargestMethodSize(largestMethodSize);
        return stats;
    }

    public int getTotalChunks() {
        return totalChunks;
    }

    public int getPackages() {
        return uniquePackages.size();
    }

    public int getClasses() {
        return classes;
    }

    public int getMethods() {
        return methods;
    }
}
