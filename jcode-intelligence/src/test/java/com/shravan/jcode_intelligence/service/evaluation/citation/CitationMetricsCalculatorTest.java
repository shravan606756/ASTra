package com.shravan.jcode_intelligence.service.evaluation.citation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CitationMetricsCalculatorTest {

    private CitationMetricsCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new CitationMetricsCalculator();
    }

    @Test
    @DisplayName("Zero total citations returns null precision")
    void testZeroTotalCitations_returnsNullPrecision() {
        CitationMetrics metrics = calculator.calculateMetrics(List.of(), List.of("Service.java"));

        assertEquals(0, metrics.totalCitations());
        assertNull(metrics.precision(), "Precision must be null when total citations is 0");
        assertFalse(metrics.hasCitations());
        assertEquals(0.0, metrics.recall(), 0.0001);
    }

    @Test
    @DisplayName("Zero expected sources returns null recall")
    void testZeroExpectedSources_returnsNullRecall() {
        Citation c = Citation.valid("[cite: Service.java:1-5]", "Service.java", 1, 5);
        CitationValidationResult res = new CitationValidationResult(
                c, true, RevisionStatus.MATCHED, FileStatus.VALID, EntityStatus.NOT_APPLICABLE, "ok"
        );

        CitationMetrics metrics = calculator.calculateMetrics(List.of(res), List.of());

        assertEquals(1, metrics.totalCitations());
        assertEquals(1.0, metrics.precision(), 0.0001);
        assertNull(metrics.recall(), "Recall must be null when expected sources list is empty");
        assertFalse(metrics.hasExpectedSources());
    }

    @Test
    @DisplayName("Partial precision: malformed markers and invalid entities reduce precision")
    void testPartialPrecisionWithMalformedAndInvalidCitations() {
        Citation c1 = Citation.valid("[cite: A.java:1-5]", "A.java", 1, 5);
        CitationValidationResult r1 = new CitationValidationResult(
                c1, true, RevisionStatus.MATCHED, FileStatus.VALID, EntityStatus.NOT_APPLICABLE, "ok"
        );

        Citation c2 = Citation.valid("[cite: B.java:1-5#foo]", "B.java", 1, 5, "foo");
        CitationValidationResult r2 = new CitationValidationResult(
                c2, false, RevisionStatus.MATCHED, FileStatus.VALID, EntityStatus.ENTITY_NOT_FOUND, "missing"
        );

        Citation c3 = Citation.malformed("[cite: broken]");
        CitationValidationResult r3 = CitationValidationResult.malformed(c3, "syntax error");

        Citation c4 = Citation.valid("[cite: C.java:10-20]", "C.java", 10, 20);
        CitationValidationResult r4 = new CitationValidationResult(
                c4, true, RevisionStatus.MATCHED, FileStatus.VALID, EntityStatus.NOT_APPLICABLE, "ok"
        );

        List<CitationValidationResult> results = List.of(r1, r2, r3, r4);
        CitationMetrics metrics = calculator.calculateMetrics(results, List.of("A.java", "C.java"));

        assertEquals(4, metrics.totalCitations());
        assertEquals(3, metrics.validSyntaxCitations());
        assertEquals(2, metrics.structurallyValidCitations());
        assertEquals(0.5, metrics.precision(), 0.0001, "Precision should be 2/4 = 0.5");
        assertEquals(1.0, metrics.recall(), 0.0001, "Both expected sources A.java and C.java were cited");
    }

    @Test
    @DisplayName("Partial recall: only a subset of expected sources cited")
    void testPartialRecallWithSubsetOfExpectedSources() {
        Citation c = Citation.valid("[cite: com/example/A.java:1-10]", "com/example/A.java", 1, 10);
        CitationValidationResult r = new CitationValidationResult(
                c, true, RevisionStatus.MATCHED, FileStatus.VALID, EntityStatus.NOT_APPLICABLE, "ok"
        );

        List<String> expected = List.of("A.java", "B.java");
        CitationMetrics metrics = calculator.calculateMetrics(List.of(r), expected);

        assertEquals(1, metrics.totalCitations());
        assertEquals(1.0, metrics.precision(), 0.0001);
        assertEquals(2, metrics.totalExpectedSources());
        assertEquals(1, metrics.matchedExpectedSources());
        assertEquals(0.5, metrics.recall(), 0.0001, "Recall should be 1/2 = 0.5");
    }

    @Test
    @DisplayName("Both precision and recall are null when both lists are empty")
    void testBothNullWhenEmpty() {
        CitationMetrics metrics = calculator.calculateMetrics(List.of(), List.of());

        assertNull(metrics.precision());
        assertNull(metrics.recall());
        assertEquals(0, metrics.totalCitations());
        assertEquals(0, metrics.totalExpectedSources());
    }
}
