package com.shravan.jcode_intelligence.service.evaluation.generation;

import com.shravan.jcode_intelligence.dto.request.BenchmarkRequest;
import com.shravan.jcode_intelligence.dto.response.BenchmarkGenerationSummaryDTO;
import com.shravan.jcode_intelligence.dto.response.CitationValidationDTO;
import com.shravan.jcode_intelligence.dto.response.QueryBenchmarkResultDTO;
import com.shravan.jcode_intelligence.dto.response.QueryGenerationResultDTO;
import com.shravan.jcode_intelligence.llm.LLMClient;
import com.shravan.jcode_intelligence.llm.LLMResponse;
import com.shravan.jcode_intelligence.llm.PromptBuilder;
import com.shravan.jcode_intelligence.llm.PromptContextResult;
import com.shravan.jcode_intelligence.model.QueryIntent;
import com.shravan.jcode_intelligence.model.benchmark.BenchmarkQueryDefinition;
import com.shravan.jcode_intelligence.service.evaluation.citation.Citation;
import com.shravan.jcode_intelligence.service.evaluation.citation.CitationMetrics;
import com.shravan.jcode_intelligence.service.evaluation.citation.CitationMetricsCalculator;
import com.shravan.jcode_intelligence.service.evaluation.citation.CitationParser;
import com.shravan.jcode_intelligence.service.evaluation.citation.CitationValidationResult;
import com.shravan.jcode_intelligence.service.evaluation.citation.CitationValidator;
import com.shravan.jcode_intelligence.service.evaluation.citation.RevisionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Orchestrates batch evaluation of LLM code generation and inline citation verification.
 *
 * <p>Processes retained retrieval candidate documents in batches using paced and retry-protected
 * LLM invocations. Extracts and validates inline citations against git checkouts and ASTs,
 * performs structured judge evaluation for semantic quality, and cleanly separates generation
 * failures from retrieval status.</p>
 */
@Component
public class GenerationBenchmarkRunner {

    private static final Logger log = LoggerFactory.getLogger(GenerationBenchmarkRunner.class);

    private final PromptBuilder promptBuilder;
    private final LLMClient llmClient;
    private final LlmRetryHelper retryHelper;
    private final CitationParser citationParser;
    private final CitationValidator citationValidator;
    private final CitationMetricsCalculator citationMetricsCalculator;
    private final GenerationEvaluator generationEvaluator;
    private final int batchSize;

    /**
     * Retained benchmark query context from successful retrieval.
     */
    public record RetainedBenchmarkQuery(
            BenchmarkQueryDefinition queryDefinition,
            QueryBenchmarkResultDTO resultDto,
            List<Document> topKDocuments,
            QueryIntent resolvedIntent,
            String repositoryId
    ) {
    }

    @Autowired
    public GenerationBenchmarkRunner(
            PromptBuilder promptBuilder,
            LLMClient llmClient,
            LlmRetryHelper retryHelper,
            CitationParser citationParser,
            CitationValidator citationValidator,
            CitationMetricsCalculator citationMetricsCalculator,
            GenerationEvaluator generationEvaluator,
            @Value("${astra.evaluation.batch-size:10}") int batchSize
    ) {
        this.promptBuilder = promptBuilder;
        this.llmClient = llmClient;
        this.retryHelper = (retryHelper != null) ? retryHelper : new LlmRetryHelper();
        this.citationParser = (citationParser != null) ? citationParser : new CitationParser();
        this.citationValidator = (citationValidator != null) ? citationValidator : new CitationValidator();
        this.citationMetricsCalculator = (citationMetricsCalculator != null) ? citationMetricsCalculator : new CitationMetricsCalculator();
        this.generationEvaluator = generationEvaluator;
        this.batchSize = Math.max(1, batchSize);
    }

