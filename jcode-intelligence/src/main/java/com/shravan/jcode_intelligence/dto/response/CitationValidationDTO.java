package com.shravan.jcode_intelligence.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.shravan.jcode_intelligence.service.evaluation.citation.CitationValidationResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic inline citation validation result for a single benchmark query.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CitationValidationDTO {

    private int totalCitations;
    private int validSyntaxCitations;
    private int structurallyValidCitations;
    private Double citationCorrectness;
    private Double expectedSourceRecall;
    private int totalExpectedSources;
    private int matchedExpectedSources;
    private String revisionStatus;
    private List<CitationValidationResult> verdicts = new ArrayList<>();

    public CitationValidationDTO() {
    }

    public int getTotalCitations() {
        return totalCitations;
    }

    public void setTotalCitations(int totalCitations) {
        this.totalCitations = totalCitations;
    }

    public int getValidSyntaxCitations() {
        return validSyntaxCitations;
    }

    public void setValidSyntaxCitations(int validSyntaxCitations) {
        this.validSyntaxCitations = validSyntaxCitations;
    }

    public int getStructurallyValidCitations() {
        return structurallyValidCitations;
    }

    public void setStructurallyValidCitations(int structurallyValidCitations) {
        this.structurallyValidCitations = structurallyValidCitations;
    }

    public Double getCitationCorrectness() {
        return citationCorrectness;
    }

    public void setCitationCorrectness(Double citationCorrectness) {
        this.citationCorrectness = citationCorrectness;
    }

    public Double getExpectedSourceRecall() {
        return expectedSourceRecall;
    }

    public void setExpectedSourceRecall(Double expectedSourceRecall) {
        this.expectedSourceRecall = expectedSourceRecall;
    }

    public int getTotalExpectedSources() {
        return totalExpectedSources;
    }

    public void setTotalExpectedSources(int totalExpectedSources) {
        this.totalExpectedSources = totalExpectedSources;
    }

    public int getMatchedExpectedSources() {
        return matchedExpectedSources;
    }

    public void setMatchedExpectedSources(int matchedExpectedSources) {
        this.matchedExpectedSources = matchedExpectedSources;
    }

    public String getRevisionStatus() {
        return revisionStatus;
    }

    public void setRevisionStatus(String revisionStatus) {
        this.revisionStatus = revisionStatus;
    }

    public List<CitationValidationResult> getVerdicts() {
        return verdicts;
    }

    public void setVerdicts(List<CitationValidationResult> verdicts) {
        this.verdicts = (verdicts != null) ? verdicts : new ArrayList<>();
    }
}
