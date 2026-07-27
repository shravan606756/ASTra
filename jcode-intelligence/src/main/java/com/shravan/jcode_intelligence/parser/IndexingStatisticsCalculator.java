package com.shravan.jcode_intelligence.parser;

import com.shravan.jcode_intelligence.model.ChunkType;
import com.shravan.jcode_intelligence.model.CodeChunk;
import com.shravan.jcode_intelligence.model.IndexingStatistics;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Computes diagnostic {@link IndexingStatistics} from a repository's full set of generated chunks.
 */
@Component
public class IndexingStatisticsCalculator {

    public IndexingStatistics calculate(List<CodeChunk> allChunks, long durationMs) {
        IndexingStatistics stats = new IndexingStatistics();
        stats.setIndexingDurationMs(durationMs);

        if (allChunks == null || allChunks.isEmpty()) {
            return stats;
        }

        Set<String> uniquePackages = new HashSet<>();
        int classes = 0;
        int interfaces = 0;
        int enums = 0;
        int records = 0;
        int fields = 0;
        int constructors = 0;
        int methods = 0;
        int fragments = 0;
        int packageSummaries = 0;

        String largestClassName = "N/A";
        int largestClassSize = 0;

        String largestMethodName = "N/A";
        int largestMethodSize = 0;

        for (CodeChunk chunk : allChunks) {
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

        stats.setPackages(uniquePackages.size());
        stats.setClasses(classes);
        stats.setInterfaces(interfaces);
        stats.setEnums(enums);
        stats.setRecords(records);
        stats.setFields(fields);
        stats.setConstructors(constructors);
        stats.setMethods(methods);
        stats.setFragments(fragments);
        stats.setTotalChunks(allChunks.size());

        stats.setLargestClassName(largestClassName);
        stats.setLargestClassSize(largestClassSize);

        stats.setLargestMethodName(largestMethodName);
        stats.setLargestMethodSize(largestMethodSize);

        return stats;
    }
}
