package com.shravan.jcode_intelligence.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LargeRepoIndexingConfigTest {

    @Test
    @DisplayName("Default constructor uses 2000 batch size and passes validation")
    void testDefaultValues() {
        LargeRepoIndexingConfig config = new LargeRepoIndexingConfig();
        config.validate();
        assertEquals(2000, config.getBatchSize());
        assertEquals(0.25, config.getSafetyHeadroomFraction(), 0.001);
    }

    @Test
    @DisplayName("Valid positive batch size and safety headroom is accepted")
    void testValidBatchSize() {
        LargeRepoIndexingConfig config = new LargeRepoIndexingConfig(5000, 0.30);
        assertEquals(5000, config.getBatchSize());
        assertEquals(0.30, config.getSafetyHeadroomFraction(), 0.001);
    }

    @Test
    @DisplayName("Invalid safety headroom fractions are rejected")
    void testInvalidSafetyHeadroomRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                new LargeRepoIndexingConfig(2000, 0.0));
        assertThrows(IllegalArgumentException.class, () ->
                new LargeRepoIndexingConfig(2000, 1.0));
        assertThrows(IllegalArgumentException.class, () ->
                new LargeRepoIndexingConfig(2000, -0.1));
    }

    @Test
    @DisplayName("Zero batch size is rejected")
    void testZeroBatchSizeRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                new LargeRepoIndexingConfig(0));
        assertTrue(ex.getMessage().contains("must be positive"));
    }

    @Test
    @DisplayName("Negative batch size is rejected")
    void testNegativeBatchSizeRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                new LargeRepoIndexingConfig(-100));
        assertTrue(ex.getMessage().contains("must be positive"));
    }
}
