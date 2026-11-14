package com.shravan.jcode_intelligence.service.classification;

import org.springframework.stereotype.Component;

/**
 * Default implementation of {@link MaxHeapProvider} that queries the running JVM's {@link Runtime}.
 */
@Component
public class RuntimeMaxHeapProvider implements MaxHeapProvider {

    @Override
    public long maxHeapBytes() {
        return Runtime.getRuntime().maxMemory();
    }
}
