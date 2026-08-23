package com.shravan.jcode_intelligence.cli.ui;

public class ProgressAnimator {

    private static final int RENDER_HEIGHT = 4;

    private Thread animationThread;
    private volatile boolean running;
    private final BunnyAnimator bunnyAnimator = new BunnyAnimator();

    public void start(BunnyState state, String[] rotationMessages) {
        if (running) {
            return;
        }

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
                    System.out.print("\033[" + RENDER_HEIGHT + "A");
                }
                firstFrame = false;

                for (String line : frame) {
                    System.out.print("\033[2K\r");
                    System.out.println(ColorPalette.MUTED + line + ColorPalette.RESET);
                }

                String paddedMessage = "  " + message;
                paddedMessage += " ".repeat(Math.max(0, 60 - paddedMessage.length()));
                System.out.print("\033[2K\r");
                System.out.println(ColorPalette.TEXT + paddedMessage + ColorPalette.RESET);
                System.out.flush();

                try {
                    Thread.sleep(120);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            if (!firstFrame) {
                System.out.print("\033[" + RENDER_HEIGHT + "A");
                for (int i = 0; i < RENDER_HEIGHT; i++) {
                    System.out.print("\033[2K\r");
                    System.out.println();
                }
                System.out.print("\033[" + RENDER_HEIGHT + "A");
                System.out.flush();
            }
        });

        animationThread.start();
    }

    public void stop() {
        running = false;
        if (animationThread != null) {
            animationThread.interrupt();
            try {
                animationThread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            animationThread = null;
        }
    }
}
