package com.shravan.jcode_intelligence.service.reranking;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.shravan.jcode_intelligence.model.QueryIntent;
import com.shravan.jcode_intelligence.service.RetrievalReranker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Opt-in Cross-Encoder Reranker using ONNX Runtime and DJL HuggingFace Tokenizers.
 *
 * <p>Execution Cascade:
 * <ol>
 *   <li>Runs intent-aware heuristic scoring first on all candidate documents.</li>
 *   <li>Selects the top-N candidates (bounded by {@code maxCandidates}, default 10).</li>
 *   <li>Formats chunk text prefixing class, element, and signature metadata.</li>
 *   <li>Batch-encodes query/chunk pairs and executes ONNX inference against a lazily loaded session.</li>
 *   <li>Sorts scored prefix descending by cross-encoder logit, appends remainder candidates in heuristic order.</li>
 *   <li>Deterministically truncates final results to {@code finalTopK}.</li>
 * </ol>
 *
 * <p>Fault Tolerance:
 * If the model file or tokenizer is missing, or if ONNX inference fails, logs a warning and
 * transparently falls back to heuristic reranking order without failing the retrieval pipeline.
 */
@Component("crossEncoderReranker")
@ConditionalOnProperty(name = "astra.reranker.cross-encoder.enabled", havingValue = "true")
public class CrossEncoderReranker implements RerankingStrategy, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(CrossEncoderReranker.class);

    private final RetrievalReranker heuristicReranker;
    private final boolean enabled;
    private final String modelPath;
    private final String tokenizerPath;
    private final int sequenceLength;
    private final int maxCandidates;

    private volatile boolean initialized = false;
    private volatile boolean initFailed = false;
    private OrtEnvironment env;
    private OrtSession session;
    private HuggingFaceTokenizer tokenizer;

    @Autowired
    public CrossEncoderReranker(
            @Autowired(required = false) RetrievalReranker heuristicReranker,
            @Value("${astra.reranker.cross-encoder.enabled:false}") boolean enabled,
            @Value("${astra.reranker.cross-encoder.model-path:models/ms-marco-MiniLM-L-6-v2.onnx}") String modelPath,
            @Value("${astra.reranker.cross-encoder.tokenizer-path:models/tokenizer.json}") String tokenizerPath,
            @Value("${astra.reranker.cross-encoder.sequence-length:512}") int sequenceLength,
            @Value("${astra.reranker.cross-encoder.max-candidates:10}") int maxCandidates) {
        this.heuristicReranker = heuristicReranker;
        this.enabled = enabled;
        this.modelPath = (modelPath != null && !modelPath.isBlank()) ? modelPath : "models/ms-marco-MiniLM-L-6-v2.onnx";
        this.tokenizerPath = (tokenizerPath != null && !tokenizerPath.isBlank()) ? tokenizerPath : "models/tokenizer.json";
        this.sequenceLength = (sequenceLength > 0) ? sequenceLength : 512;
        this.maxCandidates = (maxCandidates > 0) ? maxCandidates : 10;
    }

    public CrossEncoderReranker(
            RetrievalReranker heuristicReranker,
            String modelPath,
            String tokenizerPath,
            int sequenceLength,
            int maxCandidates) {
        this(heuristicReranker, true, modelPath, tokenizerPath, sequenceLength, maxCandidates);
    }

    public CrossEncoderReranker(
            RetrievalReranker heuristicReranker,
            String modelPath,
            String tokenizerPath) {
        this(heuristicReranker, true, modelPath, tokenizerPath, 512, 10);
    }

    public CrossEncoderReranker(RetrievalReranker heuristicReranker) {
        this(heuristicReranker, true, "models/ms-marco-MiniLM-L-6-v2.onnx", "models/tokenizer.json", 512, 10);
    }

    @Override
    public String name() {
        return "cross-encoder";
    }

    @Override
    public List<Document> rerank(List<Document> documents, QueryIntent intent, String targetSymbol, int finalTopK) {
        return rerank(documents, null, intent, targetSymbol, finalTopK);
    }

    @Override
    public List<Document> rerank(List<Document> documents, String query, QueryIntent intent, int finalTopK) {
        return rerank(documents, query, intent, null, finalTopK);
    }

    @Override
    public List<Document> rerank(List<Document> documents, String query, QueryIntent intent, String targetSymbol, int finalTopK) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }

        // Phase 1: Always execute intent-aware heuristic scoring first
        List<Document> heuristicRanked = (heuristicReranker != null)
                ? heuristicReranker.rerank(documents, intent, targetSymbol, documents.size())
                : new ArrayList<>(documents);

        if (!enabled) {
            return truncate(heuristicRanked, finalTopK);
        }

        if (query == null || query.isBlank()) {
            log.debug("Query is empty; falling back to heuristic reranking");
            return truncate(heuristicRanked, finalTopK);
        }

        // Phase 2: Ensure lazy ONNX session initialization
        if (!ensureInitialized()) {
            return truncate(heuristicRanked, finalTopK);
        }

        try {
            int candidateLimit = Math.min(heuristicRanked.size(), maxCandidates);
            if (candidateLimit == 0) {
                return truncate(heuristicRanked, finalTopK);
            }

            List<Document> prefixToScore = new ArrayList<>(heuristicRanked.subList(0, candidateLimit));
            List<Document> remainder = new ArrayList<>(heuristicRanked.subList(candidateLimit, heuristicRanked.size()));

            // Phase 3: Batch score query/chunk pairs
            float[] scores = scoreBatch(query, prefixToScore);

            // Phase 4: Sort scored prefix descending by logit, with stable tie-breaking
            List<ScoredDoc> scoredDocs = new ArrayList<>(candidateLimit);
            for (int i = 0; i < candidateLimit; i++) {
                scoredDocs.add(new ScoredDoc(prefixToScore.get(i), scores[i], i));
            }
            scoredDocs.sort((a, b) -> {
                int cmp = Float.compare(b.score, a.score);
                return cmp != 0 ? cmp : Integer.compare(a.originalIndex, b.originalIndex);
            });

            // Phase 5: Reassemble prefix + remainder
            List<Document> result = new ArrayList<>(heuristicRanked.size());
            for (ScoredDoc sd : scoredDocs) {
                result.add(sd.doc);
            }
            result.addAll(remainder);

            return truncate(result, finalTopK);
        } catch (Exception e) {
            log.error("Cross-encoder inference failed: {}. Falling back to heuristic reranking.", e.getMessage(), e);
            return truncate(heuristicRanked, finalTopK);
        }
    }

    /**
     * Batch-scores query/chunk pairs using the ONNX session.
     */
    private float[] scoreBatch(String query, List<Document> docs) throws Exception {
        int batchSize = docs.size();
        List<Encoding> encodings = new ArrayList<>(batchSize);
        int maxLen = 1;

        for (Document doc : docs) {
            String chunkText = formatChunkText(doc);
            Encoding enc = tokenizer.encode(query, chunkText);
            encodings.add(enc);
            int len = Math.min(enc.getIds().length, sequenceLength);
            if (len > maxLen) {
                maxLen = len;
            }
        }

        long[][] inputIds = new long[batchSize][maxLen];
        long[][] attentionMask = new long[batchSize][maxLen];
        long[][] tokenTypeIds = new long[batchSize][maxLen];

        for (int i = 0; i < batchSize; i++) {
            Encoding enc = encodings.get(i);
            long[] ids = enc.getIds();
            long[] att = enc.getAttentionMask();
            long[] types = enc.getTypeIds();

            int copyLen = Math.min(ids.length, maxLen);
            System.arraycopy(ids, 0, inputIds[i], 0, copyLen);
            if (att != null) {
                System.arraycopy(att, 0, attentionMask[i], 0, Math.min(att.length, maxLen));
            } else {
                Arrays.fill(attentionMask[i], 0, copyLen, 1L);
            }
            if (types != null) {
                System.arraycopy(types, 0, tokenTypeIds[i], 0, Math.min(types.length, maxLen));
            }
        }

        Map<String, OnnxTensor> inputs = new HashMap<>();
        try {
            Set<String> inputNames = session.getInputNames();
            if (inputNames.contains("input_ids")) {
                inputs.put("input_ids", OnnxTensor.createTensor(env, inputIds));
            }
            if (inputNames.contains("attention_mask")) {
                inputs.put("attention_mask", OnnxTensor.createTensor(env, attentionMask));
            }
            if (inputNames.contains("token_type_ids")) {
                inputs.put("token_type_ids", OnnxTensor.createTensor(env, tokenTypeIds));
            }

            try (OrtSession.Result result = session.run(inputs)) {
                OnnxValue outputVal = result.get(0);
                Object val = outputVal.getValue();
                return extractScores(val, batchSize);
            }
        } finally {
            for (OnnxTensor t : inputs.values()) {
                if (t != null) {
                    try {
                        t.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        }
    }

    /**
     * Extracts output scores from model result logits.
     * Supports both single-logit regression (ms-marco) and 2-class classification.
     */
    private float[] extractScores(Object val, int batchSize) {
        float[] scores = new float[batchSize];
        if (val instanceof float[][] f2) {
            for (int i = 0; i < batchSize && i < f2.length; i++) {
                if (f2[i] != null && f2[i].length > 0) {
                    scores[i] = (f2[i].length == 1) ? f2[i][0] : f2[i][1];
                }
            }
        } else if (val instanceof float[] f1) {
            System.arraycopy(f1, 0, scores, 0, Math.min(batchSize, f1.length));
        }
        return scores;
    }

    /**
     * Prefixes chunk text with class, element, and signature metadata.
     */
    public String formatChunkText(Document doc) {
        if (doc == null) {
            return "";
        }
        Map<String, Object> meta = doc.getMetadata();
        String className = (meta != null && meta.get("className") != null) ? String.valueOf(meta.get("className")).trim() : "";
        String elementName = (meta != null && meta.get("elementName") != null) ? String.valueOf(meta.get("elementName")).trim() : "";
        String signature = (meta != null && meta.get("signature") != null) ? String.valueOf(meta.get("signature")).trim() : "";
        String text = (doc.getText() != null) ? doc.getText() : "";

        StringBuilder sb = new StringBuilder();
        if (!className.isEmpty()) {
            sb.append("Class: ").append(className).append(" ");
        }
        if (!elementName.isEmpty()) {
            sb.append("Element: ").append(elementName).append(" ");
        }
        if (!signature.isEmpty()) {
            sb.append("Signature: ").append(signature).append(" ");
        }
        if (sb.length() > 0) {
            sb.append("\n");
        }
        sb.append(text);
        return sb.toString();
    }

    /**
     * Lazy, thread-safe initialization of the ONNX Runtime session and HuggingFace Tokenizer.
     */
    private synchronized boolean ensureInitialized() {
        if (initialized) {
            return !initFailed;
        }

        try {
            Path mPath = Paths.get(modelPath);
            Path tPath = Paths.get(tokenizerPath);

            if (!Files.exists(mPath)) {
                log.warn("Cross-encoder ONNX model file not found at: {}. Falling back to heuristic reranking.", modelPath);
                initFailed = true;
                initialized = true;
                return false;
            }

            if (!Files.exists(tPath)) {
                log.warn("Cross-encoder tokenizer file not found at: {}. Falling back to heuristic reranking.", tokenizerPath);
                initFailed = true;
                initialized = true;
                return false;
            }

            this.env = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            this.session = env.createSession(mPath.toAbsolutePath().toString(), opts);
            this.tokenizer = HuggingFaceTokenizer.newInstance(tPath.toAbsolutePath());

            log.info("Cross-encoder ONNX session successfully initialized (model={}, tokenizer={}, seqLen={}, maxCandidates={})",
                    modelPath, tokenizerPath, sequenceLength, maxCandidates);
            initFailed = false;
        } catch (Throwable e) {
            log.warn("Failed to initialize Cross-Encoder ONNX session/tokenizer: {}. Falling back to heuristic reranking.", e.getMessage());
            initFailed = true;
        } finally {
            initialized = true;
        }
        return !initFailed;
    }

    private List<Document> truncate(List<Document> docs, int topK) {
        if (topK > 0 && docs.size() > topK) {
            return new ArrayList<>(docs.subList(0, topK));
        }
        return docs;
    }

    @Override
    public synchronized void close() {
        if (session != null) {
            try {
                session.close();
            } catch (Exception ignored) {
            }
            session = null;
        }
        if (tokenizer != null) {
            try {
                tokenizer.close();
            } catch (Exception ignored) {
            }
            tokenizer = null;
        }
        if (env != null) {
            try {
                env.close();
            } catch (Exception ignored) {
            }
            env = null;
        }
        initialized = false;
        initFailed = false;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getModelPath() {
        return modelPath;
    }

    public String getTokenizerPath() {
        return tokenizerPath;
    }

    public int getSequenceLength() {
        return sequenceLength;
    }

    public int getMaxCandidates() {
        return maxCandidates;
    }

    public boolean isInitialized() {
        return initialized && !initFailed;
    }

    private record ScoredDoc(Document doc, float score, int originalIndex) {}
}
