package com.shravan.jcode_intelligence.service;

import com.shravan.jcode_intelligence.dto.response.BenchmarkSummaryDTO;
import com.shravan.jcode_intelligence.dto.response.QueryBenchmarkResultDTO;
import com.shravan.jcode_intelligence.dto.response.QueryBenchmarkResultDTO.QueryQualityMetricsDTO;
import com.shravan.jcode_intelligence.dto.response.QueryBenchmarkResultDTO.RetrievedChunkDTO;
import com.shravan.jcode_intelligence.model.benchmark.RelevantChunkDefinition;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Deterministic Information Retrieval evaluation engine.
 * Computes standard IR ranking metrics (Hit Rate, Recall, Precision, MRR, NDCG, Context Precision/Recall)
 * against ground-truth relevance annotations without external LLM dependencies.
 */
@Component
public class RetrievalEvaluator {

    /**
     * Evaluates a ranked list of retrieved documents against expected relevant chunks for a query.
     *
     * @param documents      retrieved ranked documents
     * @param groundTruths   expected relevant chunks with graded relevance
     * @param retrievedChunks list to populate with scored chunk representations
     * @return calculated per-query quality metrics
     */
    public QueryQualityMetricsDTO evaluateQuery(List<Document> documents,
                                                List<RelevantChunkDefinition> groundTruths,
                                                List<RetrievedChunkDTO> retrievedChunks) {
        return evaluateQuery(documents, groundTruths, retrievedChunks, documents != null ? documents.size() : 0);
    }

    /**
     * Evaluates a ranked list of retrieved documents against expected relevant chunks for a query,
     * limiting the number of chunks added to retrievedChunks to maxDisplayChunks.
     *
     * @param documents        retrieved ranked documents
     * @param groundTruths     expected relevant chunks with graded relevance
     * @param retrievedChunks  list to populate with scored chunk representations
     * @param maxDisplayChunks maximum chunks to populate in retrievedChunks (<= 0 for all)
     * @return calculated per-query quality metrics
     */
    public QueryQualityMetricsDTO evaluateQuery(List<Document> documents,
                                                List<RelevantChunkDefinition> groundTruths,
                                                List<RetrievedChunkDTO> retrievedChunks,
                                                int maxDisplayChunks) {
        if (documents == null) {
            documents = List.of();
        }
        if (groundTruths == null || groundTruths.isEmpty()) {
            populateRetrievedChunks(documents, retrievedChunks, maxDisplayChunks);
            return null;
        }

        QueryQualityMetricsDTO metrics = new QueryQualityMetricsDTO();

        // 1. Score each retrieved document against ground truths
        // To prevent duplicate inflation, each ground-truth item can only be satisfied once
        List<Integer> relevanceGrades = new ArrayList<>(documents.size());
        Set<RelevantChunkDefinition> matchedGroundTruths = new HashSet<>();
        Set<RelevantChunkDefinition> unsatisfiedGts = new HashSet<>(groundTruths);

        int displayLimit = (maxDisplayChunks > 0) ? Math.min(maxDisplayChunks, documents.size()) : documents.size();

        for (int i = 0; i < documents.size(); i++) {
            Document doc = documents.get(i);
            int grade = 0;
            RelevantChunkDefinition matchedGt = null;

            for (RelevantChunkDefinition gt : unsatisfiedGts) {
                if (matches(doc, gt)) {
                    if (gt.getRelevance() > grade) {
                        grade = gt.getRelevance();
                        matchedGt = gt;
                    }
                }
            }

            if (matchedGt != null && grade > 0) {
                matchedGroundTruths.add(matchedGt);
                unsatisfiedGts.remove(matchedGt); // Consumed: prevent duplicate inflation
            }

            relevanceGrades.add(grade);

            if (retrievedChunks != null && i < displayLimit) {
                Map<String, Object> meta = doc.getMetadata();
                String chunkId = String.valueOf(meta.getOrDefault("chunkId", ""));
                String filePath = String.valueOf(meta.getOrDefault("filePath", ""));
                String className = String.valueOf(meta.getOrDefault("className", ""));
                String elementName = String.valueOf(meta.getOrDefault("elementName", ""));
                String type = String.valueOf(meta.getOrDefault("type", ""));

                retrievedChunks.add(new RetrievedChunkDTO(
                        chunkId, filePath, className, elementName, type,
                        i + 1, grade > 0, grade
                ));
            }
        }

        int totalRelevantGt = (int) groundTruths.stream().filter(gt -> gt.getRelevance() > 0).count();

        // 2. Compute Hit Rate @ K (K in [3, 5, 25])
        metrics.setHitRateAt3(computeHitRate(relevanceGrades, 3));
        metrics.setHitRateAt5(computeHitRate(relevanceGrades, 5));
        metrics.setHitRateAt25(computeHitRate(relevanceGrades, 25));

        // 3. Compute Recall @ K (K in [3, 5, 25])
        metrics.setRecallAt3(computeRecall(relevanceGrades, 3, totalRelevantGt, documents, groundTruths));
        metrics.setRecallAt5(computeRecall(relevanceGrades, 5, totalRelevantGt, documents, groundTruths));
        metrics.setRecallAt25(computeRecall(relevanceGrades, 25, totalRelevantGt, documents, groundTruths));

        // 4. Compute Precision @ K (K in [3, 5, 25])
        metrics.setPrecisionAt3(computePrecision(relevanceGrades, 3));
        metrics.setPrecisionAt5(computePrecision(relevanceGrades, 5));
        metrics.setPrecisionAt25(computePrecision(relevanceGrades, 25));

        // 5. Compute Reciprocal Rank (RR)
        metrics.setReciprocalRank(computeReciprocalRank(relevanceGrades));

        // 6. Compute NDCG @ K (K in [3, 5, 25])
        metrics.setNdcgAt3(computeNdcg(relevanceGrades, 3, groundTruths));
        metrics.setNdcgAt5(computeNdcg(relevanceGrades, 5, groundTruths));
        metrics.setNdcgAt25(computeNdcg(relevanceGrades, 25, groundTruths));

        // 7. Context Precision
        metrics.setContextPrecision(computeContextPrecision(relevanceGrades));

        // 8. Context Recall
        metrics.setContextRecall(totalRelevantGt > 0 ? (double) matchedGroundTruths.size() / totalRelevantGt : 0.0);

        return metrics;
    }

