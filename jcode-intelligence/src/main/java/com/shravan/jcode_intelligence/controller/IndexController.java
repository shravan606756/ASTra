package com.shravan.jcode_intelligence.controller;

import com.shravan.jcode_intelligence.dto.request.IndexRequest;
import com.shravan.jcode_intelligence.dto.response.IndexResponse;
import com.shravan.jcode_intelligence.service.IndexingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
@RequestMapping("/api/v1/index")
public class IndexController {

    private final IndexingService indexingService;

    public IndexController(IndexingService indexingService) {
        this.indexingService = indexingService;
    }

    @PostMapping("/local")
    public ResponseEntity<IndexResponse> indexLocal(@RequestBody IndexRequest request) throws IOException {
        if (request.getProjectPath() == null || request.getProjectPath().isBlank()) {
            return ResponseEntity.badRequest().body(new IndexResponse(
                    "ERROR",
                    "projectPath must be provided for local indexing",
                    0,
                    null
            ));
        }

        int count = indexingService.indexProject(request.getProjectPath(), request.getRepositoryId());
        return ResponseEntity.ok(new IndexResponse(
                "SUCCESS",
                "Successfully indexed local project.",
                count,
                request.getRepositoryId()
        ));
    }

    @PostMapping("/git")
    public ResponseEntity<IndexResponse> indexGit(@RequestBody IndexRequest request) throws IOException {
        if (request.getGitUrl() == null || request.getGitUrl().isBlank()) {
            return ResponseEntity.badRequest().body(new IndexResponse(
                    "ERROR",
                    "gitUrl must be provided for git indexing",
                    0,
                    null
            ));
        }

        int count = indexingService.indexGitRepository(request.getGitUrl(), request.getRepositoryId());
        return ResponseEntity.ok(new IndexResponse(
                "SUCCESS",
                "Successfully indexed Git repository.",
                count,
                request.getRepositoryId()
        ));
    }

    @PostMapping
    public ResponseEntity<IndexResponse> indexUnified(@RequestBody IndexRequest request) throws IOException {
        if (request.getGitUrl() != null && !request.getGitUrl().isBlank()) {
            return indexGit(request);
        } else if (request.getProjectPath() != null && !request.getProjectPath().isBlank()) {
            return indexLocal(request);
        }
        return ResponseEntity.badRequest().body(new IndexResponse(
                "ERROR",
                "Either projectPath or gitUrl must be provided",
                0,
                null
        ));
    }
}

