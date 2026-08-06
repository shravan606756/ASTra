package com.shravan.jcode_intelligence.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shravan.jcode_intelligence.model.ArchitectureContext;
import com.shravan.jcode_intelligence.model.ArchitectureContext.DependencyEdge;
import com.shravan.jcode_intelligence.model.ComponentRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Builds deterministic, repository-wide {@link ArchitectureContext} by querying
 * structural metadata from PostgreSQL {@code vector_store}.
 *
 * <p>Retrieval rules:
 * <ul>
 *   <li><b>ALL Packages</b>: Retrieves every package summary (never Top-K!).</li>
 *   <li><b>Components by Role</b>: Classifies top-level types into {@link ComponentRole}.</li>
 *   <li><b>Architectural Interfaces</b>: Retrieves top-level interfaces.</li>
 *   <li><b>Orchestration Methods</b>: Retrieves entry points and orchestration methods, excluding accessors/getters/setters.</li>
 *   <li><b>Zero Fields</b>: FIELD chunks are completely excluded.</li>
 *   <li><b>Dependency Graph & Layer Inference</b>: In-memory dependency graph and layer detection.</li>
 * </ul>
 *
 * <p>Supports budgeted context building via {@code buildBudgetedContext}, which uses already
 * retrieved and reranked documents to preserve LLM context limits.
 */
@Component
public class ArchitectureContextBuilder {

    private static final Logger log = LoggerFactory.getLogger(ArchitectureContextBuilder.class);
    private static final ObjectMapper jsonMapper = new ObjectMapper();

    private final JdbcTemplate jdbcTemplate;

