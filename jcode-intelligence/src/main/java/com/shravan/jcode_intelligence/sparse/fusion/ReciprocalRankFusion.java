package com.shravan.jcode_intelligence.sparse.fusion;

import com.shravan.jcode_intelligence.config.RetrievalConfig;
import com.shravan.jcode_intelligence.model.QueryIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Weighted Reciprocal Rank Fusion (RRF) for combining dense vector and sparse BM25 candidate lists.
 *
 * <p>Reciprocal Rank Fusion calculates a fused score for each document across candidate result lists:
 * <pre>
 *   RRF(d) = ∑ [ w_m * (1.0 / (k + rank_m(d))) ]
 * </pre>
 * where:
 * <ul>
 *   <li>{@code rank_m(d)} is the 1-based rank (1, 2, ...) of document {@code d} in list {@code m}.</li>
 *   <li>{@code k} is a smoothing constant (default: 60) configured via {@link RetrievalConfig#getRrfK()}.</li>
 *   <li>{@code w_m} is the list weight (dense or sparse), configured globally or per {@link QueryIntent}.</li>
 * </ul>
 *
 * <h3>Canonical Dense Data</h3>
 * <p>When a document appears in both dense and sparse candidate lists, the dense {@link Document} instance
 * is preserved as the canonical representation (preserving its content, chunk metadata, and embeddings),
 * while its score is updated to the fused RRF score. Documents appearing in only one list retain their
 * original representation with their score updated.
 *
 * <h3>Deterministic Tie-Breaking</h3>
 * <p>When two documents achieve identical fused scores, ties are deterministically resolved by sorting
 * ascending on the document's unique identifier.
 *
 * <h3>Equal-Weight List-Depth Condition</h3>
 * <p>When list weights are equal ({@code w_dense = w_sparse = w}), the fused score for a document
 * present in both lists at ranks {@code (r_1, r_2)} is:
 * <pre>
 *   S(r_1, r_2) = w * (1 / (k + r_1) + 1 / (k + r_2))
 * </pre>
 * A document appearing in only one candidate list at rank {@code r} achieves:
 * <pre>
 *   S(r, ∞) = w / (k + r)
 * </pre>
 * The maximum score for an exclusive single-list document occurs at rank 1:
 * <pre>
 *   S_single_max = w / (k + 1)
 * </pre>
 * An intersection document appearing at equal ranks {@code r_1 = r_2 = r} strictly outperforms
 * any single-list document whenever:
 * <pre>
 *   2 / (k + r) > 1 / (k + 1)  <=>  2(k + 1) > k + r  <=>  r < k + 2
 * </pre>
 * With default {@code k = 60}, any document appearing within the top 61 positions of both lists
 * ({@code r <= 61}) is mathematically guaranteed to outrank even the top-ranked (#1) exclusive document.
 *
 * <p>Furthermore, when candidate list depth {@code L} is bounded such that {@code L <= k}:
 * <pre>
 *   S_intersection_min = 2w / (k + L) >= 2w / (2k) = w / k > w / (k + 1)
 * </pre>
 * Thus, whenever list depth {@code L <= k}, <em>all</em> documents present in both candidate lists
 * will rank strictly above <em>any</em> document appearing in only one candidate list.
 */
@Component
public class ReciprocalRankFusion {

    private static final Logger log = LoggerFactory.getLogger(ReciprocalRankFusion.class);

    private final RetrievalConfig config;

    public ReciprocalRankFusion() {
        this(new RetrievalConfig());
    }

    @Autowired(required = false)
    public ReciprocalRankFusion(RetrievalConfig config) {
        this.config = (config != null) ? config : new RetrievalConfig();
    }

    /**
     * Fuses dense and sparse candidate lists using intent-specific weights and configured k.
     *
     * @param denseDocs  ranked candidate documents from vector search (1-based rank by index)
     * @param sparseDocs ranked candidate documents from Lucene BM25 search (1-based rank by index)
     * @param intent     the classified query intent (used to lookup per-intent weights)
     * @param limit      maximum number of fused documents to return
     * @return fused, ranked, deduplicated, and truncated documents
     */
    public List<Document> fuse(List<Document> denseDocs, List<Document> sparseDocs,
                               QueryIntent intent, int limit) {
        double denseWeight = config.getDenseWeight(intent);
        double sparseWeight = config.getSparseWeight(intent);
        return fuse(denseDocs, sparseDocs, denseWeight, sparseWeight, limit);
    }

    /**
     * Fuses dense and sparse candidate lists using default weights and configured k.
     *
     * @param denseDocs  ranked candidate documents from vector search
     * @param sparseDocs ranked candidate documents from Lucene BM25 search
     * @param limit      maximum number of fused documents to return
     * @return fused documents
     */
    public List<Document> fuse(List<Document> denseDocs, List<Document> sparseDocs, int limit) {
        return fuse(denseDocs, sparseDocs, config.getDefaultDenseWeight(), config.getDefaultSparseWeight(), limit);
    }

    /**
     * Core fusion algorithm combining two ranked lists with explicit weights and configured k.
     *
     * @param denseDocs    ranked candidate documents from dense retrieval
     * @param sparseDocs   ranked candidate documents from sparse retrieval
     * @param denseWeight  weight multiplier for dense rankings
     * @param sparseWeight weight multiplier for sparse rankings
     * @param limit        maximum number of documents to return (if <= 0, returns all)
     * @return fused, ranked documents with canonical dense representations and deterministic tie-breaking
     */
    public List<Document> fuse(List<Document> denseDocs, List<Document> sparseDocs,
                               double denseWeight, double sparseWeight, int limit) {
        if ((denseDocs == null || denseDocs.isEmpty()) && (sparseDocs == null || sparseDocs.isEmpty())) {
            return Collections.emptyList();
        }

        int k = config.getRrfK();
        Map<String, FusionCandidate> candidateMap = new LinkedHashMap<>();

        // Process dense documents (1-based ranks)
        if (denseDocs != null) {
            for (int i = 0; i < denseDocs.size(); i++) {
                Document doc = denseDocs.get(i);
                int rank = i + 1; // 1-based rank
                String id = getDocumentKey(doc);
                double contribution = denseWeight * (1.0 / (k + rank));

                FusionCandidate cand = candidateMap.computeIfAbsent(id, kId -> new FusionCandidate(id));
                cand.score += contribution;
                cand.denseDocument = doc; // Canonical dense representation
                cand.denseRank = rank;
            }
        }

        // Process sparse documents (1-based ranks)
        if (sparseDocs != null) {
            for (int i = 0; i < sparseDocs.size(); i++) {
                Document doc = sparseDocs.get(i);
                int rank = i + 1; // 1-based rank
                String id = getDocumentKey(doc);
                double contribution = sparseWeight * (1.0 / (k + rank));

                FusionCandidate cand = candidateMap.computeIfAbsent(id, kId -> new FusionCandidate(id));
                cand.score += contribution;
                cand.sparseRank = rank;
                if (cand.sparseDocument == null) {
                    cand.sparseDocument = doc;
                }
            }
        }

        // Sort candidates: descending by fused score, then ascending by ID for deterministic tie-breaking
        List<FusionCandidate> sorted = new ArrayList<>(candidateMap.values());
        sorted.sort((a, b) -> {
            int cmp = Double.compare(b.score, a.score);
            if (cmp != 0) {
                return cmp;
            }
            return a.id.compareTo(b.id);
        });

        int max = (limit > 0) ? Math.min(sorted.size(), limit) : sorted.size();
        List<Document> result = new ArrayList<>(max);

        for (int i = 0; i < max; i++) {
            FusionCandidate cand = sorted.get(i);
            // Canonical dense data preferred; sparse used if document was only in sparse list
            Document canonical = (cand.denseDocument != null) ? cand.denseDocument : cand.sparseDocument;

            Map<String, Object> metadata = new LinkedHashMap<>(canonical.getMetadata() != null ? canonical.getMetadata() : Map.of());
            metadata.put(com.shravan.jcode_intelligence.service.RetrievalConstants.METADATA_RRF_SCORE, cand.score);
            metadata.put("rrf_score", cand.score);
            metadata.put("score", cand.score);
            if (cand.denseRank > 0) {
                metadata.put("dense_rank", cand.denseRank);
            }
            if (cand.sparseRank > 0) {
                metadata.put("sparse_rank", cand.sparseRank);
            }

            result.add(new Document(canonical.getId(), canonical.getText(), metadata));
        }

        log.debug("RRF fused {} dense and {} sparse candidates into {} results (limit: {})",
                denseDocs != null ? denseDocs.size() : 0,
                sparseDocs != null ? sparseDocs.size() : 0,
                result.size(), limit);

        return result;
    }

    /**
     * Extracts a stable document key for fusion grouping.
     * Uses explicit document ID if present, falls back to chunkId or content-based key.
     */
    private String getDocumentKey(Document doc) {
        if (doc.getId() != null && !doc.getId().isBlank()) {
            return doc.getId().trim();
        }
        Map<String, Object> meta = doc.getMetadata();
        if (meta != null) {
            Object chunkId = meta.get("chunkId");
            if (chunkId != null && !String.valueOf(chunkId).isBlank()) {
                return String.valueOf(chunkId).trim();
            }
            String repoId = String.valueOf(meta.getOrDefault("repositoryId", "default"));
            String filePath = String.valueOf(meta.getOrDefault("filePath", ""));
            String startLine = String.valueOf(meta.getOrDefault("startLine", "0"));
            String endLine = String.valueOf(meta.getOrDefault("endLine", "0"));
            String type = String.valueOf(meta.getOrDefault("type", ""));
            return String.format("%s:%s:%s:%s:%s", repoId, filePath, startLine, endLine, type);
        }
        return String.valueOf(doc.hashCode());
    }

    /**
     * Internal tracking holder for rank fusion candidate scoring.
     */
    private static class FusionCandidate {
        final String id;
        double score = 0.0;
        Document denseDocument = null;
        Document sparseDocument = null;
        int denseRank = 0;
        int sparseRank = 0;

        FusionCandidate(String id) {
            this.id = id;
        }
    }
}
