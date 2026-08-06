package com.shravan.jcode_intelligence.cli.ui;

public class ProgressAnimator {

    private Thread animationThread;
    private volatile boolean running;

    private static final String[] SPINNER_FRAMES = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};

    public void start(String[] rotationMessages) {
        if (running) return;
        running = true;
        
        animationThread = new Thread(() -> {
            int spinnerIndex = 0;
            int messageIndex = 0;
            long lastMessageChange = System.currentTimeMillis();
            
            while (running) {
                if (System.currentTimeMillis() - lastMessageChange > 2500) {
                    messageIndex = (messageIndex + 1) % rotationMessages.length;
                    lastMessageChange = System.currentTimeMillis();
                }
                
                String frame = SPINNER_FRAMES[spinnerIndex % SPINNER_FRAMES.length];
                String message = rotationMessages[messageIndex];
                
                // Print with carriage return and padding to overwrite previous line
                System.out.print("\r" + ColorPalette.ACCENT + "  " + ColorPalette.TEXT + message + ColorPalette.RESET + "          ");
                
                spinnerIndex++;
                try {
                    Thread.sleep(80);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            
            // Clear the line when done
            System.out.print("\r" + " ".repeat(80) + "\r");
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
