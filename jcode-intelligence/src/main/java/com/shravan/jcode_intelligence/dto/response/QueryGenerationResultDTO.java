package com.shravan.jcode_intelligence.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.shravan.jcode_intelligence.service.evaluation.generation.CitationVerdict;
import com.shravan.jcode_intelligence.service.evaluation.generation.ClaimVerdict;

import java.util.ArrayList;
import java.util.List;

/**
 * Detailed generation evaluation result for a single benchmark query.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class QueryGenerationResultDTO {

    private String generatedAnswer;
    private Double faithfulness;
    private Integer ungroundedClaims;
    private Integer totalClaims;
    private Double relevancy;
    private Double correctness;
    private Boolean correctnessApproximate;
    private Double citationPrecision;
    private String status; // "SUCCESS" or "FAILED"
    private String failureReason;
    private List<ClaimVerdict> claimVerdicts = new ArrayList<>();
    private List<CitationVerdict> citationVerdicts = new ArrayList<>();

    public QueryGenerationResultDTO() {
    }

    public QueryGenerationResultDTO(String status, String failureReason) {
        this.status = status;
        this.failureReason = failureReason;
    }

    public String getGeneratedAnswer() {
        return generatedAnswer;
    }

    public void setGeneratedAnswer(String generatedAnswer) {
        this.generatedAnswer = generatedAnswer;
    }

    public Double getFaithfulness() {
        return faithfulness;
    }

    public void setFaithfulness(Double faithfulness) {
        this.faithfulness = faithfulness;
    }

    public Integer getUngroundedClaims() {
        return ungroundedClaims;
    }

    public void setUngroundedClaims(Integer ungroundedClaims) {
        this.ungroundedClaims = ungroundedClaims;
    }

    public Integer getTotalClaims() {
        return totalClaims;
    }

    public void setTotalClaims(Integer totalClaims) {
        this.totalClaims = totalClaims;
    }

    public Double getRelevancy() {
        return relevancy;
    }

    public void setRelevancy(Double relevancy) {
        this.relevancy = relevancy;
    }

    public Double getCorrectness() {
        return correctness;
    }

    public void setCorrectness(Double correctness) {
        this.correctness = correctness;
    }

    public Boolean getCorrectnessApproximate() {
        return correctnessApproximate;
    }

    public void setCorrectnessApproximate(Boolean correctnessApproximate) {
        this.correctnessApproximate = correctnessApproximate;
    }

    public Double getCitationPrecision() {
        return citationPrecision;
    }

    public void setCitationPrecision(Double citationPrecision) {
        this.citationPrecision = citationPrecision;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public List<ClaimVerdict> getClaimVerdicts() {
        return claimVerdicts;
    }

    public void setClaimVerdicts(List<ClaimVerdict> claimVerdicts) {
        this.claimVerdicts = (claimVerdicts != null) ? claimVerdicts : new ArrayList<>();
    }

    public List<CitationVerdict> getCitationVerdicts() {
        return citationVerdicts;
    }

    public void setCitationVerdicts(List<CitationVerdict> citationVerdicts) {
        this.citationVerdicts = (citationVerdicts != null) ? citationVerdicts : new ArrayList<>();
    }
}