    public GenerationBenchmarkRunner(
            PromptBuilder promptBuilder,
            LLMClient llmClient,
            LlmRetryHelper retryHelper,
            CitationParser citationParser,
            CitationValidator citationValidator,
            CitationMetricsCalculator citationMetricsCalculator,
            GenerationEvaluator generationEvaluator
    ) {
        this(promptBuilder, llmClient, retryHelper, citationParser, citationValidator, citationMetricsCalculator, generationEvaluator, 10);
    }

    /**
     * Executes generation and citation evaluation across retained retrieval queries in batches.
     *
     * @param retainedQueries      queries with successful retrieval candidates
     * @param request              benchmark request payload containing options and source info
     * @param suiteSourceRevision  default source revision from suite definition
     * @return aggregated generation and citation summary
     */
    public BenchmarkGenerationSummaryDTO runGenerationEvaluation(
            List<RetainedBenchmarkQuery> retainedQueries,
            BenchmarkRequest request,
            String suiteSourceRevision
    ) {
        if (retainedQueries == null || retainedQueries.isEmpty()) {
            BenchmarkGenerationSummaryDTO emptySummary = new BenchmarkGenerationSummaryDTO();
            emptySummary.setTotalQueriesEvaluated(0);
            emptySummary.setBatchCount(0);
            return emptySummary;
        }

        int totalQueries = retainedQueries.size();
        List<List<RetainedBenchmarkQuery>> batches = partition(retainedQueries, batchSize);
        int batchCount = batches.size();

        log.info("Starting generation evaluation for {} queries in {} batch(es) (batch-size={})",
                totalQueries, batchCount, batchSize);

        String sourceRoot = (request != null) ? request.getSourceRoot() : null;

        for (int b = 0; b < batches.size(); b++) {
            List<RetainedBenchmarkQuery> batch = batches.get(b);
            log.debug("Processing generation evaluation batch {}/{} ({} queries)", b + 1, batchCount, batch.size());

            for (RetainedBenchmarkQuery retained : batch) {
                BenchmarkQueryDefinition bq = retained.queryDefinition();
                String effectiveRevision = resolveEffectiveRevision(request, bq, suiteSourceRevision);

                evaluateSingleGeneration(retained, sourceRoot, effectiveRevision);
            }
        }

        return aggregateGenerationSummary(retainedQueries, batchCount, sourceRoot, suiteSourceRevision);
    }

