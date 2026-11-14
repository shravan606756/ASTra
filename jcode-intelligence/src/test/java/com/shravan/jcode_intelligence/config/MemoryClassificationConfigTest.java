package com.shravan.jcode_intelligence.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class MemoryClassificationConfigTest {

    @Test
    public void testDefaultConfigurationIsValid() {
        MemoryClassificationConfig config = new MemoryClassificationConfig();
        assertDoesNotThrow(config::validate);
        assertEquals(0.4, config.getBudgetFraction(), 1e-6);
        assertEquals(600L, config.getBytesPerLoc());
        assertNull(config.getNormalThreshold());
    }

    @Test
    public void testValidCustomConfiguration() {
        MemoryClassificationConfig config = new MemoryClassificationConfig(0.5, 1000L, 50000L);
        assertEquals(0.5, config.getBudgetFraction(), 1e-6);
        assertEquals(1000L, config.getBytesPerLoc());
        assertEquals(50000L, config.getNormalThreshold());
    }

    @Test
    public void testBudgetFractionBoundaries() {
        // 1.0 is valid
        assertDoesNotThrow(() -> new MemoryClassificationConfig(1.0, 500L, null));

        // Just above zero is valid
        assertDoesNotThrow(() -> new MemoryClassificationConfig(0.001, 500L, null));

        // <= 0.0 is invalid
        IllegalArgumentException exZero = assertThrows(IllegalArgumentException.class,
                () -> new MemoryClassificationConfig(0.0, 500L, null));
        assertTrue(exZero.getMessage().contains("budget-fraction must be in (0, 1]"));

        IllegalArgumentException exNeg = assertThrows(IllegalArgumentException.class,
                () -> new MemoryClassificationConfig(-0.1, 500L, null));
        assertTrue(exNeg.getMessage().contains("budget-fraction must be in (0, 1]"));

        // > 1.0 is invalid
        IllegalArgumentException exOver = assertThrows(IllegalArgumentException.class,
                () -> new MemoryClassificationConfig(1.01, 500L, null));
        assertTrue(exOver.getMessage().contains("budget-fraction must be in (0, 1]"));
    }

    @Test
    public void testBytesPerLocValidation() {
        // > 0 is valid
        assertDoesNotThrow(() -> new MemoryClassificationConfig(0.4, 1L, null));

        // <= 0 is invalid
        IllegalArgumentException exZero = assertThrows(IllegalArgumentException.class,
                () -> new MemoryClassificationConfig(0.4, 0L, null));
        assertTrue(exZero.getMessage().contains("bytes-per-loc must be positive"));

        IllegalArgumentException exNeg = assertThrows(IllegalArgumentException.class,
                () -> new MemoryClassificationConfig(0.4, -50L, null));
        assertTrue(exNeg.getMessage().contains("bytes-per-loc must be positive"));
    }

    @Test
    public void testNormalThresholdValidation() {
        // null is valid
        assertDoesNotThrow(() -> new MemoryClassificationConfig(0.4, 500L, null));

        // 0 is valid
        assertDoesNotThrow(() -> new MemoryClassificationConfig(0.4, 500L, 0L));

        // positive is valid
        assertDoesNotThrow(() -> new MemoryClassificationConfig(0.4, 500L, 100000L));

        // negative is invalid
        IllegalArgumentException exNeg = assertThrows(IllegalArgumentException.class,
                () -> new MemoryClassificationConfig(0.4, 500L, -1L));
        assertTrue(exNeg.getMessage().contains("normal-threshold must be non-negative"));
    }

    @Test
    public void testSetters() {
        MemoryClassificationConfig config = new MemoryClassificationConfig();
        config.setBudgetFraction(0.35);
        config.setBytesPerLoc(750L);
        config.setNormalThreshold(200000L);

        assertEquals(0.35, config.getBudgetFraction(), 1e-6);
        assertEquals(750L, config.getBytesPerLoc());
        assertEquals(200000L, config.getNormalThreshold());
        assertDoesNotThrow(config::validate);
    }
}
