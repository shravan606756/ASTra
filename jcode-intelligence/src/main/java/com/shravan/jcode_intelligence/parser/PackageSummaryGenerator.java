package com.shravan.jcode_intelligence.parser;

import com.shravan.jcode_intelligence.model.ChunkType;
import com.shravan.jcode_intelligence.model.CodeChunk;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Generates repository-wide package summaries after all file-level chunks have been generated,
 * or incrementally via an {@link Accumulator} for streaming pipelines.
 */
@Component
public class PackageSummaryGenerator {

    public static final int MAX_PACKAGE_TYPES = 100;

    /**
     * Generates exactly one PACKAGE summary chunk for each unique package present in the repository chunks.
     *
     * @param repositoryChunks all generated chunks for the repository
     * @return list of package summary chunks
     */
    public List<CodeChunk> generatePackageSummaries(List<CodeChunk> repositoryChunks) {
        if (repositoryChunks == null || repositoryChunks.isEmpty()) {
            return List.of();
        }

        Accumulator accumulator = new Accumulator();
        accumulator.accumulate(repositoryChunks);
        return accumulator.generateSummaries();
    }

    /**
     * Creates a new stateful {@link Accumulator} for incremental streaming package summary generation.
     *
     * @return a fresh accumulator instance
     */
    public Accumulator createAccumulator() {
        return new Accumulator();
    }

    /**
     * Entry representing a top-level type and its identifier for package summary content.
     */
    public record TypeEntry(String type, String elementName) {}

    /**
     * Incremental accumulator for tracking package metadata across streaming chunk waves.
     */
    public static class Accumulator {
        private final Map<String, PackageSummaryData> packageDataMap = new LinkedHashMap<>();

        public void accumulate(List<CodeChunk> chunks) {
            if (chunks == null || chunks.isEmpty()) {
                return;
            }
            for (CodeChunk chunk : chunks) {
                accumulate(chunk);
            }
        }

        public void accumulate(CodeChunk chunk) {
            if (chunk == null) {
                return;
            }
            String pkg = chunk.getPackageName();
            if (pkg == null || pkg.isBlank()) {
                return;
            }

            PackageSummaryData data = packageDataMap.computeIfAbsent(pkg, PackageSummaryData::new);

            if (isTypeChunk(chunk.getType()) && chunk.getNestingDepth() == 0) {
                data.addType(chunk.getType(), chunk.getElementName());
            }
        }

        public List<CodeChunk> generateSummaries() {
            List<CodeChunk> packageChunks = new ArrayList<>(packageDataMap.size());
            for (PackageSummaryData data : packageDataMap.values()) {
                packageChunks.add(buildPackageSummaryChunk(data.packageName, data.totalTopLevelTypes, data.first100Entries, data.typeCounts));
            }
            return packageChunks;
        }

        public Map<String, PackageSummaryData> getPackageDataMap() {
            return Collections.unmodifiableMap(packageDataMap);
        }
    }

    /**
     * Aggregated state for an individual package.
     */
    public static class PackageSummaryData {
        private final String packageName;
        private int totalTopLevelTypes = 0;
        private final List<TypeEntry> first100Entries = new ArrayList<>();
        private final Map<String, Long> typeCounts = new LinkedHashMap<>();

        public PackageSummaryData(String packageName) {
            this.packageName = packageName;
        }

        public void addType(String type, String elementName) {
            totalTopLevelTypes++;
            typeCounts.merge(type, 1L, Long::sum);
            if (first100Entries.size() < MAX_PACKAGE_TYPES) {
                first100Entries.add(new TypeEntry(type, elementName));
            }
        }

        public String getPackageName() {
            return packageName;
        }

        public int getTotalTopLevelTypes() {
            return totalTopLevelTypes;
        }

        public List<TypeEntry> getFirst100Entries() {
            return Collections.unmodifiableList(first100Entries);
        }

        public Map<String, Long> getTypeCounts() {
            return Collections.unmodifiableMap(typeCounts);
        }
    }

    /**
     * Shared construction of package summary chunk content and metadata.
     */
    public static CodeChunk buildPackageSummaryChunk(
            String packageName,
            int totalTopLevelTypes,
            List<TypeEntry> topEntries,
            Map<String, Long> typeCounts
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("Package: ").append(packageName).append("\n\n");

        sb.append("Type Summary (Total ").append(totalTopLevelTypes).append(" top-level types):\n");
        int count = 0;
        for (TypeEntry entry : topEntries) {
            if (count >= MAX_PACKAGE_TYPES) {
                break;
            }
            String line = "  - " + entry.type() + " " + entry.elementName() + "\n";
            // Check character budget: reserve space for omitted notice and type counts footer
            if (sb.length() + line.length() > 7500) {
                break;
            }
            sb.append(line);
            count++;
        }

        if (totalTopLevelTypes > count) {
            sb.append(String.format("  // ... %d additional top-level types omitted from package summary\n",
                    totalTopLevelTypes - count));
        }

        sb.append("\nType Counts:\n");
        typeCounts.forEach((type, typeCount) ->
                sb.append("  ").append(type).append(": ").append(typeCount).append("\n"));

        String content = sb.toString();

        CodeChunk chunk = new CodeChunk();
        chunk.setId("package:" + packageName + "::PACKAGE");
        chunk.setType(ChunkType.PACKAGE.name());
        chunk.setPackageName(packageName);
        chunk.setClassName("");
        chunk.setElementName(packageName);
        chunk.setFilePath("");
        chunk.setStartLine(1);
        chunk.setEndLine(1);
        chunk.setContent(content);
        chunk.setLanguage("Java");
        chunk.setSummarized(true);
        chunk.setFragmented(false);

        return chunk;
    }

    private static boolean isTypeChunk(String type) {
        return ChunkType.CLASS.name().equals(type)
                || ChunkType.INTERFACE.name().equals(type)
                || ChunkType.ENUM.name().equals(type)
                || ChunkType.RECORD.name().equals(type);
    }
}
