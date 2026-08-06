package com.shravan.jcode_intelligence.cli.ui;

public class ConsoleUI {
    private final ProgressRenderer progress = new ProgressRenderer();
    private final ProgressAnimator animator = new ProgressAnimator();
    private final AnswerFormatter answerFormatter = new AnswerFormatter();

    public void printBanner() {
        System.out.println(com.shravan.jcode_intelligence.cli.util.TerminalUtils.renderSeparator(""));
        System.out.println();
        System.out.println(ColorPalette.TEXT + BunnyDialogue.BUNNY_ART + ColorPalette.RESET);
        System.out.println();
        System.out.println(ColorPalette.ACCENT + BunnyDialogue.getGreeting() + ColorPalette.RESET);
        System.out.println();
        System.out.println(ColorPalette.TEXT + "Welcome to ASTra.");
        System.out.println("I'm your repository guide.");
        System.out.println();
        System.out.println("Type");
        System.out.println();
        System.out.println(ColorPalette.ACCENT + "  help" + ColorPalette.RESET);
        System.out.println();
        System.out.println(ColorPalette.TEXT + "to discover what we can explore together." + ColorPalette.RESET);
        System.out.println();
        System.out.println(com.shravan.jcode_intelligence.cli.util.TerminalUtils.renderSeparator(""));
    }

    public void printFarewell() {
        System.out.println();
        System.out.println(ColorPalette.ACCENT + BunnyDialogue.getFarewell() + ColorPalette.RESET);
    }

    public void printInfo(String message) {
        System.out.println(ColorPalette.TEXT + message + ColorPalette.RESET);
    }

    public void startProgressAnimation(String[] messages) {
        animator.start(messages);
    }

    public void stopProgressAnimation() {
        animator.stop();
    }

    public void printSuccess(String message) {
        System.out.println(progress.renderSuccessMessage(message));
    }

    public void printWarning(String message) {
        System.out.println(ColorPalette.WARNING + message + ColorPalette.RESET);
    }

    public void printError(String message) {
        System.out.println(progress.renderFailureMessage(message));
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
