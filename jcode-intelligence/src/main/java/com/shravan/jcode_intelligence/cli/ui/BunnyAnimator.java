package com.shravan.jcode_intelligence.cli.ui;

public class BunnyAnimator {
    private BunnyState currentState = BunnyState.IDLE;
    private long stateStartTime = System.currentTimeMillis();

    public void setState(BunnyState state) {
        if (this.currentState != state) {
            this.currentState = state;
            this.stateStartTime = System.currentTimeMillis();
        }
    }

    public BunnyState getCurrentState() {
        return currentState;
    }

    public String[] getCurrentFrame() {
        BunnyAnimation animation = BunnyAnimation.get(currentState);
        long elapsed = System.currentTimeMillis() - stateStartTime;
        
        int totalFrames = animation.getFrames().size();
        int frameIndex = (int) (elapsed / animation.getFrameDelayMs());
        
        if (!animation.isLoop() && frameIndex >= totalFrames) {
            if (currentState == BunnyState.WELCOME || currentState == BunnyState.SUCCESS || 
                currentState == BunnyState.ERROR || currentState == BunnyState.CONFUSED) {
                setState(BunnyState.IDLE);
                return getCurrentFrame(); // transition and get the new frame
            }
            frameIndex = totalFrames - 1; // Hold on last frame if it doesn't auto-transition
        } else {
            frameIndex = frameIndex % totalFrames;
        }
        
        return animation.getFrames().get(frameIndex);
    }
}
