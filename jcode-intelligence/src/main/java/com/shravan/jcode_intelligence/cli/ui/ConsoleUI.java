package com.shravan.jcode_intelligence.cli.ui;

import com.shravan.jcode_intelligence.cli.util.TerminalUtils;

public class ConsoleUI {
    private static final String[] ASTRA_LOGO = {
            "________  ________  _________  ________  ________     ",
            "|\\   __  \\|\\   ____\\|\\___   ___\\\\   __  \\|\\   __  \\    ",
            "\\ \\  \\|\\  \\ \\  \\___|\\|___ \\  \\_\\ \\  \\|\\  \\ \\  \\|\\  \\   ",
            " \\ \\   __  \\ \\_____  \\   \\ \\  \\ \\   _  _\\ \\   __  \\  ",
            "  \\ \\  \\ \\  \\|____|\\  \\   \\ \\  \\ \\  \\  \\\\  \\\\ \\  \\ \\  \\ ",
            "   \\ \\__\\ \\__\\____\\_\\  \\   \\ \\  \\ \\  \\__\\\\ _\\\\ \\__\\ \\__\\",
            "    \\|__|\\|__|\\_________\\   \\|__|  \\|__|\\|__|\\|__|\\|__|",
            "             \\|_________|                              "
    };

    private static final int STARTUP_BLOCK_GAP = 4;

    private final ProgressRenderer progress = new ProgressRenderer();
    private final ProgressAnimator animator = new ProgressAnimator();
    private final AnswerFormatter answerFormatter = new AnswerFormatter();

    private final Object renderLock = new Object();

    private Thread idleThread;
    private volatile boolean idleRunning;

    public void printBanner() {
        clearScreen();

        BunnyAnimator startupAnimator = new BunnyAnimator();
        startupAnimator.setState(BunnyState.WELCOME);

        int startupHeight = getStartupBlockHeight();
        for (int i = 0; i < startupHeight; i++) {
            System.out.println();
        }

        long start = System.currentTimeMillis();
        boolean firstFrame = true;

        while (System.currentTimeMillis() - start < 800) {
            if (!firstFrame) {
                System.out.print("\033[" + startupHeight + "A");
            }
            firstFrame = false;

            renderStartupBlock(startupAnimator.getCurrentFrame());

            try {
                Thread.sleep(120);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        System.out.println();
        System.out.println(TerminalUtils.center(ColorPalette.ACCENT + "Welcome to ASTra" + ColorPalette.RESET, TerminalUtils.getTerminalWidth()));
        System.out.println();
        System.out.println(TerminalUtils.center(ColorPalette.MUTED + "Try:" + ColorPalette.RESET, TerminalUtils.getTerminalWidth()));
        System.out.println(TerminalUtils.center(ColorPalette.TEXT + "    help" + ColorPalette.RESET, TerminalUtils.getTerminalWidth()));
        System.out.println();

        startIdleAnimation(startupHeight + 6);
    }

    public void startIdleAnimation(int linesUp) {
        if (idleRunning) {
            return;
        }

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

                if (!idleRunning) {
                    break;
                }

                synchronized (renderLock) {
                    System.out.print("\033[s\033[" + linesUp + "A");
                    renderStartupBlock(idleAnimator.getCurrentFrame());
                    System.out.print("\033[u");
                    System.out.flush();
                }
            }
        });

