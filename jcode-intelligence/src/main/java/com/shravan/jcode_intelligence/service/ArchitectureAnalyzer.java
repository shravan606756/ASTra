package com.shravan.jcode_intelligence.service;

import com.shravan.jcode_intelligence.model.ArchitectureContext;
import com.shravan.jcode_intelligence.model.ArchitectureContext.DependencyEdge;
import com.shravan.jcode_intelligence.model.ComponentRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Analyzes an {@link ArchitectureContext} to synthesize a structured architectural
 * narrative for LLM prompt consumption.
 *
 * <p>Produces categorized, deterministic context sections:
 * <ul>
 *   <li><b>Repository Overview & Statistics</b></li>
 *   <li><b>Inferred Architecture Layers</b></li>
 *   <li><b>Package Structure Map</b></li>
 *   <li><b>Major Components by Role</b> (Application, Controllers, Services, Repositories, Entities, Strategies, Configurations)</li>
 *   <li><b>Dependency Flow Graph</b></li>
 *   <li><b>Entry Points & Orchestration Methods</b></li>
 * </ul>
 */
@Component
public class ArchitectureAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(ArchitectureAnalyzer.class);

    /**
     * Synthesizes the architecture context into a structured, human-readable text representation.
     */
    public String analyzeAndFormat(ArchitectureContext context) {
        if (context == null) {
            return "No architecture context available.";
        }

        StringBuilder sb = new StringBuilder();

        // 1. REPOSITORY OVERVIEW & STATISTICS
        appendRepositoryOverview(sb, context);

        // 2. INFERRED ARCHITECTURE LAYERS
        appendInferredLayers(sb, context);

        // 3. PACKAGE STRUCTURE
        appendPackageStructure(sb, context);

        // 4. MAJOR COMPONENTS BY ROLE
        appendComponentsByRole(sb, context);

        // 5. DEPENDENCY FLOW & GRAPH
        appendDependencyGraph(sb, context);

        // 6. ENTRY POINTS & ORCHESTRATION METHODS
        appendOrchestrationMethods(sb, context);

        return sb.toString().trim();
    }

    private void appendRepositoryOverview(StringBuilder sb, ArchitectureContext context) {
        sb.append("========== REPOSITORY OVERVIEW & STATISTICS ==========\n\n");
        sb.append(String.format("Repository ID: %s\n", context.getRepositoryId() != null ? context.getRepositoryId() : "default"));

        Map<String, Integer> stats = context.getStatistics();
        if (stats != null && !stats.isEmpty()) {
            sb.append("Repository Statistics:\n");
            for (Map.Entry<String, Integer> entry : stats.entrySet()) {
                sb.append(String.format("  - %s: %d\n", entry.getKey(), entry.getValue()));
            }
        }
        sb.append("\n");
    }

    private void appendInferredLayers(StringBuilder sb, ArchitectureContext context) {
        Map<String, List<String>> layers = context.getInferredLayers();
        if (layers != null && !layers.isEmpty()) {
            sb.append("========== INFERRED ARCHITECTURE LAYERS ==========\n\n");
            for (Map.Entry<String, List<String>> layer : layers.entrySet()) {
                sb.append(String.format("[%s]\n", layer.getKey()));
                for (String component : layer.getValue()) {
                    sb.append(String.format("  - %s\n", component));
                }
                sb.append("\n");
            }
        }
    }

    private void appendPackageStructure(StringBuilder sb, ArchitectureContext context) {
        List<Document> packages = context.getPackageSummaries();
        if (packages != null && !packages.isEmpty()) {
            sb.append("========== PACKAGE STRUCTURE ==========\n\n");
            for (Document doc : packages) {
                Map<String, Object> meta = doc.getMetadata();
                sb.append(String.format("Package: %s\n", meta.getOrDefault("packageName", "default")));
                if (doc.getText() != null && !doc.getText().isBlank()) {
                    sb.append("Summary:\n").append(doc.getText()).append("\n");
                }
                sb.append("\n");
            }
        }
    }

    private void appendComponentsByRole(StringBuilder sb, ArchitectureContext context) {
        Map<ComponentRole, List<Document>> roleMap = context.getComponentsByRole();
        if (roleMap != null && !roleMap.isEmpty()) {
            sb.append("========== MAJOR COMPONENTS BY ROLE ==========\n\n");
            for (ComponentRole role : ComponentRole.values()) {
                List<Document> docs = roleMap.get(role);
                if (docs != null && !docs.isEmpty()) {
                    sb.append(String.format("--- Role: %s (%d components) ---\n\n", role.name(), docs.size()));
                    for (Document doc : docs) {
                        appendComponentDetails(sb, doc);
                    }
                }
            }
        }

        // Interfaces section
        List<Document> interfaces = context.getInterfaces();
        if (interfaces != null && !interfaces.isEmpty()) {
            sb.append("--- Role: ARCHITECTURAL INTERFACES (").append(interfaces.size()).append(" interfaces) ---\n\n");
            for (Document doc : interfaces) {
                appendComponentDetails(sb, doc);
            }
        }
    }

    private void appendComponentDetails(StringBuilder sb, Document doc) {
        Map<String, Object> meta = doc.getMetadata();
        sb.append(String.format("Class: %s | Package: %s | Type: %s\n",
                meta.getOrDefault("className", "UNKNOWN"),
                meta.getOrDefault("packageName", ""),
                meta.getOrDefault("type", "CLASS")));
        if (meta.get("superClass") != null) {
            sb.append(String.format("  Extends: %s\n", meta.get("superClass")));
        }
        if (meta.get("interfaces") != null) {
            sb.append(String.format("  Implements: %s\n", meta.get("interfaces")));
        }
        if (meta.get("annotations") != null) {
            sb.append(String.format("  Annotations: %s\n", meta.get("annotations")));
        }
        if (doc.getText() != null && !doc.getText().isBlank()) {
            sb.append("Source Code / Summary:\n```java\n").append(doc.getText()).append("\n```\n");
        }
        sb.append("\n");
    }

    private void appendDependencyGraph(StringBuilder sb, ArchitectureContext context) {
        List<DependencyEdge> edges = context.getDependencyEdges();
        if (edges != null && !edges.isEmpty()) {
            sb.append("========== DEPENDENCY FLOW & GRAPH ==========\n\n");
            for (DependencyEdge edge : edges) {
                sb.append(String.format("  - %s\n", edge.toString()));
            }
            sb.append("\n");
        }
    }

    private void appendOrchestrationMethods(StringBuilder sb, ArchitectureContext context) {
        List<Document> methods = context.getOrchestrationMethods();
        if (methods != null && !methods.isEmpty()) {
            sb.append("========== ENTRY POINTS & ORCHESTRATION METHODS ==========\n\n");
            for (Document doc : methods) {
                Map<String, Object> meta = doc.getMetadata();
                sb.append(String.format("Method: %s#%s | Signature: %s\n",
                        meta.getOrDefault("className", ""),
                        meta.getOrDefault("elementName", ""),
                        meta.getOrDefault("signature", "")));
                if (doc.getText() != null && !doc.getText().isBlank()) {
                    sb.append("Source Code:\n```java\n").append(doc.getText()).append("\n```\n");
                }
                sb.append("\n");
            }
        }
    }
}
