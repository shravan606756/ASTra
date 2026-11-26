package com.shravan.jcode_intelligence.config;

import com.shravan.jcode_intelligence.model.QueryIntent;
import com.shravan.jcode_intelligence.model.RetrievalStrategy;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.util.EnumMap;
import java.util.Map;

/**
 * Configuration for sparse BM25 and hybrid retrieval.
 *
 * <p>Controls BM25 tuning parameters (k1, b), fuzzy matching thresholds, field boosts,
 * storage directories for Lucene index generations, and retrieval strategy selection.
 */
@Configuration
public class RetrievalConfig {

    @Value("${astra.retrieval.strategy:HYBRID}")
    private RetrievalStrategy strategy = RetrievalStrategy.HYBRID;

    @Value("${astra.retrieval.sparse.enabled:true}")
    private boolean sparseEnabled = true;

    @Value("${astra.retrieval.sparse.index-dir:data/sparse-index}")
    private String indexDir = "data/sparse-index";

    @Value("${astra.retrieval.sparse.k1:1.2}")
    private float k1 = 1.2f;

    @Value("${astra.retrieval.sparse.b:0.75}")
    private float b = 0.75f;

    @Value("${astra.retrieval.sparse.fuzzy-min-term-length:4}")
    private int fuzzyMinTermLength = 4;

    @Value("${astra.retrieval.sparse.fuzzy-max-edits:1}")
    private int fuzzyMaxEdits = 1;

    @Value("${astra.retrieval.sparse.boost.content:1.0}")
    private float contentBoost = 1.0f;

    @Value("${astra.retrieval.sparse.boost.element-name:4.0}")
    private float elementNameBoost = 4.0f;

    @Value("${astra.retrieval.sparse.boost.class-name:2.0}")
    private float classNameBoost = 2.0f;

    @Value("${astra.retrieval.sparse.boost.file-path:1.5}")
    private float filePathBoost = 1.5f;

    @Value("${astra.retrieval.sparse.boost.signature:1.5}")
    private float signatureBoost = 1.5f;

    @Value("${astra.retrieval.rrf.k:60}")
    private int rrfK = 60;

    @Value("${astra.retrieval.rrf.weight.dense:0.5}")
    private double defaultDenseWeight = 0.5;

    @Value("${astra.retrieval.rrf.weight.sparse:0.5}")
    private double defaultSparseWeight = 0.5;

    @Value("${astra.retrieval.executor.core-pool-size:4}")
    private int executorCorePoolSize = 4;

    private final Map<QueryIntent, Double> denseIntentWeights = new EnumMap<>(QueryIntent.class);
    private final Map<QueryIntent, Double> sparseIntentWeights = new EnumMap<>(QueryIntent.class);

    public RetrievalConfig() {
        denseIntentWeights.put(QueryIntent.SEARCH, 0.3);
        sparseIntentWeights.put(QueryIntent.SEARCH, 0.7);
        denseIntentWeights.put(QueryIntent.ARCHITECTURE, 0.6);
        sparseIntentWeights.put(QueryIntent.ARCHITECTURE, 0.4);
        denseIntentWeights.put(QueryIntent.WORKFLOW, 0.6);
        sparseIntentWeights.put(QueryIntent.WORKFLOW, 0.4);
    }

    @PostConstruct
    public void validate() {
        if (strategy == null) {
            throw new IllegalArgumentException("astra.retrieval.strategy must not be null");
        }
        if (indexDir == null || indexDir.isBlank()) {
            throw new IllegalArgumentException("astra.retrieval.sparse.index-dir must not be blank");
        }
        if (Float.isNaN(k1) || k1 < 0.0f) {
            throw new IllegalArgumentException("astra.retrieval.sparse.k1 must be non-negative, got: " + k1);
        }
        if (Float.isNaN(b) || b < 0.0f || b > 1.0f) {
            throw new IllegalArgumentException("astra.retrieval.sparse.b must be between 0.0 and 1.0, got: " + b);
        }
        if (fuzzyMinTermLength < 1) {
            throw new IllegalArgumentException(
                    "astra.retrieval.sparse.fuzzy-min-term-length must be >= 1, got: " + fuzzyMinTermLength);
        }
        if (fuzzyMaxEdits < 0 || fuzzyMaxEdits > 2) {
            throw new IllegalArgumentException(
                    "astra.retrieval.sparse.fuzzy-max-edits must be between 0 and 2, got: " + fuzzyMaxEdits);
        }
        if (Float.isNaN(contentBoost) || contentBoost < 0.0f
                || Float.isNaN(elementNameBoost) || elementNameBoost < 0.0f
                || Float.isNaN(classNameBoost) || classNameBoost < 0.0f
                || Float.isNaN(filePathBoost) || filePathBoost < 0.0f
                || Float.isNaN(signatureBoost) || signatureBoost < 0.0f) {
            throw new IllegalArgumentException("Field boosts must be non-negative");
        }
        if (rrfK < 1) {
            throw new IllegalArgumentException("astra.retrieval.rrf.k must be >= 1, got: " + rrfK);
        }
        if (Double.isNaN(defaultDenseWeight) || defaultDenseWeight < 0.0) {
            throw new IllegalArgumentException("defaultDenseWeight must be non-negative, got: " + defaultDenseWeight);
        }
        if (Double.isNaN(defaultSparseWeight) || defaultSparseWeight < 0.0) {
            throw new IllegalArgumentException("defaultSparseWeight must be non-negative, got: " + defaultSparseWeight);
        }
        if (executorCorePoolSize < 1) {
            throw new IllegalArgumentException("astra.retrieval.executor.core-pool-size must be >= 1, got: " + executorCorePoolSize);
        }
    }

