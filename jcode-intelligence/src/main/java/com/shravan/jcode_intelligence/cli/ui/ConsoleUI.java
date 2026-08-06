package com.shravan.jcode_intelligence.cli.ui;

public class ConsoleUI {
    private final ProgressRenderer progress = new ProgressRenderer();
    private final ProgressAnimator animator = new ProgressAnimator();
    private final AnswerFormatter answerFormatter = new AnswerFormatter();

    public void printBanner() {
        showSignBunny("Welcome to ASTra!", BunnyState.WELCOME);
        System.out.println();
    }

    public void printFarewell() {
        System.out.println();
        showSignBunny("See you next compile!", BunnyState.GOODBYE);
        System.out.println();
    }

    public void showBunny(BunnyState state) {
        System.out.println(BunnyRenderer.renderSmall(state));
        System.out.println("  " + ColorPalette.TEXT + BunnyDialogue.getDialogue(state) + ColorPalette.RESET);
        System.out.println();
    }

    public void showSignBunny(String message, BunnyState state) {
        System.out.println(BunnyRenderer.renderSign(state, message));
    }

    public void printInfo(String message) {
        System.out.println(ColorPalette.TEXT + message + ColorPalette.RESET);
    }

    public void startProgressAnimation(BunnyState state, String[] messages) {
        System.out.println(BunnyRenderer.renderSmall(state));
        animator.start(messages);
    }

    public void stopProgressAnimation() {
        animator.stop();
    }

    public void printSuccess(String message) {
        if (message != null && !message.isBlank()) {
            System.out.println(BunnyRenderer.renderSmall(BunnyState.SUCCESS));
            System.out.println("  " + ColorPalette.TEXT + message + ColorPalette.RESET);
            System.out.println();
        } else {
            showBunny(BunnyState.SUCCESS);
        }
    }

    public void printWarning(String message) {
        System.out.println(ColorPalette.WARNING + message + ColorPalette.RESET);
    }

    public void printError(String message) {
        System.out.println(BunnyRenderer.renderSmall(BunnyState.ERROR));
        System.out.println("  " + ColorPalette.TEXT + message + ColorPalette.RESET);
        System.out.println();
    }

    public void printProgress(String message) {
        System.out.println(progress.renderSpinnerMessage(message));
    }
    
    public void printHeader(String title) {
        System.out.println(com.shravan.jcode_intelligence.cli.util.TerminalUtils.renderSeparator(title));
    }
    
    public void printSection(String sectionTitle) {
        System.out.println("\n" + progress.renderBanner(sectionTitle));
    }

    public void printPrompt(String prompt) {
        System.out.print(ColorPalette.ACCENT + prompt + ColorPalette.RESET + " ");
    }

    public void printTable(TableRenderer table) {
        System.out.print(ColorPalette.TEXT + table.render() + ColorPalette.RESET + "\n");
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
        
        System.out.println();
        printSuccess(null);
    }

    public void clearScreen() {
        System.out.print("\033[H\033[2J");
        System.out.flush();
    }
}
