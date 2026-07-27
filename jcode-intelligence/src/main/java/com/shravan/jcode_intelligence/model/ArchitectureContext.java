package com.shravan.jcode_intelligence.model;

import org.springframework.ai.document.Document;

import java.util.*;

/**
 * Represents structured, repository-wide architectural context extracted
 * for an architecture analysis query.
 *
 * <p>Contains repository statistics, full package hierarchy, categorized
 * component summaries, architectural interfaces, orchestration methods,
 * and an in-memory dependency graph.
 */
public class ArchitectureContext {

    private String repositoryId;
    private Map<String, Integer> statistics = new LinkedHashMap<>();
    private List<Document> packageSummaries = new ArrayList<>();
    private Map<ComponentRole, List<Document>> componentsByRole = new EnumMap<>(ComponentRole.class);
    private List<Document> interfaces = new ArrayList<>();
    private List<Document> orchestrationMethods = new ArrayList<>();
    private List<DependencyEdge> dependencyEdges = new ArrayList<>();
    private Map<String, List<String>> inferredLayers = new LinkedHashMap<>();

    public ArchitectureContext() {
    }

    public ArchitectureContext(String repositoryId) {
        this.repositoryId = repositoryId;
    }

    // ── Getters and Setters ────────────────────────────────────

    public String getRepositoryId() {
        return repositoryId;
    }

    public void setRepositoryId(String repositoryId) {
        this.repositoryId = repositoryId;
    }

    public Map<String, Integer> getStatistics() {
        return statistics;
    }

    public void setStatistics(Map<String, Integer> statistics) {
        this.statistics = statistics;
    }

    public List<Document> getPackageSummaries() {
        return packageSummaries;
    }

    public void setPackageSummaries(List<Document> packageSummaries) {
        this.packageSummaries = packageSummaries;
    }

    public Map<ComponentRole, List<Document>> getComponentsByRole() {
        return componentsByRole;
    }

    public void setComponentsByRole(Map<ComponentRole, List<Document>> componentsByRole) {
        this.componentsByRole = componentsByRole;
    }

    public List<Document> getInterfaces() {
        return interfaces;
    }

    public void setInterfaces(List<Document> interfaces) {
        this.interfaces = interfaces;
    }

    public List<Document> getOrchestrationMethods() {
        return orchestrationMethods;
    }

    public void setOrchestrationMethods(List<Document> orchestrationMethods) {
        this.orchestrationMethods = orchestrationMethods;
    }

    public List<DependencyEdge> getDependencyEdges() {
        return dependencyEdges;
    }

    public void setDependencyEdges(List<DependencyEdge> dependencyEdges) {
        this.dependencyEdges = dependencyEdges;
    }

    public Map<String, List<String>> getInferredLayers() {
        return inferredLayers;
    }

    public void setInferredLayers(Map<String, List<String>> inferredLayers) {
        this.inferredLayers = inferredLayers;
    }

    /**
     * Flattens all structural documents into a single deduplicated list
     * (strictly excluding FIELD chunks).
     */
    public List<Document> toFlatDocumentList() {
        Set<String> seenKeys = new HashSet<>();
        List<Document> flatList = new ArrayList<>();

        addDeduplicated(flatList, seenKeys, packageSummaries);

        for (List<Document> roleDocs : componentsByRole.values()) {
            addDeduplicated(flatList, seenKeys, roleDocs);
        }

        addDeduplicated(flatList, seenKeys, interfaces);
        addDeduplicated(flatList, seenKeys, orchestrationMethods);

        return flatList;
    }

    private void addDeduplicated(List<Document> target, Set<String> seenKeys, List<Document> source) {
        if (source == null) return;
        for (Document doc : source) {
            Map<String, Object> meta = doc.getMetadata();
            String type = String.valueOf(meta.getOrDefault("type", ""));
            if ("FIELD".equals(type)) {
                continue; // Zero FIELD chunks!
            }

            String repoId = String.valueOf(meta.getOrDefault("repositoryId", "default"));
            String filePath = String.valueOf(meta.getOrDefault("filePath", ""));
            String startLine = String.valueOf(meta.getOrDefault("startLine", "0"));
            String endLine = String.valueOf(meta.getOrDefault("endLine", "0"));

            String key = String.format("%s:%s:%s:%s:%s", repoId, filePath, startLine, endLine, type);
            if (seenKeys.add(key)) {
                target.add(doc);
            }
        }
    }

    /**
     * Represents a directed dependency relationship between two components.
     */
    public record DependencyEdge(String sourceComponent, String targetComponent, String relationshipType) {
        @Override
        public String toString() {
            return String.format("%s -[%s]-> %s", sourceComponent, relationshipType, targetComponent);
        }
    }
}
