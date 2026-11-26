package com.shravan.jcode_intelligence.service.evaluation.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shravan.jcode_intelligence.llm.LLMClient;
import com.shravan.jcode_intelligence.llm.LLMResponse;
import com.shravan.jcode_intelligence.llm.PromptTemplateLoader;
import com.shravan.jcode_intelligence.service.evaluation.citation.Citation;
import com.shravan.jcode_intelligence.service.evaluation.citation.CitationParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Evaluates generated code intelligence answers using structured judge LLM calls.
 *
 * <p>Assesses faithfulness and ungrounded claims, question relevancy, approximate correctness
 * against ground-truth reference answers, and citation precision.
 * Citations are resolved against {@code suppliedDocuments} by file path and range overlap prior
 * to the judge invocation; unresolved citations are marked unsupported deterministically.
 */
@Component
public class GenerationEvaluator {

    private static final Logger log = LoggerFactory.getLogger(GenerationEvaluator.class);

    private final LLMClient llmClient;
    private final LlmRetryHelper retryHelper;
    private final PromptTemplateLoader templateLoader;
    private final CitationParser citationParser;
    private final ObjectMapper objectMapper;

    @Value("${astra.evaluation.temperature:0.0}")
    private double temperature = 0.0;

    @Autowired
    public GenerationEvaluator(
            LLMClient llmClient,
            LlmRetryHelper retryHelper,
            PromptTemplateLoader templateLoader,
            CitationParser citationParser,
            ObjectMapper objectMapper
    ) {
        this.llmClient = llmClient;
        this.retryHelper = (retryHelper != null) ? retryHelper : new LlmRetryHelper();
        this.templateLoader = templateLoader;
        this.citationParser = (citationParser != null) ? citationParser : new CitationParser();
        this.objectMapper = (objectMapper != null) ? objectMapper : new ObjectMapper();
    }

    public GenerationEvaluator(
            LLMClient llmClient,
            LlmRetryHelper retryHelper,
            PromptTemplateLoader templateLoader
    ) {
        this(llmClient, retryHelper, templateLoader, new CitationParser(), new ObjectMapper());
    }

    /**
     * Performs structured semantic evaluation of an AI-generated answer.
     *
     * @param query             user query
     * @param promptText        full prompt text passed to the generator (includes synthesized architecture content)
     * @param generatedAnswer   generated answer to evaluate
     * @param referenceAnswer   reference ground-truth answer (can be null)
     * @param suppliedDocuments documents budgeted and supplied to the generation prompt
     * @return structured evaluation result with calculated metrics or FAILED on error
     */
    public GenerationEvaluationResult evaluate(
            String query,
            String promptText,
            String generatedAnswer,
            String referenceAnswer,
            List<Document> suppliedDocuments
    ) {
        if (generatedAnswer == null || generatedAnswer.isBlank()) {
            return GenerationEvaluationResult.failed("Generated answer is empty or null");
        }

        try {
            // 1. Parse citations from generated answer
            List<Citation> citations = citationParser.parseCitations(generatedAnswer);
            int totalCitations = citations.size();

            // 2. Resolve citations against suppliedDocuments by path and range overlap
            List<ResolvedCitationInfo> resolvedCitations = new ArrayList<>();
            List<CitationVerdict> deterministicVerdicts = new ArrayList<>();

            for (int i = 0; i < citations.size(); i++) {
                Citation citation = citations.get(i);
                String sentence = extractSentence(generatedAnswer, citation.rawText());

                Document matchedDoc = resolveCitationToDocument(citation, suppliedDocuments);
                if (matchedDoc != null) {
                    resolvedCitations.add(new ResolvedCitationInfo(i, citation, sentence, matchedDoc));
                } else {
                    // Mark unresolved citations unsupported deterministically
                    deterministicVerdicts.add(new CitationVerdict(
                            i,
                            citation.rawText(),
                            sentence,
                            false,
                            null,
                            "UNSUPPORTED",
                            "Unresolved: no supplied chunk matches file path and overlapping line range"
                    ));
                }
            }

            // 3. Assemble prompt for judge
            String template = loadJudgeTemplate();
            String citationsSection = formatCitationsForJudge(resolvedCitations);
            boolean hasReference = (referenceAnswer != null && !referenceAnswer.isBlank());
            String refAnswerText = hasReference ? referenceAnswer : "None provided";

            String prompt = template
                    .replace("{{question}}", query != null ? query : "")
                    .replace("{{promptText}}", promptText != null ? promptText : "")
                    .replace("{{referenceAnswer}}", refAnswerText)
                    .replace("{{generatedAnswer}}", generatedAnswer)
                    .replace("{{citations}}", citationsSection);

            // 4. Call judge LLM with retry helper
            LLMResponse response = retryHelper.executeWithRetry(() ->
                    llmClient.generateWithUsage(prompt, null)
            );

            if (response == null || response.text() == null || response.text().isBlank()) {
                return GenerationEvaluationResult.failed("Judge LLM returned empty response");
            }

            // 5. Parse structured JSON from judge
            return parseJudgeResponse(
                    response.text(),
                    totalCitations,
                    resolvedCitations,
                    deterministicVerdicts,
                    hasReference,
                    response.latencyMs()
            );

        } catch (Exception e) {
            log.error("Generation evaluation failed: {}", e.getMessage(), e);
            return GenerationEvaluationResult.failed("Evaluation execution failed: " + e.getMessage());
        }
    }

