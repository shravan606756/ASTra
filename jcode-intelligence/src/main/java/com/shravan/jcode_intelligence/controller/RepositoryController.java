package com.shravan.jcode_intelligence.controller;

import com.shravan.jcode_intelligence.service.RepositoryManagementService;
import com.shravan.jcode_intelligence.sparse.store.LuceneGenerationStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST endpoints for managing indexed repositories.
 */
@RestController
@RequestMapping("/api/v1/repositories")
public class RepositoryController {

    private final RepositoryManagementService repositoryManagementService;

    public RepositoryController(RepositoryManagementService repositoryManagementService) {
        this.repositoryManagementService = repositoryManagementService;
    }

    @GetMapping
    public ResponseEntity<List<String>> listRepositories() {
        return ResponseEntity.ok(repositoryManagementService.listRepositories());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> removeRepository(@PathVariable("id") String repositoryId) {
        if (repositoryId == null || repositoryId.isBlank()) {
            throw new IllegalArgumentException("repositoryId must not be null or blank for repository deletion");
        }
        LuceneGenerationStore.validateNonBlankRepositoryId(repositoryId);
        boolean removed = repositoryManagementService.removeRepository(repositoryId);
        if (removed) {
            return ResponseEntity.noContent().build();
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/{id}/stats")
    public ResponseEntity<com.shravan.jcode_intelligence.dto.response.RepositoryStatsDTO> getRepositoryStats(@PathVariable("id") String repositoryId) {
        if (repositoryId == null || repositoryId.isBlank()) {
            throw new IllegalArgumentException("repositoryId must not be null or blank");
        }
        LuceneGenerationStore.validateNonBlankRepositoryId(repositoryId);
        return ResponseEntity.ok(repositoryManagementService.getRepositoryStats(repositoryId));
    }
}
