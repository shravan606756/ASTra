package com.shravan.jcode_intelligence.service.classification;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class RuntimeMaxHeapProviderTest {

    @Test
    public void testMaxHeapBytesReturnsPositiveValue() {
        RuntimeMaxHeapProvider provider = new RuntimeMaxHeapProvider();
        long maxHeap = provider.maxHeapBytes();
        assertTrue(maxHeap > 0, "maxHeapBytes must be strictly positive");
        assertEquals(Runtime.getRuntime().maxMemory(), maxHeap);
    }
}