    private void evaluateSingleGeneration(
            RetainedBenchmarkQuery retained,
            String sourceRoot,
            String effectiveRevision
    ) {
        BenchmarkQueryDefinition bq = retained.queryDefinition();
        QueryBenchmarkResultDTO resultDto = retained.resultDto();

        try {
            // 1. Build prompt context with inline citation instructions
            PromptContextResult promptContext = promptBuilder.buildPromptContext(
                    bq.getQuery(),
                    retained.topKDocuments(),
                    retained.repositoryId(),
                    retained.resolvedIntent(),
                    true
            );
            String promptText = promptContext.promptText();
            List<Document> suppliedDocuments = promptContext.suppliedDocuments();

            // 2. Call usage-aware LLM with pacing and retry
            LLMResponse genResponse = retryHelper.executeWithRetry(() ->
                    llmClient.generateWithUsage(promptText, null)
            );

            if (genResponse == null || genResponse.text() == null || genResponse.text().isBlank()) {
                throw new IllegalStateException("LLM returned empty or null response");
            }

            String answerText = genResponse.text();
            Long genLatency = genResponse.latencyMs();
            Integer pTokens = genResponse.promptTokens();
            Integer cTokens = genResponse.completionTokens();
            Integer tTokens = (pTokens != null || cTokens != null)
                    ? ((pTokens != null ? pTokens : 0) + (cTokens != null ? cTokens : 0))
                    : null;

            resultDto.setGenerationLatencyMs(genLatency);
            resultDto.setPromptTokens(pTokens);
            resultDto.setCompletionTokens(cTokens);
            resultDto.setTotalTokens(tTokens);

            // 3. Parse citations from generated answer
            List<Citation> parsedCitations = citationParser.parseCitations(answerText);

            // 4. Validate citations against checkout and compute deterministic citation metrics
            RevisionStatus revStatus = RevisionStatus.UNAVAILABLE;
            if (sourceRoot != null && !sourceRoot.isBlank()) {
                revStatus = citationValidator.checkRevision(Paths.get(sourceRoot), effectiveRevision);
            }

            List<CitationValidationResult> validationResults = citationValidator.validateAll(
                    parsedCitations, sourceRoot, effectiveRevision
            );

            CitationMetrics citationMetrics = citationMetricsCalculator.calculateMetrics(
                    validationResults, bq.getExpectedCitations(), revStatus
            );

            CitationValidationDTO citationDto = new CitationValidationDTO();
            citationDto.setTotalCitations(citationMetrics.totalCitations());
            citationDto.setValidSyntaxCitations(citationMetrics.validSyntaxCitations());
            citationDto.setStructurallyValidCitations(citationMetrics.structurallyValidCitations());
            citationDto.setCitationCorrectness(citationMetrics.precision());
            citationDto.setExpectedSourceRecall(citationMetrics.recall());
            citationDto.setTotalExpectedSources(citationMetrics.totalExpectedSources());
            citationDto.setMatchedExpectedSources(citationMetrics.matchedExpectedSources());
            citationDto.setRevisionStatus(revStatus != null ? revStatus.name() : null);
            citationDto.setVerdicts(validationResults);
            resultDto.setCitationValidation(citationDto);

            // 5. Semantic evaluation via judge LLM
            GenerationEvaluationResult evalResult = generationEvaluator.evaluate(
                    bq.getQuery(), promptText, answerText, bq.getReferenceAnswer(), suppliedDocuments
            );

            resultDto.setJudgeLatencyMs(evalResult.judgeLatencyMs());

            QueryGenerationResultDTO genDto = new QueryGenerationResultDTO();
            genDto.setGeneratedAnswer(answerText);
            genDto.setStatus(evalResult.status());
            genDto.setFailureReason(evalResult.failureReason());
            genDto.setFaithfulness(evalResult.faithfulness());
            genDto.setUngroundedClaims(evalResult.ungroundedClaims());
            genDto.setTotalClaims(evalResult.totalClaims());
            genDto.setRelevancy(evalResult.relevancy());
            genDto.setCorrectness(evalResult.correctness());
            genDto.setCorrectnessApproximate(evalResult.correctnessApproximate());
            genDto.setCitationPrecision(evalResult.citationPrecision());
            genDto.setClaimVerdicts(evalResult.claimVerdicts());
            genDto.setCitationVerdicts(evalResult.citationVerdicts());

            resultDto.setGenerationResult(genDto);

            if (!evalResult.isSuccess()) {
                log.warn("Generation evaluation failed for query '{}': {}", bq.getQueryId(), evalResult.failureReason());
            }

        } catch (Exception e) {
            log.warn("Generation evaluation failed for query '{}': {}", bq.getQueryId(), e.getMessage());
            QueryGenerationResultDTO genDto = new QueryGenerationResultDTO("FAILED", e.getMessage());
            resultDto.setGenerationResult(genDto);
        }
    }