    /**
     * Populates retrieved chunk representations when ground truth is not provided.
     * Relevant flag and relevanceGrade are left null.
     */
    public void populateRetrievedChunks(List<Document> documents, List<RetrievedChunkDTO> retrievedChunks) {
        populateRetrievedChunks(documents, retrievedChunks, documents != null ? documents.size() : 0);
    }

    /**
     * Populates retrieved chunk representations when ground truth is not provided,
     * limiting output to maxDisplayChunks.
     */
    public void populateRetrievedChunks(List<Document> documents, List<RetrievedChunkDTO> retrievedChunks, int maxDisplayChunks) {
        if (documents == null || retrievedChunks == null) return;
        int displayLimit = (maxDisplayChunks > 0) ? Math.min(maxDisplayChunks, documents.size()) : documents.size();
        for (int i = 0; i < displayLimit; i++) {
            Document doc = documents.get(i);
            Map<String, Object> meta = doc.getMetadata();
            String chunkId = String.valueOf(meta.getOrDefault("chunkId", ""));
            String filePath = String.valueOf(meta.getOrDefault("filePath", ""));
            String className = String.valueOf(meta.getOrDefault("className", ""));
            String elementName = String.valueOf(meta.getOrDefault("elementName", ""));
            String type = String.valueOf(meta.getOrDefault("type", ""));

            retrievedChunks.add(new RetrievedChunkDTO(
                    chunkId, filePath, className, elementName, type,
                    i + 1, null, null
            ));
        }
    }