    public RetrievalStrategy getStrategy() {
        return strategy;
    }

    public void setStrategy(RetrievalStrategy strategy) {
        this.strategy = strategy;
    }

    public boolean isSparseEnabled() {
        return sparseEnabled;
    }

    public void setSparseEnabled(boolean sparseEnabled) {
        this.sparseEnabled = sparseEnabled;
    }

    public String getIndexDir() {
        return indexDir;
    }

    public void setIndexDir(String indexDir) {
        this.indexDir = indexDir;
    }

    public float getK1() {
        return k1;
    }

    public void setK1(float k1) {
        this.k1 = k1;
    }

    public float getB() {
        return b;
    }

    public void setB(float b) {
        this.b = b;
    }

    public int getFuzzyMinTermLength() {
        return fuzzyMinTermLength;
    }

    public void setFuzzyMinTermLength(int fuzzyMinTermLength) {
        this.fuzzyMinTermLength = fuzzyMinTermLength;
    }

    public int getFuzzyMaxEdits() {
        return fuzzyMaxEdits;
    }

    public void setFuzzyMaxEdits(int fuzzyMaxEdits) {
        this.fuzzyMaxEdits = fuzzyMaxEdits;
    }

    public float getContentBoost() {
        return contentBoost;
    }

    public void setContentBoost(float contentBoost) {
        this.contentBoost = contentBoost;
    }

    public float getElementNameBoost() {
        return elementNameBoost;
    }

    public void setElementNameBoost(float elementNameBoost) {
        this.elementNameBoost = elementNameBoost;
    }

    public float getClassNameBoost() {
        return classNameBoost;
    }

    public void setClassNameBoost(float classNameBoost) {
        this.classNameBoost = classNameBoost;
    }

    public float getFilePathBoost() {
        return filePathBoost;
    }

    public void setFilePathBoost(float filePathBoost) {
        this.filePathBoost = filePathBoost;
    }

    public float getSignatureBoost() {
        return signatureBoost;
    }

    public void setSignatureBoost(float signatureBoost) {
        this.signatureBoost = signatureBoost;
    }

    public int getRrfK() {
        return rrfK;
    }

    public void setRrfK(int rrfK) {
        this.rrfK = rrfK;
    }

    public double getDefaultDenseWeight() {
        return defaultDenseWeight;
    }

    public void setDefaultDenseWeight(double defaultDenseWeight) {
        this.defaultDenseWeight = defaultDenseWeight;
    }

    public double getDefaultSparseWeight() {
        return defaultSparseWeight;
    }

    public void setDefaultSparseWeight(double defaultSparseWeight) {
        this.defaultSparseWeight = defaultSparseWeight;
    }

    public int getExecutorCorePoolSize() {
        return executorCorePoolSize;
    }

    public void setExecutorCorePoolSize(int executorCorePoolSize) {
        this.executorCorePoolSize = executorCorePoolSize;
    }

    public double getDenseWeight(QueryIntent intent) {
        if (intent != null && denseIntentWeights.containsKey(intent)) {
            return denseIntentWeights.get(intent);
        }
        return defaultDenseWeight;
    }

    public double getSparseWeight(QueryIntent intent) {
        if (intent != null && sparseIntentWeights.containsKey(intent)) {
            return sparseIntentWeights.get(intent);
        }
        return defaultSparseWeight;
    }

    public void setDenseWeight(QueryIntent intent, double weight) {
        if (intent != null) {
            denseIntentWeights.put(intent, weight);
        }
    }

    public void setSparseWeight(QueryIntent intent, double weight) {
        if (intent != null) {
            sparseIntentWeights.put(intent, weight);
        }
    }
}
