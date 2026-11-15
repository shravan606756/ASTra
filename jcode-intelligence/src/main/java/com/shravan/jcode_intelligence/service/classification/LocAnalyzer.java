package com.shravan.jcode_intelligence.service.classification;

import com.shravan.jcode_intelligence.parser.JavaSourceDiscovery;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Analyzes eligible source files to compute non-blank lines of code without parsing ASTs.
 */
@Component
public class LocAnalyzer {

    private final JavaSourceDiscovery discovery;

    public LocAnalyzer() {
        this(new JavaSourceDiscovery());
    }

    @Autowired
    public LocAnalyzer(JavaSourceDiscovery discovery) {
        this.discovery = Objects.requireNonNull(discovery, "discovery must not be null");
    }

    /**
     * Discovers eligible Java files under the root and counts total non-blank LOC.
     *
     * @param root repository root directory or source path
     * @return {@link LocAnalysis} containing total non-blank LOC and the immutable list of eligible files
     * @throws IOException if file discovery or reading fails
     */
    public LocAnalysis analyze(Path root) throws IOException {
        List<Path> eligibleFiles = discovery.discover(root);
        long totalLoc = 0;
        for (Path file : eligibleFiles) {
            totalLoc += countNonBlankLines(file);
        }
        return new LocAnalysis(totalLoc, eligibleFiles);
    }

    /**
     * Counts lines with at least one non-whitespace character in a file.
     *
     * <p>Decodes file bytes as UTF-8 with malformed input and unmappable character replacement,
     * correctly handling LF, CRLF, and files without trailing newlines.
     *
     * @param file source file path
     * @return non-blank line count
     * @throws IOException if reading the file fails
     */
    public long countNonBlankLines(Path file) throws IOException {
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);

        long nonBlankLines = 0;
        try (var reader = new BufferedReader(new InputStreamReader(Files.newInputStream(file), decoder))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    nonBlankLines++;
                }
            }
        }
        return nonBlankLines;
    }
}