    /**
     * Aggregates individual query benchmark results into a top-level summary.
     */
    public BenchmarkSummaryDTO aggregateSummary(List<QueryBenchmarkResultDTO> results,
                                               long totalDurationMs,
                                               int timeoutCount) {
        BenchmarkSummaryDTO summary = new BenchmarkSummaryDTO();
        summary.setTotalQueries(results.size());
        summary.setTotalDurationMs(totalDurationMs);
        summary.setTimeoutCount(timeoutCount);

        if (results.isEmpty()) {
            return summary;
        }

        int successCount = 0;
        int failureCount = 0;
        int groundTruthQueryCount = 0;

        double sumHr3 = 0, sumHr5 = 0, sumHr25 = 0;
        double sumRec3 = 0, sumRec5 = 0, sumRec25 = 0;
        double sumPrec3 = 0, sumPrec5 = 0, sumPrec25 = 0;
        double sumMrr = 0;
        double sumNdcg3 = 0, sumNdcg5 = 0, sumNdcg25 = 0;
        double sumContextPrec = 0, sumContextRec = 0;

        long sumTotalLatency = 0;
        long sumEmbeddingLatency = 0;
        long sumVectorSearchLatency = 0;
        long sumRerankingLatency = 0;
        long sumRetrievedChunks = 0;

        for (QueryBenchmarkResultDTO r : results) {
            if ("SUCCESS".equalsIgnoreCase(r.getStatus())) {
                successCount++;
                if (r.getQualityMetrics() != null && r.isHasGroundTruth()) {
                    groundTruthQueryCount++;
                    QueryQualityMetricsDTO qm = r.getQualityMetrics();
                    sumHr3 += qm.getHitRateAt3();
                    sumHr5 += qm.getHitRateAt5();
                    sumHr25 += qm.getHitRateAt25();

                    sumRec3 += qm.getRecallAt3();
                    sumRec5 += qm.getRecallAt5();
                    sumRec25 += qm.getRecallAt25();

                    sumPrec3 += qm.getPrecisionAt3();
                    sumPrec5 += qm.getPrecisionAt5();
                    sumPrec25 += qm.getPrecisionAt25();

                    sumMrr += qm.getReciprocalRank();

                    sumNdcg3 += qm.getNdcgAt3();
                    sumNdcg5 += qm.getNdcgAt5();
                    sumNdcg25 += qm.getNdcgAt25();

                    sumContextPrec += qm.getContextPrecision();
                    sumContextRec += qm.getContextRecall();
                }
            } else {
                failureCount++;
            }

            if (r.getOperationalMetrics() != null) {
                sumTotalLatency += r.getOperationalMetrics().getTotalLatencyMs();
                sumEmbeddingLatency += r.getOperationalMetrics().getEmbeddingLatencyMs();
                sumVectorSearchLatency += r.getOperationalMetrics().getVectorSearchLatencyMs();
                sumRerankingLatency += r.getOperationalMetrics().getRerankingLatencyMs();
                sumRetrievedChunks += r.getOperationalMetrics().getRetrievedChunkCount();
            }
        }

        summary.setSuccessfulQueries(successCount);
        summary.setFailedQueries(failureCount);
        summary.setFailureCount(failureCount);

        if (groundTruthQueryCount > 0) {
            summary.setHasGroundTruth(true);
            int n = groundTruthQueryCount;
            summary.setHitRateAt3(round(sumHr3 / n));
            summary.setHitRateAt5(round(sumHr5 / n));
            summary.setHitRateAt25(round(sumHr25 / n));

            summary.setRecallAt3(round(sumRec3 / n));
            summary.setRecallAt5(round(sumRec5 / n));
            summary.setRecallAt25(round(sumRec25 / n));

            summary.setPrecisionAt3(round(sumPrec3 / n));
            summary.setPrecisionAt5(round(sumPrec5 / n));
            summary.setPrecisionAt25(round(sumPrec25 / n));

            summary.setMrr(round(sumMrr / n));

            summary.setNdcgAt3(round(sumNdcg3 / n));
            summary.setNdcgAt5(round(sumNdcg5 / n));
            summary.setNdcgAt25(round(sumNdcg25 / n));

            summary.setContextPrecision(round(sumContextPrec / n));
            summary.setContextRecall(round(sumContextRec / n));
        } else {
            summary.setHasGroundTruth(false);
        }

        int nTotal = results.size();
        summary.setAvgTotalLatencyMs(round((double) sumTotalLatency / nTotal));
        summary.setAvgEmbeddingLatencyMs(round((double) sumEmbeddingLatency / nTotal));
        summary.setAvgVectorSearchLatencyMs(round((double) sumVectorSearchLatency / nTotal));
        summary.setAvgRerankingLatencyMs(round((double) sumRerankingLatency / nTotal));
        summary.setAvgRetrievedChunks(round((double) sumRetrievedChunks / nTotal));

        return summary;
    }

    // ── Metric Calculation Helpers ────────────────────────────

    public double computeHitRate(List<Integer> grades, int k) {
        int limit = Math.min(k, grades.size());
        for (int i = 0; i < limit; i++) {
            if (grades.get(i) > 0) {
                return 1.0;
            }
        }
        return 0.0;
    }

    public double computeRecall(List<Integer> grades, int k, int totalRelevantGt,
                                List<Document> documents, List<RelevantChunkDefinition> groundTruths) {
        if (totalRelevantGt == 0) return 0.0;
        int limit = Math.min(k, documents.size());

        Set<RelevantChunkDefinition> matchedAtK = new HashSet<>();
        for (int i = 0; i < limit; i++) {
            Document doc = documents.get(i);
            for (RelevantChunkDefinition gt : groundTruths) {
                if (gt.getRelevance() > 0 && matches(doc, gt)) {
                    matchedAtK.add(gt);
                }
            }
        }
        return (double) matchedAtK.size() / totalRelevantGt;
    }

    public double computePrecision(List<Integer> grades, int k) {
        if (k <= 0) return 0.0;
        int limit = Math.min(k, grades.size());
        long relevantCount = 0;
        for (int i = 0; i < limit; i++) {
            if (grades.get(i) > 0) {
                relevantCount++;
            }
        }
        return (double) relevantCount / k;
    }

