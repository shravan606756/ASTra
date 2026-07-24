package com.shravan.jcode_intelligence.service;

import java.io.IOException;

public interface IndexingService {

    int indexProject(String projectPath) throws IOException;

    int indexProject(String projectPath, String repositoryId) throws IOException;

    int indexGitRepository(String gitUrl) throws IOException;

    int indexGitRepository(String gitUrl, String repositoryId) throws IOException;
}

