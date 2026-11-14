package com.shravan.jcode_intelligence.service.classification;

/**
 * Functional interface providing the maximum heap memory limit of the JVM in bytes.
 *
 * <p>Allows injecting fixed heap sizes in tests without modifying runtime JVM arguments.
 */
@FunctionalInterface
public interface MaxHeapProvider {

    /**
     * Returns the maximum heap memory available to the JVM in bytes.
     *
     * @return maximum heap memory in bytes
     */
    long maxHeapBytes();
}