    public double computeReciprocalRank(List<Integer> grades) {
        for (int i = 0; i < grades.size(); i++) {
            if (grades.get(i) > 0) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }

    public double computeNdcg(List<Integer> grades, int k, List<RelevantChunkDefinition> groundTruths) {
        if (k <= 0) return 0.0;
        int limit = Math.min(k, grades.size());

        // DCG@K
        double dcg = 0.0;
        for (int i = 0; i < limit; i++) {
            int grade = grades.get(i);
            if (grade > 0) {
                double gain = Math.pow(2.0, grade) - 1.0;
                double discount = Math.log(i + 2) / Math.log(2.0); // log2(i + 2) since i is 0-indexed
                dcg += gain / discount;
            }
        }

        // Ideal DCG@K (IDCG)
        List<Integer> idealGrades = new ArrayList<>();
        for (RelevantChunkDefinition gt : groundTruths) {
            if (gt.getRelevance() > 0) {
                idealGrades.add(gt.getRelevance());
            }
        }
        idealGrades.sort(Collections.reverseOrder());

        double idcg = 0.0;
        int idcgLimit = Math.min(k, idealGrades.size());
        for (int i = 0; i < idcgLimit; i++) {
            int grade = idealGrades.get(i);
            double gain = Math.pow(2.0, grade) - 1.0;
            double discount = Math.log(i + 2) / Math.log(2.0);
            idcg += gain / discount;
        }

        if (idcg == 0.0) {
            return 0.0;
        }

        return Math.min(1.0, dcg / idcg);
    }

    public double computeContextPrecision(List<Integer> grades) {
        if (grades == null || grades.isEmpty()) return 0.0;

        int totalRelevant = 0;
        double sumPrecision = 0.0;

        for (int k = 1; k <= grades.size(); k++) {
            if (grades.get(k - 1) > 0) {
                totalRelevant++;
                double precisionAtK = (double) totalRelevant / k;
                sumPrecision += precisionAtK;
            }
        }

        if (totalRelevant == 0) {
            return 0.0;
        }

        return sumPrecision / totalRelevant;
    }

    // ── Matching Logic ────────────────────────────────────────

    public boolean matches(Document doc, RelevantChunkDefinition gt) {
        if (doc == null || gt == null) return false;

        Map<String, Object> meta = doc.getMetadata();
        String docPath = normalizePath(String.valueOf(meta.getOrDefault("filePath", "")));
        String gtPath = normalizePath(gt.getFilePath());

        String elementName = String.valueOf(meta.getOrDefault("elementName", ""));
        String className = String.valueOf(meta.getOrDefault("className", ""));
        String methodName = String.valueOf(meta.getOrDefault("methodName", ""));
        String type = String.valueOf(meta.getOrDefault("type", "")).toUpperCase(Locale.ROOT);

        boolean pathMatches = false;
        if (!gtPath.isBlank() && !docPath.isBlank()) {
            pathMatches = docPath.equals(gtPath)
                    || docPath.endsWith("/" + gtPath)
                    || docPath.contains("/" + gtPath);
        }

        boolean symbolMatches = false;
        String gtSymbol = (gt.getSymbol() != null) ? gt.getSymbol().trim() : "";
        if (!gtSymbol.isBlank()) {
            boolean elemMatch = gtSymbol.equalsIgnoreCase(elementName)
                    || elementName.startsWith(gtSymbol + "#")
                    || elementName.startsWith(gtSymbol + "(")
                    || elementName.equalsIgnoreCase(gtSymbol + "()");
            boolean methodMatch = gtSymbol.equalsIgnoreCase(methodName)
                    || methodName.startsWith(gtSymbol + "(")
                    || methodName.equalsIgnoreCase(gtSymbol + "()");
            boolean classChunkMatch = ("CLASS".equals(type) || "INTERFACE".equals(type) || "RECORD".equals(type) || "ENUM".equals(type))
                    && gtSymbol.equalsIgnoreCase(className);

            symbolMatches = elemMatch || methodMatch || classChunkMatch;
        }

        // If both path and symbol are specified, both must match
        if (!gtPath.isBlank() && !gtSymbol.isBlank()) {
            return pathMatches && symbolMatches;
        }

        // If only path is specified
        if (!gtPath.isBlank()) {
            return pathMatches;
        }

        // If only symbol is specified
        if (!gtSymbol.isBlank()) {
            return symbolMatches;
        }

        return false;
    }

    private String normalizePath(String path) {
        if (path == null) return "";
        return path.replace('\\', '/').trim();
    }

    private double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}

