package com.shravan.jcode_intelligence.parser;

import com.shravan.jcode_intelligence.model.ChunkType;
import com.shravan.jcode_intelligence.model.CodeChunk;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Generates repository-wide package summaries after all file-level chunks have been generated.
 */
@Component
public class PackageSummaryGenerator {

    private static final int MAX_PACKAGE_TYPES = 100;

    /**
     * Generates exactly one PACKAGE summary chunk for each unique package present in the repository chunks.
     */
    public List<CodeChunk> generatePackageSummaries(List<CodeChunk> repositoryChunks) {
        if (repositoryChunks == null || repositoryChunks.isEmpty()) {
            return List.of();
        }

        Map<String, List<CodeChunk>> packageMap = repositoryChunks.stream()
                .filter(c -> c.getPackageName() != null && !c.getPackageName().isBlank())
                .collect(Collectors.groupingBy(CodeChunk::getPackageName));

        List<CodeChunk> packageChunks = new ArrayList<>();

        for (Map.Entry<String, List<CodeChunk>> entry : packageMap.entrySet()) {
            String packageName = entry.getKey();
            List<CodeChunk> pkgChunks = entry.getValue();

            String content = buildPackageSummaryContent(packageName, pkgChunks);

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

            packageChunks.add(chunk);
        }

        return packageChunks;
    }

    private String buildPackageSummaryContent(String packageName, List<CodeChunk> pkgChunks) {
        StringBuilder sb = new StringBuilder();
        sb.append("Package: ").append(packageName).append("\n\n");

        List<CodeChunk> typeChunks = pkgChunks.stream()
                .filter(c -> isTypeChunk(c.getType()))
                .filter(c -> c.getNestingDepth() == 0)
                .toList();

        Map<String, Long> typeCounts = typeChunks.stream()
                .collect(Collectors.groupingBy(CodeChunk::getType, Collectors.counting()));

        sb.append("Type Summary (Total ").append(typeChunks.size()).append(" top-level types):\n");
        int count = 0;
        for (CodeChunk typeChunk : typeChunks) {
            if (count >= MAX_PACKAGE_TYPES) {
                sb.append(String.format("  // ... %d additional top-level types omitted from package summary\n",
                        typeChunks.size() - count));
                break;
            }
            sb.append("  - ").append(typeChunk.getType())
                    .append(" ").append(typeChunk.getElementName()).append("\n");
            count++;
        }

        sb.append("\nType Counts:\n");
        typeCounts.forEach((type, typeCount) ->
                sb.append("  ").append(type).append(": ").append(typeCount).append("\n"));

        return sb.toString();
    }

    private boolean isTypeChunk(String type) {
        return ChunkType.CLASS.name().equals(type)
                || ChunkType.INTERFACE.name().equals(type)
                || ChunkType.ENUM.name().equals(type)
                || ChunkType.RECORD.name().equals(type);
    }
}