        idleThread.setDaemon(true);
        idleThread.start();
    }

    public void stopIdleAnimation() {
        idleRunning = false;
        if (idleThread != null) {
            idleThread.interrupt();
            try {
                idleThread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public void printFarewell() {
        stopIdleAnimation();
        System.out.println();
        showSignBunny(BunnyDialogue.getDialogue(BunnyState.GOODBYE), BunnyState.GOODBYE);
        System.out.println();
    }

    public void showBunny(BunnyState state) {
        System.out.println(BunnyRenderer.renderSmall(state));
        System.out.println("  " + ColorPalette.TEXT + BunnyDialogue.getDialogue(state) + ColorPalette.RESET);
        System.out.println();
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
            System.out.println();
        } else {
            showBunny(BunnyState.SUCCESS);
        }
    }

    public void printWarning(String message) {
        System.out.println();
        System.out.println(ColorPalette.WARNING + message + ColorPalette.RESET);
        System.out.println();
    }

    public void printError(String message) {
        System.out.println(BunnyRenderer.renderSmall(BunnyState.ERROR));
        System.out.println("  " + ColorPalette.ERROR + message + ColorPalette.RESET);
        System.out.println();
    }

    public void printProgress(String message) {
        System.out.println(progress.renderSpinnerMessage(message));
    }

    public void printHeader(String title) {
        System.out.println();
        System.out.println(TerminalUtils.renderSeparator(title));
        System.out.println();
    }

    public void printSection(String sectionTitle) {
        System.out.println();
        System.out.println(progress.renderBanner(sectionTitle));
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
                if (loc != null && source.getStartLine() > 0) {
                    loc += ":" + source.getStartLine();
                }
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

    public void printStats(String repositoryId, com.shravan.jcode_intelligence.dto.response.RepositoryStatsDTO stats) {
        printHeader("Repository Statistics");

        TableRenderer structureTable = new TableRenderer("Code Structure", "Value");
        structureTable.addRow("Repository", repositoryId);
        structureTable.addRow("Packages", String.valueOf(stats.getPackages()));
        structureTable.addRow("Classes", String.valueOf(stats.getClasses()));
        structureTable.addRow("Interfaces", String.valueOf(stats.getInterfaces()));
        structureTable.addRow("Enums", String.valueOf(stats.getEnums()));
        structureTable.addRow("Records", String.valueOf(stats.getRecords()));
        printTable(structureTable);

        TableRenderer membersTable = new TableRenderer("Members", "Value");
        membersTable.addRow("Fields", String.valueOf(stats.getFields()));
        membersTable.addRow("Constructors", String.valueOf(stats.getConstructors()));
        membersTable.addRow("Methods", String.valueOf(stats.getMethods()));
        membersTable.addRow("Fragments", String.valueOf(stats.getFragments()));
        printTable(membersTable);

        TableRenderer summaryTable = new TableRenderer("Summary", "Value");
        summaryTable.addRow("Total Chunks", String.valueOf(stats.getTotalChunks()));
        summaryTable.addRow("Largest Class", stats.getLargestClass() != null ? stats.getLargestClass() : "N/A");
        summaryTable.addRow("Largest Method", stats.getLargestMethod() != null ? stats.getLargestMethod() : "N/A");
        summaryTable.addRow("Indexing Time", String.format("%.1f s", stats.getIndexingTimeMs() / 1000.0));
        printTable(summaryTable);
    }

    private int getStartupBlockHeight() {
        return ASTRA_LOGO.length;
    }

    private int getBunnyWidth(String[] bunnyFrame) {
        int max = 0;
        for (String line : bunnyFrame) {
            if (line.length() > max) {
                max = line.length();
            }
        }
        return max;
    }

    private int getLogoWidth() {
        int max = 0;
        for (String line : ASTRA_LOGO) {
            if (line.length() > max) {
                max = line.length();
            }
        }
        return max;
    }

    private String padRight(String text, int width) {
        if (text.length() >= width) {
            return text;
        }
        return text + " ".repeat(width - text.length());
    }

    private void renderStartupBlock(String[] bunnyFrame) {
        int logoHeight = ASTRA_LOGO.length;
        int bunnyHeight = bunnyFrame.length;
        int bunnyTop = (logoHeight - bunnyHeight) / 2;
        int bunnyWidth = getBunnyWidth(bunnyFrame);
        int logoWidth = getLogoWidth();

        int blockWidth = bunnyWidth + STARTUP_BLOCK_GAP + logoWidth;
        int leftPad = Math.max(0, (TerminalUtils.getTerminalWidth() - blockWidth) / 2);
        String pad = " ".repeat(leftPad);

        synchronized (renderLock) {
            for (int row = 0; row < logoHeight; row++) {
                int bunnyIndex = row - bunnyTop;
                String bunnyLine = (bunnyIndex >= 0 && bunnyIndex < bunnyHeight) ? bunnyFrame[bunnyIndex] : "";
                String logoLine = ASTRA_LOGO[row];
                String line = pad
                        + ColorPalette.MUTED + padRight(bunnyLine, bunnyWidth) + ColorPalette.RESET
                        + " ".repeat(STARTUP_BLOCK_GAP)
                        + ColorPalette.ACCENT + logoLine + ColorPalette.RESET;

                System.out.print("\033[2K\r");
                System.out.println(line);
            }
            System.out.flush();
        }
    }
}