    Document resolveCitationToDocument(Citation citation, List<Document> suppliedDocuments) {
        if (!citation.validSyntax() || citation.filePath() == null || suppliedDocuments == null) {
            return null;
        }

        String citePath = normalizePath(citation.filePath());
        int citeStart = (citation.startLine() != null) ? citation.startLine() : 1;
        int citeEnd = (citation.endLine() != null) ? citation.endLine() : citeStart;

        for (Document doc : suppliedDocuments) {
            String docPath = getDocFilePath(doc);
            if (docPath != null && pathMatches(citePath, docPath)) {
                Integer docStart = getDocLine(doc, "startLine");
                Integer docEnd = getDocLine(doc, "endLine");

                if (docStart == null || docEnd == null) {
                    // Synthetic or package summary without line ranges -> path match suffices
                    return doc;
                } else if (Math.max(citeStart, docStart) <= Math.min(citeEnd, docEnd)) {
                    // Line range overlap
                    return doc;
                }
            }
        }
        return null;
    }

    public String extractSentence(String text, String marker) {
        if (text == null || marker == null) return "";
        int idx = text.indexOf(marker);
        if (idx == -1) return marker;

        // Find start of sentence
        int start = 0;
        for (int i = idx - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == '\n' || ((c == '.' || c == '!' || c == '?') && i + 1 < idx && Character.isWhitespace(text.charAt(i + 1)))) {
                start = (c == '\n') ? i + 1 : i + 2;
                break;
            }
        }