    public ArchitectureContextBuilder(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Builds complete, structured architectural context for a repository.
     */
    public ArchitectureContext buildContext(String repositoryId) {
        long start = System.currentTimeMillis();
        ArchitectureContext context = new ArchitectureContext(repositoryId);

        // 1. ALL Package summaries (Never Top-K!)
        List<Document> packages = retrieveAllPackageSummaries(repositoryId);
        context.setPackageSummaries(packages);

        // 2. Compute Repository Statistics
        Map<String, Integer> stats = computeRepositoryStatistics(repositoryId, packages.size());
        context.setStatistics(stats);

        // 3. Components by Role
        Map<ComponentRole, List<Document>> componentsByRole = retrieveComponentsByRole(repositoryId);
        context.setComponentsByRole(componentsByRole);

        // 4. Architectural Interfaces
        List<Document> interfaces = retrieveArchitecturalInterfaces(repositoryId);
        context.setInterfaces(interfaces);

        // 5. Orchestration Methods (No getters/setters/fields)
        List<Document> orchestrationMethods = retrieveOrchestrationMethods(repositoryId);
        context.setOrchestrationMethods(orchestrationMethods);

        // 6. In-Memory Dependency Graph
        List<DependencyEdge> edges = buildDependencyGraph(componentsByRole, interfaces);
        context.setDependencyEdges(edges);

        // 7. Layer Inference
        Map<String, List<String>> layers = inferLayers(componentsByRole);
        context.setInferredLayers(layers);

        long duration = System.currentTimeMillis() - start;
        log.info("Built ArchitectureContext for repo '{}' in {} ms: {} packages, {} components, {} interfaces, {} orchestration methods, {} dependency edges",
                repositoryId, duration, packages.size(),
                countTotalComponents(componentsByRole), interfaces.size(),
                orchestrationMethods.size(), edges.size());

        return context;
    }

    /**
     * Builds a budgeted architectural context using ONLY the provided list of documents.
     * Prevents unbounded database fetching while still enriching with lightweight metadata.
     */
    public ArchitectureContext buildBudgetedContext(String repositoryId, List<Document> budgetedDocuments) {
        long start = System.currentTimeMillis();
        ArchitectureContext context = new ArchitectureContext(repositoryId);

        List<Document> packages = new ArrayList<>();
        Map<ComponentRole, List<Document>> componentsByRole = new EnumMap<>(ComponentRole.class);
        List<Document> interfaces = new ArrayList<>();
        List<Document> orchestrationMethods = new ArrayList<>();

        if (budgetedDocuments != null) {
            for (Document doc : budgetedDocuments) {
                Map<String, Object> meta = doc.getMetadata();
                String type = String.valueOf(meta.getOrDefault("type", ""));

                if ("PACKAGE".equals(type)) {
                    packages.add(doc);
                } else if ("INTERFACE".equals(type)) {
                    interfaces.add(doc);
                } else if ("CLASS".equals(type) || "ENUM".equals(type) || "RECORD".equals(type)) {
                    ComponentRole role = ComponentRole.fromMetadata(meta);
                    componentsByRole.computeIfAbsent(role, k -> new ArrayList<>()).add(doc);
                } else if ("METHOD".equals(type) || "CONSTRUCTOR".equals(type)) {
                    orchestrationMethods.add(doc);
                }
            }
        }

        context.setPackageSummaries(packages);
        context.setComponentsByRole(componentsByRole);
        context.setInterfaces(interfaces);
        context.setOrchestrationMethods(orchestrationMethods);

        // Fetch ONLY lightweight statistics from DB
        int totalPackages = fetchPackageCount(repositoryId);
        Map<String, Integer> stats = computeRepositoryStatistics(repositoryId, totalPackages);
        context.setStatistics(stats);

        // Compute topology dynamically from the budgeted subset
        List<DependencyEdge> edges = buildDependencyGraph(componentsByRole, interfaces);
        context.setDependencyEdges(edges);

        Map<String, List<String>> layers = inferLayers(componentsByRole);
        context.setInferredLayers(layers);

        long duration = System.currentTimeMillis() - start;
        log.info("Built Budgeted ArchitectureContext for repo '{}' in {} ms: {} packages, {} components, {} interfaces, {} methods, {} edges",
                repositoryId, duration, packages.size(),
                countTotalComponents(componentsByRole), interfaces.size(),
                orchestrationMethods.size(), edges.size());

        return context;
    }

    // ── Structural SQL Queries ────────────────────────────────

    private List<Document> retrieveAllPackageSummaries(String repositoryId) {
        try {
            String sql;
            Object[] params;
            if (repositoryId != null && !repositoryId.isBlank()) {
                sql = "SELECT id, content, metadata FROM vector_store WHERE metadata->>'type' = 'PACKAGE' AND metadata->>'repositoryId' = ?";
                params = new Object[]{repositoryId};
            } else {
                sql = "SELECT id, content, metadata FROM vector_store WHERE metadata->>'type' = 'PACKAGE'";
                params = new Object[]{};
            }
            return executeQuery(sql, params);
        } catch (Exception e) {
            log.error("Failed to query package summaries: {}", e.getMessage(), e);
            return List.of();
        }
    }

    private Map<ComponentRole, List<Document>> retrieveComponentsByRole(String repositoryId) {
        Map<ComponentRole, List<Document>> roleMap = new EnumMap<>(ComponentRole.class);

        try {
            String sql;
            Object[] params;
            if (repositoryId != null && !repositoryId.isBlank()) {
                sql = "SELECT id, content, metadata FROM vector_store WHERE metadata->>'type' IN ('CLASS', 'ENUM', 'RECORD') AND metadata->>'repositoryId' = ?";
                params = new Object[]{repositoryId};
            } else {
                sql = "SELECT id, content, metadata FROM vector_store WHERE metadata->>'type' IN ('CLASS', 'ENUM', 'RECORD')";
                params = new Object[]{};
            }

            List<Document> classDocs = executeQuery(sql, params);

            for (Document doc : classDocs) {
                ComponentRole role = ComponentRole.fromMetadata(doc.getMetadata());
                roleMap.computeIfAbsent(role, k -> new ArrayList<>()).add(doc);
            }
        } catch (Exception e) {
            log.error("Failed to query components by role: {}", e.getMessage(), e);
        }

        return roleMap;
    }

    private List<Document> retrieveArchitecturalInterfaces(String repositoryId) {
        try {
            String sql;
            Object[] params;
            if (repositoryId != null && !repositoryId.isBlank()) {
                sql = "SELECT id, content, metadata FROM vector_store WHERE metadata->>'type' = 'INTERFACE' AND metadata->>'repositoryId' = ?";
                params = new Object[]{repositoryId};
            } else {
                sql = "SELECT id, content, metadata FROM vector_store WHERE metadata->>'type' = 'INTERFACE'";
                params = new Object[]{};
            }
            return executeQuery(sql, params);
        } catch (Exception e) {
            log.error("Failed to query interfaces: {}", e.getMessage(), e);
            return List.of();
        }
    }

    private List<Document> retrieveOrchestrationMethods(String repositoryId) {
        try {
            String sql;
            Object[] params;
            if (repositoryId != null && !repositoryId.isBlank()) {
                sql = "SELECT id, content, metadata FROM vector_store WHERE metadata->>'type' IN ('METHOD', 'CONSTRUCTOR') AND metadata->>'repositoryId' = ?";
                params = new Object[]{repositoryId};
            } else {
                sql = "SELECT id, content, metadata FROM vector_store WHERE metadata->>'type' IN ('METHOD', 'CONSTRUCTOR')";
                params = new Object[]{};
            }

            List<Document> methods = executeQuery(sql, params);
            List<Document> orchestration = new ArrayList<>();

            for (Document doc : methods) {
                Map<String, Object> meta = doc.getMetadata();
                String elementName = String.valueOf(meta.getOrDefault("elementName", ""));
                String annotations = String.valueOf(meta.getOrDefault("annotations", "")).toLowerCase(Locale.ROOT);

                // Exclude getters, setters, accessors, trivial methods
                if (isAccessorOrTrivial(elementName)) {
                    continue;
                }

                // Prioritize entry points, async, scheduled, transactional, or main methods
                boolean isOrchestration = elementName.equalsIgnoreCase("main")
                        || elementName.startsWith("execute")
                        || elementName.startsWith("schedule")
                        || elementName.startsWith("create")
                        || elementName.startsWith("process")
                        || elementName.startsWith("handle")
                        || elementName.startsWith("parse")
                        || elementName.startsWith("index")
                        || elementName.startsWith("retrieve")
                        || annotations.contains("scheduled")
                        || annotations.contains("async")
                        || annotations.contains("transactional")
                        || annotations.contains("bean");

                if (isOrchestration) {
                    orchestration.add(doc);
                    if (orchestration.size() >= 10) {
                        break;
                    }
                }
            }

            return orchestration;
        } catch (Exception e) {
            log.error("Failed to query orchestration methods: {}", e.getMessage(), e);
            return List.of();
        }
    }

    private Map<String, Integer> computeRepositoryStatistics(String repositoryId, int packageCount) {
        Map<String, Integer> stats = new LinkedHashMap<>();
        stats.put("Packages", packageCount);

        try {
            String sql = "SELECT metadata->>'type' as type, COUNT(*) as cnt FROM vector_store ";
            Object[] params;
            if (repositoryId != null && !repositoryId.isBlank()) {
                sql += "WHERE metadata->>'repositoryId' = ? GROUP BY metadata->>'type'";
                params = new Object[]{repositoryId};
            } else {
                sql += "GROUP BY metadata->>'type'";
                params = new Object[]{};
            }

            jdbcTemplate.query(sql, params, rs -> {
                String type = rs.getString("type");
                int cnt = rs.getInt("cnt");
                if (type != null) {
                    stats.put(type + "s", cnt);
                }
            });
        } catch (Exception e) {
            log.warn("Failed to compute repository statistics via SQL count: {}", e.getMessage());
        }

        return stats;
    }

    private int fetchPackageCount(String repositoryId) {
        try {
            String sql;
            Object[] params;
            if (repositoryId != null && !repositoryId.isBlank()) {
                sql = "SELECT COUNT(*) FROM vector_store WHERE metadata->>'type' = 'PACKAGE' AND metadata->>'repositoryId' = ?";
                params = new Object[]{repositoryId};
            } else {
                sql = "SELECT COUNT(*) FROM vector_store WHERE metadata->>'type' = 'PACKAGE'";
                params = new Object[]{};
            }
            Integer count = jdbcTemplate.queryForObject(sql, params, Integer.class);
            return count != null ? count : 0;
        } catch (Exception e) {
            log.warn("Failed to count packages: {}", e.getMessage());
            return 0;
        }
    }

    // ── Dependency Graph & Layer Inference ────────────────────

    @SuppressWarnings("unchecked")
    private List<DependencyEdge> buildDependencyGraph(Map<ComponentRole, List<Document>> componentsByRole, List<Document> interfaces) {
        List<DependencyEdge> edges = new ArrayList<>();
        Set<String> addedEdgeKeys = new HashSet<>();

        List<Document> allComponents = new ArrayList<>();
        componentsByRole.values().forEach(allComponents::addAll);
        allComponents.addAll(interfaces);

        for (Document doc : allComponents) {
            Map<String, Object> meta = doc.getMetadata();
            String sourceName = String.valueOf(meta.getOrDefault("className", ""));
            if (sourceName.isBlank()) continue;

            // Interfaces implemented
            Object ifacesObj = meta.get("interfaces");
            if (ifacesObj instanceof List<?> ifaceList) {
                for (Object iface : ifaceList) {
                    String target = String.valueOf(iface);
                    addEdge(edges, addedEdgeKeys, sourceName, target, "implements");
                }
            }

            // Superclass
            Object superObj = meta.get("superClass");
            if (superObj != null && !superObj.toString().isBlank() && !"Object".equals(superObj.toString())) {
                addEdge(edges, addedEdgeKeys, sourceName, superObj.toString(), "extends");
            }

            // Relationships map (injects, calls, uses)
            Object relsObj = meta.get("relationships");
            if (relsObj instanceof Map<?, ?> relMap) {
                for (Map.Entry<?, ?> entry : relMap.entrySet()) {
                    String relType = String.valueOf(entry.getKey());
                    if (entry.getValue() instanceof List<?> targetList) {
                        for (Object targetObj : targetList) {
                            addEdge(edges, addedEdgeKeys, sourceName, String.valueOf(targetObj), relType);
                        }
                    }
                }
            }
        }

        return edges;
    }

    private void addEdge(List<DependencyEdge> edges, Set<String> addedKeys, String source, String target, String relType) {
        if (source.equals(target) || target.isBlank() || target.equals("Object")) return;
        String key = source + "->" + target + ":" + relType;
        if (addedKeys.add(key)) {
            edges.add(new DependencyEdge(source, target, relType));
        }
    }

    private Map<String, List<String>> inferLayers(Map<ComponentRole, List<Document>> componentsByRole) {
        Map<String, List<String>> layers = new LinkedHashMap<>();

        List<String> presentation = extractClassNames(componentsByRole.get(ComponentRole.CONTROLLER));
        List<String> business = extractClassNames(componentsByRole.get(ComponentRole.SERVICE));
        business.addAll(extractClassNames(componentsByRole.get(ComponentRole.STRATEGY)));

        List<String> persistence = extractClassNames(componentsByRole.get(ComponentRole.REPOSITORY));
        persistence.addAll(extractClassNames(componentsByRole.get(ComponentRole.ENTITY)));

        List<String> infrastructure = extractClassNames(componentsByRole.get(ComponentRole.CONFIGURATION));
        infrastructure.addAll(extractClassNames(componentsByRole.get(ComponentRole.EVENT)));

        if (!presentation.isEmpty()) layers.put("Presentation Layer", presentation);
        if (!business.isEmpty()) layers.put("Business Layer", business);
        if (!persistence.isEmpty()) layers.put("Persistence Layer", persistence);
        if (!infrastructure.isEmpty()) layers.put("Infrastructure Layer", infrastructure);

        return layers;
    }

    private List<String> extractClassNames(List<Document> docs) {
        List<String> names = new ArrayList<>();
        if (docs != null) {
            for (Document doc : docs) {
                String name = String.valueOf(doc.getMetadata().getOrDefault("className", ""));
                if (!name.isBlank()) names.add(name);
            }
        }
        return names;
    }

    private boolean isAccessorOrTrivial(String name) {
        if (name == null || name.isBlank()) return true;
        return name.startsWith("get") || name.startsWith("set")
                || name.startsWith("is") || name.startsWith("has")
                || name.equals("toString") || name.equals("hashCode") || name.equals("equals");
    }

    private int countTotalComponents(Map<ComponentRole, List<Document>> map) {
        return map.values().stream().mapToInt(List::size).sum();
    }

    @SuppressWarnings("unchecked")
    private List<Document> executeQuery(String sql, Object[] params) {
        return jdbcTemplate.query(sql, params, (rs, rowNum) -> {
            String content = rs.getString("content");
            String metadataJson = rs.getString("metadata");
            Map<String, Object> metadata = parseMetadataJson(metadataJson);
            return new Document(content, metadata);
        });
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMetadataJson(String json) {
        if (json == null || json.isBlank()) return new HashMap<>();
        try {
            return jsonMapper.readValue(json, Map.class);
        } catch (Exception e) {
            log.warn("Failed to parse metadata JSON: {}", e.getMessage());
            return new HashMap<>();
        }
    }
}
