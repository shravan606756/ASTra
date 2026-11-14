package com.shravan.jcode_intelligence.service.classification;

import java.nio.file.Path;
import java.util.List;

/**
 * Result of line-of-code analysis for a repository.
 *
 * @param totalLoc      total non-blank lines of code across all eligible source files
 * @param eligibleFiles immutable list of eligible file paths discovered
 */
public record LocAnalysis(long totalLoc, List<Path> eligibleFiles) {

    public LocAnalysis {
        eligibleFiles = (eligibleFiles == null) ? List.of() : List.copyOf(eligibleFiles);
    }
}
