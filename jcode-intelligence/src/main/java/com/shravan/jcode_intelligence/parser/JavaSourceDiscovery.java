package com.shravan.jcode_intelligence.parser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Discovers indexable Java source files under a given root path.
 */
@Component
public class JavaSourceDiscovery {

    private static final Logger log = LoggerFactory.getLogger(JavaSourceDiscovery.class);

    private final IndexableJavaFileFilter filter;

    public JavaSourceDiscovery() {
        this(new IndexableJavaFileFilter());
    }

    @Autowired
    public JavaSourceDiscovery(IndexableJavaFileFilter filter) {
        this.filter = filter != null ? filter : new IndexableJavaFileFilter();
    }

    /**
     * Discovers all eligible Java files under the specified root path.
     *
     * @param root root directory or file path to discover sources from
     * @return immutable list of discovered paths sorted by path
     * @throws IOException if the root does not exist or traversal fails
     */
    public List<Path> discover(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            throw new NoSuchFileException(root != null ? root.toString() : "null");
        }

        List<Path> eligible = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(filter::test).forEach(eligible::add);
        } catch (UncheckedIOException e) {
            throw e;
        }

        eligible.sort(Comparator.<Path>naturalOrder().thenComparing(Path::toString));

        List<Path> uniquePaths = new ArrayList<>();
        Set<Object> seenFileIdentities = new HashSet<>();

        for (Path path : eligible) {
            Object identityKey = resolveFileIdentity(path);
            if (seenFileIdentities.add(identityKey)) {
                uniquePaths.add(path);
            }
        }

        return List.copyOf(uniquePaths);
    }

    private Object resolveFileIdentity(Path path) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
            Object fileKey = attrs.fileKey();
            if (fileKey != null) {
                return fileKey;
            }
        } catch (Exception e) {
            log.debug("Failed to read fileKey for {}, falling back to real path: {}", path, e.getMessage());
        }

        try {
            return path.toRealPath();
        } catch (Exception e) {
            log.debug("Failed to resolve real path for {}, falling back to normalized absolute path: {}",
                    path, e.getMessage());
            return path.toAbsolutePath().normalize();
        }
    }

    /**
     * Convenience alias for {@link #discover(Path)}.
     */
    public List<Path> discoverJavaFiles(Path root) throws IOException {
        return discover(root);
    }
}
