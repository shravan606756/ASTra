package com.shravan.jcode_intelligence.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests verifying tier level ordering, completeness, and descriptions in {@link AttributionTier}.
 */
class AttributionTierTest {

    @Test
    @DisplayName("Attribution tiers define strictly ordered levels from 1 to 4")
    void testTierLevelsAndOrder() {
        assertEquals(1, AttributionTier.RETRIEVED_CHUNKS.getLevel());
        assertEquals(2, AttributionTier.SUPPLIED_CONTEXT.getLevel());
        assertEquals(3, AttributionTier.REFERENCED_RESOURCES.getLevel());
        assertEquals(4, AttributionTier.SUPPORTING_CITATIONS.getLevel());

        AttributionTier[] tiers = AttributionTier.values();
        assertEquals(4, tiers.length);
        assertEquals(AttributionTier.RETRIEVED_CHUNKS, tiers[0]);
        assertEquals(AttributionTier.SUPPLIED_CONTEXT, tiers[1]);
        assertEquals(AttributionTier.REFERENCED_RESOURCES, tiers[2]);
        assertEquals(AttributionTier.SUPPORTING_CITATIONS, tiers[3]);
    }

    @Test
    @DisplayName("Each attribution tier has a meaningful, non-blank description")
    void testTierDescriptions() {
        for (AttributionTier tier : AttributionTier.values()) {
            assertNotNull(tier.getDescription());
            assertFalse(tier.getDescription().isBlank());
        }
    }
}