        // Find end of sentence
        int end = text.length();
        for (int i = idx + marker.length(); i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '.' || c == '!' || c == '?') {
                end = (c == '\n') ? i : i + 1;
                break;
            }
        }

        if (start >= end) {
            return marker;
        }
        return text.substring(start, end).trim();
    }

    private String formatCitationsForJudge(List<ResolvedCitationInfo> resolvedCitations) {
        if (resolvedCitations.isEmpty()) {
            return "No citations resolved to supplied context chunks.";
        }

        StringBuilder sb = new StringBuilder();
        for (ResolvedCitationInfo r : resolvedCitations) {
            sb.append(String.format("Citation #%d: %s\n", r.index(), r.citation().rawText()));
            sb.append(String.format("Cited Sentence: \"%s\"\n", r.sentence()));
            sb.append(String.format("Resolved Source File: %s\n", getDocFilePath(r.doc())));
            sb.append(String.format("Resolved Source Chunk Text:\n```\n%s\n```\n\n", r.doc().getText()));
        }
        return sb.toString();
    }

    private GenerationEvaluationResult parseJudgeResponse(
            String rawResponseText,
            int totalCitations,
            List<ResolvedCitationInfo> resolvedCitations,
            List<CitationVerdict> deterministicVerdicts,
            boolean hasReferenceAnswer,
            Long latencyMs
    ) {
        try {
            String json = rawResponseText.trim();
            if (json.startsWith("```")) {
                int firstNl = json.indexOf('\n');
                int lastFence = json.lastIndexOf("```");
                if (firstNl != -1 && lastFence > firstNl) {
                    json = json.substring(firstNl + 1, lastFence).trim();
                }
            }

            JsonNode root = objectMapper.readTree(json);

            // 1. Claims and Faithfulness
            List<ClaimVerdict> claimVerdicts = new ArrayList<>();
            int groundedCount = 0;
            JsonNode claimsNode = root.get("claims");
            if (claimsNode != null && claimsNode.isArray()) {
                for (JsonNode cNode : claimsNode) {
                    String claim = cNode.has("claim") ? cNode.get("claim").asText() : "";
                    String verdict = cNode.has("verdict") ? cNode.get("verdict").asText() : "UNGROUNDED";
                    ClaimVerdict cv = new ClaimVerdict(claim, verdict);
                    claimVerdicts.add(cv);
                    if (cv.isGrounded()) {
                        groundedCount++;
                    }
                }
            }

            int totalClaims = claimVerdicts.size();
            int ungroundedClaims = totalClaims - groundedCount;
            Double faithfulness = (totalClaims > 0) ? (double) groundedCount / totalClaims : 1.0;

            // 2. Ratings
            Double relevancy = null;
            if (root.has("relevancyRating") && !root.get("relevancyRating").isNull()) {
                relevancy = normalizeRating(root.get("relevancyRating").asDouble());
            }

            Double correctness = null;
            if (hasReferenceAnswer && root.has("correctnessRating") && !root.get("correctnessRating").isNull()) {
                correctness = normalizeRating(root.get("correctnessRating").asDouble());
            }

            // 3. Citation verdicts and Citation Precision
            Map<Integer, String> judgeVerdicts = new HashMap<>();
            Map<Integer, String> judgeRationales = new HashMap<>();
            JsonNode citVerdictsNode = root.get("citationVerdicts");
            if (citVerdictsNode != null && citVerdictsNode.isArray()) {
                for (JsonNode cvNode : citVerdictsNode) {
                    int idx = cvNode.has("citationIndex") ? cvNode.get("citationIndex").asInt() : -1;
                    String v = cvNode.has("verdict") ? cvNode.get("verdict").asText() : "UNSUPPORTED";
                    String r = cvNode.has("rationale") ? cvNode.get("rationale").asText() : "";
                    if (idx >= 0) {
                        judgeVerdicts.put(idx, v);
                        judgeRationales.put(idx, r);
                    }
                }
            }

            List<CitationVerdict> allCitationVerdicts = new ArrayList<>();
            int supportedCount = 0;

            for (ResolvedCitationInfo resolved : resolvedCitations) {
                String v = judgeVerdicts.getOrDefault(resolved.index(), "UNSUPPORTED");
                String r = judgeRationales.getOrDefault(resolved.index(), "Evaluated against supplied chunk");
                CitationVerdict verdict = new CitationVerdict(
                        resolved.index(),
                        resolved.citation().rawText(),
                        resolved.sentence(),
                        true,
                        getDocFilePath(resolved.doc()),
                        v,
                        r
                );
                allCitationVerdicts.add(verdict);
                if (verdict.isSupported()) {
                    supportedCount++;
                }
            }

            allCitationVerdicts.addAll(deterministicVerdicts);
            allCitationVerdicts.sort(Comparator.comparingInt(CitationVerdict::citationIndex));

            Double citationPrecision = (totalCitations > 0)
                    ? (double) supportedCount / totalCitations
                    : null;

            return new GenerationEvaluationResult(
                    "SUCCESS",
                    null,
                    faithfulness,
                    ungroundedClaims,
                    totalClaims,
                    relevancy,
                    correctness,
                    true,
                    citationPrecision,
                    totalCitations,
                    supportedCount,
                    claimVerdicts,
                    allCitationVerdicts,
                    latencyMs
            );
        } catch (Exception e) {
            log.error("Failed to parse judge JSON response: {}", e.getMessage());
            return GenerationEvaluationResult.failed("Failed to parse judge JSON response: " + e.getMessage());
        }
    }

    private Double normalizeRating(double score) {
        if (score <= 1.0 && score >= 0.0) {
            return score;
        }
        double normalized = (score - 1.0) / 4.0;
        return Math.max(0.0, Math.min(1.0, normalized));
    }

    private String loadJudgeTemplate() {
        if (templateLoader != null) {
            try {
                return templateLoader.getTemplate("evaluation/generation-judge.st");
            } catch (Exception ignored) {}
        }

        try (InputStream in = getClass().getClassLoader().getResourceAsStream("prompts/evaluation/generation-judge.st")) {
            if (in != null) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {}

        return """
                --- USER QUESTION ---
                {{question}}
                --- SUPPLIED CONTEXT (PROMPT TEXT) ---
                {{promptText}}
                --- REFERENCE ANSWER (GROUND TRUTH) ---
                {{referenceAnswer}}
                --- GENERATED ANSWER ---
                {{generatedAnswer}}
                --- INLINE CITATIONS TO VERIFY ---
                {{citations}}
                """;
    }

    private boolean pathMatches(String citePath, String docPath) {
        String p1 = normalizePath(citePath);
        String p2 = normalizePath(docPath);
        return p1.equals(p2) || p1.endsWith("/" + p2) || p2.endsWith("/" + p1);
    }

    private String normalizePath(String path) {
        if (path == null) return "";
        String p = path.replace('\\', '/').trim();
        while (p.startsWith("./")) {
            p = p.substring(2);
        }
        return p.toLowerCase(Locale.ROOT);
    }

    private String getDocFilePath(Document doc) {
        if (doc == null || doc.getMetadata() == null) return null;
        Object fp = doc.getMetadata().get("filePath");
        return (fp != null) ? fp.toString() : null;
    }

    private Integer getDocLine(Document doc, String key) {
        if (doc == null || doc.getMetadata() == null) return null;
        Object val = doc.getMetadata().get(key);
        if (val instanceof Number n) {
            return n.intValue();
        }
        if (val instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    record ResolvedCitationInfo(
            int index,
            Citation citation,
            String sentence,
            Document doc
    ) {}
}
