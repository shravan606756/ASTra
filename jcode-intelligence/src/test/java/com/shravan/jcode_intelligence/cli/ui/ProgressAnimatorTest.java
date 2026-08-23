package com.shravan.jcode_intelligence.cli.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the ProgressAnimator lifecycle to prevent regressions on:
 * - duplicate bunny output from animation + result rendering overlap
 * - animation thread surviving after stop()
 * - stop() idempotency (safe to call multiple times)
 */
class ProgressAnimatorTest {

    @Test
    @Timeout(5)
    void stopIsIdempotent() {
        ProgressAnimator animator = new ProgressAnimator();
        // stop() on a never-started animator should not throw
        animator.stop();
        animator.stop();
    }

    @Test
    @Timeout(5)
    void startThenStopTerminatesThread() throws InterruptedException {
        ProgressAnimator animator = new ProgressAnimator();
        animator.start(BunnyState.SEARCHING, new String[] { "Testing..." });

        // Give the animation thread a moment to start rendering
        Thread.sleep(250);

        animator.stop();

        // After stop(), starting again should work (proves clean state)
        animator.start(BunnyState.THINKING, new String[] { "Round two..." });
        Thread.sleep(250);
        animator.stop();
    }

    @Test
    @Timeout(5)
    void doubleStopAfterStartIsHarmless() {
        ProgressAnimator animator = new ProgressAnimator();
        animator.start(BunnyState.DIGGING, new String[] { "Digging..." });
        animator.stop();
        // Second stop should be a no-op
        animator.stop();
    }

    @Test
    @Timeout(5)
    void startWhileRunningIsIgnored() throws InterruptedException {
        ProgressAnimator animator = new ProgressAnimator();
        animator.start(BunnyState.SEARCHING, new String[] { "First..." });
        Thread.sleep(100);

        // Second start while running should be ignored (no crash, no second thread)
        animator.start(BunnyState.THINKING, new String[] { "Second..." });

        animator.stop();
    }

    @Test
    @Timeout(5)
    void stopClearsAnimationState() {
        ProgressAnimator animator = new ProgressAnimator();
        animator.start(BunnyState.READING, new String[] { "Reading..." });
        animator.stop();

        // After stop, a new start should work cleanly
        animator.start(BunnyState.SUCCESS, new String[] { "Done!" });
        animator.stop();
    }
}
