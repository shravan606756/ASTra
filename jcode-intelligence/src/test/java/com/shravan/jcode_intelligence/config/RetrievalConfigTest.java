package com.shravan.jcode_intelligence.config;

import com.shravan.jcode_intelligence.model.RetrievalStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RetrievalConfigTest {

    @Test
    @DisplayName("Default configuration satisfies validation and matches specified defaults")
    void testDefaults() {
        RetrievalConfig config = new RetrievalConfig();
        assertDoesNotThrow(config::validate);

        assertEquals(RetrievalStrategy.HYBRID, config.getStrategy());
        assertTrue(config.isSparseEnabled());
        assertEquals("data/sparse-index", config.getIndexDir());
        assertEquals(1.2f, config.getK1(), 0.001f);
        assertEquals(0.75f, config.getB(), 0.001f);
        assertEquals(4, config.getFuzzyMinTermLength());
        assertEquals(1, config.getFuzzyMaxEdits());
        assertEquals(1.0f, config.getContentBoost(), 0.001f);
        assertEquals(4.0f, config.getElementNameBoost(), 0.001f);
        assertEquals(2.0f, config.getClassNameBoost(), 0.001f);
        assertEquals(1.5f, config.getFilePathBoost(), 0.001f);
        assertEquals(1.5f, config.getSignatureBoost(), 0.001f);
        assertEquals(60, config.getRrfK());
        assertEquals(0.5, config.getDefaultDenseWeight(), 0.001);
        assertEquals(0.5, config.getDefaultSparseWeight(), 0.001);
        assertEquals(4, config.getExecutorCorePoolSize());
    }

    @Test
    @DisplayName("Validation rejects null strategy")
    void testValidationNullStrategy() {
        RetrievalConfig config = new RetrievalConfig();
        config.setStrategy(null);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, config::validate);
        assertTrue(ex.getMessage().contains("strategy"));
    }

    @Test
    @DisplayName("Validation rejects blank indexDir")
    void testValidationBlankIndexDir() {
        RetrievalConfig config = new RetrievalConfig();
        config.setIndexDir("   ");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, config::validate);
        assertTrue(ex.getMessage().contains("index-dir"));
    }

    @Test
    @DisplayName("Validation rejects negative k1")
    void testValidationNegativeK1() {
        RetrievalConfig config = new RetrievalConfig();
        config.setK1(-0.1f);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, config::validate);
        assertTrue(ex.getMessage().contains("k1"));
    }

    @Test
    @DisplayName("Validation rejects out of range b")
    void testValidationOutOfRangeB() {
        RetrievalConfig config = new RetrievalConfig();
        config.setB(-0.01f);
        assertThrows(IllegalArgumentException.class, config::validate);

        config.setB(1.01f);
        assertThrows(IllegalArgumentException.class, config::validate);

        config.setB(0.0f);
        assertDoesNotThrow(config::validate);

        config.setB(1.0f);
        assertDoesNotThrow(config::validate);
    }

    @Test
    @DisplayName("Validation rejects invalid fuzzy parameters")
    void testValidationFuzzyParameters() {
        RetrievalConfig config = new RetrievalConfig();
        config.setFuzzyMinTermLength(0);
        assertThrows(IllegalArgumentException.class, config::validate);

        config.setFuzzyMinTermLength(3);
        config.setFuzzyMaxEdits(-1);
        assertThrows(IllegalArgumentException.class, config::validate);

        config.setFuzzyMaxEdits(3);
        assertThrows(IllegalArgumentException.class, config::validate);

        config.setFuzzyMaxEdits(2);
        assertDoesNotThrow(config::validate);
    }

    @Test
    @DisplayName("Validation rejects negative boost values")
    void testValidationNegativeBoost() {
        RetrievalConfig config = new RetrievalConfig();
        config.setContentBoost(-0.5f);
        assertThrows(IllegalArgumentException.class, config::validate);
    }

    @Test
    @DisplayName("Validation rejects invalid RRF and executor parameters")
    void testValidationRrfAndExecutorParameters() {
        RetrievalConfig config = new RetrievalConfig();
        config.setRrfK(0);
        assertThrows(IllegalArgumentException.class, config::validate);

        config = new RetrievalConfig();
        config.setDefaultDenseWeight(-0.1);
        assertThrows(IllegalArgumentException.class, config::validate);

        config = new RetrievalConfig();
        config.setDefaultSparseWeight(-0.1);
        assertThrows(IllegalArgumentException.class, config::validate);

        config = new RetrievalConfig();
        config.setExecutorCorePoolSize(0);
        assertThrows(IllegalArgumentException.class, config::validate);
    }
}