    private BenchmarkGenerationSummaryDTO aggregateGenerationSummary(
            List<RetainedBenchmarkQuery> retainedQueries,
            int batchCount,
            String sourceRoot,
            String suiteSourceRevision
    ) {
        BenchmarkGenerationSummaryDTO summary = new BenchmarkGenerationSummaryDTO();
        summary.setTotalQueriesEvaluated(retainedQueries.size());
        summary.setBatchCount(batchCount);

        int successfulGenerations = 0;
        int failedGenerations = 0;

        double sumGenLatency = 0.0;
        int countGenLatency = 0;

        double sumJudgeLatency = 0.0;
        int countJudgeLatency = 0;

        long sumPromptTokens = 0L;
        long sumCompletionTokens = 0L;
        long sumTotalTokens = 0L;
        boolean hasAnyTokens = false;
        int countQueriesWithTokens = 0;

        double sumFaithfulness = 0.0;
        int countFaithfulness = 0;

        double sumRelevancy = 0.0;
        int countRelevancy = 0;

        double sumCorrectness = 0.0;
        int countCorrectness = 0;

        double sumCitationPrecision = 0.0;
        int countCitationPrecision = 0;

        int totalUngrounded = 0;
        boolean hasUngrounded = false;

        double sumCitationCorrectness = 0.0;
        int countCitationCorrectness = 0;

        double sumExpectedRecall = 0.0;
        int countExpectedRecall = 0;

        int totalAttemptedCitations = 0;
        int totalValidSyntaxCitations = 0;
        int totalStructurallyValidCitations = 0;
        Set<String> observedRevisionStatuses = new LinkedHashSet<>();

        for (RetainedBenchmarkQuery rq : retainedQueries) {
            QueryBenchmarkResultDTO qr = rq.resultDto();
            QueryGenerationResultDTO gen = qr.getGenerationResult();
            CitationValidationDTO cite = qr.getCitationValidation();

            if (gen != null && "SUCCESS".equalsIgnoreCase(gen.getStatus())) {
                successfulGenerations++;

                if (qr.getGenerationLatencyMs() != null) {
                    sumGenLatency += qr.getGenerationLatencyMs();
                    countGenLatency++;
                }
                if (qr.getJudgeLatencyMs() != null) {
                    sumJudgeLatency += qr.getJudgeLatencyMs();
                    countJudgeLatency++;
                }

                if (gen.getFaithfulness() != null) {
                    sumFaithfulness += gen.getFaithfulness();
                    countFaithfulness++;
                }
                if (gen.getRelevancy() != null) {
                    sumRelevancy += gen.getRelevancy();
                    countRelevancy++;
                }
                if (gen.getCorrectness() != null) {
                    sumCorrectness += gen.getCorrectness();
                    countCorrectness++;
                }
                if (gen.getCitationPrecision() != null) {
                    sumCitationPrecision += gen.getCitationPrecision();
                    countCitationPrecision++;
                }
                if (gen.getUngroundedClaims() != null) {
                    totalUngrounded += gen.getUngroundedClaims();
                    hasUngrounded = true;
                }
            } else {
                failedGenerations++;
            }

            if (qr.getTotalTokens() != null) {
                hasAnyTokens = true;
                sumTotalTokens += qr.getTotalTokens();
                countQueriesWithTokens++;
                if (qr.getPromptTokens() != null) {
                    sumPromptTokens += qr.getPromptTokens();
                }
                if (qr.getCompletionTokens() != null) {
                    sumCompletionTokens += qr.getCompletionTokens();
                }
            }

            if (cite != null) {
                totalAttemptedCitations += cite.getTotalCitations();
                totalValidSyntaxCitations += cite.getValidSyntaxCitations();
                totalStructurallyValidCitations += cite.getStructurallyValidCitations();

                if (cite.getCitationCorrectness() != null) {
                    sumCitationCorrectness += cite.getCitationCorrectness();
                    countCitationCorrectness++;
                }
                if (cite.getExpectedSourceRecall() != null) {
                    sumExpectedRecall += cite.getExpectedSourceRecall();
                    countExpectedRecall++;
                }
                if (cite.getRevisionStatus() != null && !cite.getRevisionStatus().isBlank()) {
                    observedRevisionStatuses.add(cite.getRevisionStatus().trim().toUpperCase(Locale.ROOT));
                }
            }
        }

        if (observedRevisionStatuses.isEmpty() && sourceRoot != null && !sourceRoot.isBlank()) {
            try {
                RevisionStatus status = citationValidator.checkRevision(Paths.get(sourceRoot), suiteSourceRevision);
                if (status != null) {
                    observedRevisionStatuses.add(status.name());
                } else {
                    observedRevisionStatuses.add(RevisionStatus.UNAVAILABLE.name());
                }
            } catch (Exception e) {
                log.warn("Invalid source path or revision check failure in summary fallback for '{}': {}", sourceRoot, e.getMessage());
                observedRevisionStatuses.add(RevisionStatus.UNAVAILABLE.name());
            }
        }

        String resolvedRevisionStatus = null;
        if (observedRevisionStatuses.contains("DIRTY")) {
            resolvedRevisionStatus = "DIRTY";
        } else if (observedRevisionStatuses.contains("MISMATCHED")) {
            resolvedRevisionStatus = "MISMATCHED";
        } else if (observedRevisionStatuses.contains("UNAVAILABLE")) {
            resolvedRevisionStatus = "UNAVAILABLE";
        } else if (observedRevisionStatuses.contains("MATCHED")) {
            resolvedRevisionStatus = "MATCHED";
        }

        summary.setSuccessfulGenerations(successfulGenerations);
        summary.setFailedGenerations(failedGenerations);

        summary.setAverageGenerationLatencyMs(countGenLatency > 0 ? round4(sumGenLatency / countGenLatency) : null);
        summary.setAverageJudgeLatencyMs(countJudgeLatency > 0 ? round4(sumJudgeLatency / countJudgeLatency) : null);

        if (hasAnyTokens) {
            summary.setTotalPromptTokens(sumPromptTokens);
            summary.setTotalCompletionTokens(sumCompletionTokens);
            summary.setTotalTokens(sumTotalTokens);
            summary.setAverageTokensPerQuery(countQueriesWithTokens > 0 ? round4((double) sumTotalTokens / countQueriesWithTokens) : null);
        }

        summary.setAverageFaithfulness(countFaithfulness > 0 ? round4(sumFaithfulness / countFaithfulness) : null);
        summary.setAverageRelevancy(countRelevancy > 0 ? round4(sumRelevancy / countRelevancy) : null);
        summary.setAverageCorrectness(countCorrectness > 0 ? round4(sumCorrectness / countCorrectness) : null);
        summary.setCorrectnessApproximate(true);
        summary.setAverageCitationPrecision(countCitationPrecision > 0 ? round4(sumCitationPrecision / countCitationPrecision) : null);
        summary.setTotalUngroundedClaims(hasUngrounded ? totalUngrounded : null);

        summary.setAverageCitationCorrectness(countCitationCorrectness > 0 ? round4(sumCitationCorrectness / countCitationCorrectness) : null);
        summary.setAverageExpectedSourceRecall(countExpectedRecall > 0 ? round4(sumExpectedRecall / countExpectedRecall) : null);
        summary.setTotalCitationsAttempted(totalAttemptedCitations);
        summary.setTotalCitationsValidSyntax(totalValidSyntaxCitations);
        summary.setTotalCitationsStructurallyValid(totalStructurallyValidCitations);
        summary.setRevisionStatus(resolvedRevisionStatus);

        return summary;
    }

    private String resolveEffectiveRevision(BenchmarkRequest request, BenchmarkQueryDefinition bq, String suiteSourceRevision) {
        if (request != null && request.getSourceRevision() != null && !request.getSourceRevision().isBlank()) {
            return request.getSourceRevision().trim();
        }
        if (bq != null && bq.getSourceRevision() != null && !bq.getSourceRevision().isBlank()) {
            return bq.getSourceRevision().trim();
        }
        return (suiteSourceRevision != null && !suiteSourceRevision.isBlank()) ? suiteSourceRevision.trim() : null;
    }

    private <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> partitions = new ArrayList<>();
        if (list == null || list.isEmpty()) {
            return partitions;
        }
        for (int i = 0; i < list.size(); i += size) {
            partitions.add(new ArrayList<>(list.subList(i, Math.min(i + size, list.size()))));
        }
        return partitions;
    }

    private Double round4(Double value) {
        if (value == null || Double.isNaN(value) || Double.isInfinite(value)) {
            return null;
        }
        return Math.round(value * 10000.0) / 10000.0;
    }
}
