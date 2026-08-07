package com.shravan.jcode_intelligence.cli.ui;

import java.util.List;
import com.shravan.jcode_intelligence.cli.util.TerminalUtils;

public class ConsoleUI {
    private final ProgressRenderer progress = new ProgressRenderer();
    private final ProgressAnimator animator = new ProgressAnimator();
    private final AnswerFormatter answerFormatter = new AnswerFormatter();

    private Thread idleThread;
    private volatile boolean idleRunning;

    public void printBanner(List<String> tryCommands) {
        clearScreen();
        BunnyAnimator startupAnimator = new BunnyAnimator();
        startupAnimator.setState(BunnyState.WELCOME);
        
        System.out.println("\n");
        // Print placeholders for 3-line bunny
        for (int i = 0; i < 3; i++) System.out.println();
        
        long start = System.currentTimeMillis();
        boolean firstFrame = true;
        
        while (System.currentTimeMillis() - start < 800) {
            String[] frame = startupAnimator.getCurrentFrame();
            
            if (!firstFrame) {
                System.out.print("\033[3A"); // Move up 3 lines
            }
            firstFrame = false;
            
            System.out.print(BunnyRenderer.renderCenteredFrame(frame) + "\n");
            
            try {
                Thread.sleep(120);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        
        // Now print the welcome screen BELOW the bunny
        System.out.println("\n" + TerminalUtils.center(ColorPalette.ACCENT + "Welcome to ASTra" + ColorPalette.RESET, TerminalUtils.getTerminalWidth()));
        
        System.out.println("\n" + TerminalUtils.renderSeparator(""));
        System.out.println("\n" + TerminalUtils.center(ColorPalette.MUTED + "Try:" + ColorPalette.RESET, TerminalUtils.getTerminalWidth()));
        
        System.out.println(TerminalUtils.center(ColorPalette.TEXT + "help" + ColorPalette.RESET, TerminalUtils.getTerminalWidth()));
        
        System.out.println("\n" + TerminalUtils.renderSeparator("") + "\n");

        startIdleAnimation(13); // Bunny is 13 lines above the prompt
    }

    public void startIdleAnimation(int linesUp) {
        if (idleRunning) return;
        idleRunning = true;
        BunnyAnimator idleAnimator = new BunnyAnimator();
        idleAnimator.setState(BunnyState.IDLE);
        
        idleThread = new Thread(() -> {
            while (idleRunning) {
                try {
                    Thread.sleep(800);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (!idleRunning) break;
                
                String[] frame = idleAnimator.getCurrentFrame();
                // Save cursor, move up, print frame, restore cursor
                System.out.print("\033[s\033[" + linesUp + "A\r");
                System.out.print(BunnyRenderer.renderCenteredFrame(frame));
                System.out.print("\033[u");
                System.out.flush();
            }
        });
        idleThread.setDaemon(true);
        idleThread.start();
    }

    public void stopIdleAnimation() {
        idleRunning = false;
        if (idleThread != null) {
            idleThread.interrupt();
        }
    }

    public void printFarewell() {
        System.out.println("\n");
        showSignBunny("See you next compile!", BunnyState.GOODBYE);
        System.out.println("\n");
    }

    public void showBunny(BunnyState state) {
        System.out.println(BunnyRenderer.renderSmall(state));
        System.out.println("  " + ColorPalette.TEXT + BunnyDialogue.getDialogue(state) + ColorPalette.RESET);
        System.out.println("\n");
    }

    public void showSignBunny(String message, BunnyState state) {
        System.out.println(BunnyRenderer.renderBubble(state, message));
    }

    public void printInfo(String message) {
        System.out.println(ColorPalette.TEXT + message + ColorPalette.RESET);
    }

    public void startProgressAnimation(BunnyState state, String[] messages) {
        animator.start(state, messages);
    }

    public void stopProgressAnimation() {
        animator.stop();
    }

    public void printSuccess(String message) {
        if (message != null && !message.isBlank()) {
            System.out.println(BunnyRenderer.renderSmall(BunnyState.SUCCESS));
            System.out.println("  " + ColorPalette.SUCCESS + message + ColorPalette.RESET);
            System.out.println("\n");
        } else {
            showBunny(BunnyState.SUCCESS);
        }
    }

    public void printWarning(String message) {
        System.out.println("\n" + ColorPalette.WARNING + message + ColorPalette.RESET + "\n");
    }

    public void printError(String message) {
        System.out.println(BunnyRenderer.renderSmall(BunnyState.ERROR));
        System.out.println("  " + ColorPalette.ERROR + message + ColorPalette.RESET);
        System.out.println("\n");
    }

    public void printProgress(String message) {
        System.out.println(progress.renderSpinnerMessage(message));
    }
    
    public void printHeader(String title) {
        System.out.println("\n" + TerminalUtils.renderSeparator(title) + "\n");
    }
    
    public void printSection(String sectionTitle) {
        System.out.println("\n" + progress.renderBanner(sectionTitle));
    }

    public void printPrompt(String prompt) {
        System.out.print(ColorPalette.ACCENT + prompt + ColorPalette.RESET + " ");
    }

    public void printTable(TableRenderer table) {
        System.out.print(ColorPalette.TEXT + table.render() + ColorPalette.RESET + "\n\n");
    }

    public void printChatResponse(com.shravan.jcode_intelligence.dto.response.ChatResponse response) {
        printSection("Question");
        printInfo(response.getQuery());

        printSection("Answer");
        printInfo(answerFormatter.format(response.getAnswer()));

        if (response.getSources() != null && !response.getSources().isEmpty()) {
            printSection("Sources");
            for (com.shravan.jcode_intelligence.dto.response.ChunkResponse source : response.getSources()) {
                String loc = source.getFilePath();
                if (loc != null) {
                    loc = loc.replace("\\", "/");
                    int lastSlash = loc.lastIndexOf('/');
                    if (lastSlash >= 0) {
                        loc = loc.substring(lastSlash + 1);
                    }
                }
                if (loc != null && source.getStartLine() > 0) loc += ":" + source.getStartLine();
                printInfo("  • " + (loc != null ? loc : "Unknown source"));
            }
        }
        
        System.out.println("\n");
        printSuccess(null);
    }

    public void clearScreen() {
        System.out.print("\033[H\033[2J");
        System.out.flush();
    }
}
