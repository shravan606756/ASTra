package com.shravan.jcode_intelligence.cli.ui;

public class ProgressAnimator {

    private Thread animationThread;
    private volatile boolean running;
    private final BunnyAnimator bunnyAnimator = new BunnyAnimator();

    public void start(BunnyState state, String[] rotationMessages) {
        if (running) return;
        running = true;
        bunnyAnimator.setState(state);
        
        animationThread = new Thread(() -> {
            int messageIndex = 0;
            long lastMessageChange = System.currentTimeMillis();
            boolean firstFrame = true;
            
            while (running) {
                if (System.currentTimeMillis() - lastMessageChange > 2500) {
                    messageIndex = (messageIndex + 1) % rotationMessages.length;
                    lastMessageChange = System.currentTimeMillis();
                }
                
                String message = rotationMessages[messageIndex];
                String[] frame = bunnyAnimator.getCurrentFrame();
                
                if (!firstFrame) {
                    System.out.print("\033[4A"); // Move up 4 lines (3 for bunny, 1 for dialogue)
                }
                firstFrame = false;
                
                System.out.print(BunnyRenderer.renderFrame(frame) + "\n");
                // Ensure the line is long enough to overwrite any previous longer text
                String paddedMessage = "  " + message;
                paddedMessage += " ".repeat(Math.max(0, 60 - paddedMessage.length()));
                System.out.print(ColorPalette.TEXT + paddedMessage + ColorPalette.RESET + "\n");
                
                try {
                    Thread.sleep(120); // ~8-10 FPS
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            
            // Clear the 4 lines when done
            if (!firstFrame) {
                System.out.print("\033[4A");
                for (int i = 0; i < 4; i++) {
                    System.out.print("\033[2K\n");
                }
                System.out.print("\033[4A");
            }
        });
        
        animationThread.start();
    }

    public void stop() {
        running = false;
        if (animationThread != null) {
            try {
                animationThread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
