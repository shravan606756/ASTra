package com.shravan.jcode_intelligence.service;

import java.io.IOException;
import java.nio.file.Path;

public interface GitService {

    Path cloneRepository(String gitUrl) throws IOException;

    void cleanupRepository(Path directory);
}
