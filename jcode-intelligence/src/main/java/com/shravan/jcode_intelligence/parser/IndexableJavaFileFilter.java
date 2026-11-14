package com.shravan.jcode_intelligence.parser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Predicate;

/**
 * Filter determining whether a file is an indexable Java source file for AST analysis.
 */
@Component
public class IndexableJavaFileFilter implements Predicate<Path> {

    private static final Logger log = LoggerFactory.getLogger(IndexableJavaFileFilter.class);

    public IndexableJavaFileFilter() {
    }

    @Override
    public boolean test(Path path) {
        return isIndexable(path);
    }

    /**
     * Determines whether a Java file should be included in AST semantic indexing.
     * Excludes build directories (target, build, .git), dependency caches (node_modules, .gradle, .m2),
     * generated source directories, and non-source test fixture resources.
     */
    public boolean isIndexable(Path path) {
        if (path == null) {
            return false;
        }
        if (!Files.isRegularFile(path)) {
            return false;
        }
        if (!path.toString().endsWith(".java")) {
            return false;
        }
        String normalized = path.toString().replace('\\', '/');
        if (normalized.contains("/target/")
                || normalized.contains("/build/")
                || normalized.contains("/.git/")
                || normalized.contains("/generated-sources/")
                || normalized.contains("/generated-test-sources/")
                || normalized.contains("/node_modules/")
                || normalized.contains("/.gradle/")
                || normalized.contains("/.m2/")) {
            return false;
        }
        if (normalized.contains("/src/test/resources/") || normalized.contains("/test-resources/")) {
            log.debug("Skipping test fixture resource file: {}", path);
            return false;
        }
        return true;
    }
}
