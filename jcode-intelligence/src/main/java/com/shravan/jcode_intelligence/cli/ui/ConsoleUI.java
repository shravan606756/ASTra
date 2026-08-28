package com.shravan.jcode_intelligence.cli.ui;

import com.shravan.jcode_intelligence.cli.util.TerminalUtils;

public class ConsoleUI {
    private static final String ANSI_M = "\u001b[35m";
    private static final String ANSI_BM = "\u001b[95m";
    private static final String ANSI_G = "\u001b[37m";
    private static final String ANSI_BGM = "\u001b[45m";
    private static final String ANSI_RESET = "\u001b[0m";

    private static final String ASTRA_LOGO_TEMPLATE = """
            {M}                   {R}{G} {R}{M}     ▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄{R}{G} {R}{M} ▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄{R}{G} {R}{M}▄▄▄▄▄▄▄▄▄▄▄▄▄▄{R}{MM}▀{R}{M}▄ {R}{G} {R}{M}                   {R}
            {M}   ▀▓{R}{MM}▄▄▄▄{R}{M}█▄▄       {R}{G} {R}{M}   ▄{R}{MM}▄██▓█▀▀▀▀▀▓███░{R}{MM}░▓{R}{G} {R}{M}▀{R}{MM}▓{R}{MM}▒▓█▀▓▓████{R}{BM}█{R}{MM}▓▀▀▀▓█▀{R}{MM}▄{R}{G} {R}{M}█{R}{MM}▀███ {R}{M}  ░▀{R}{MM}▒▓█▒{R}{M}█{R}{MM}▐{R}{M} {R}{G} {R}{M}   ▀▓{R}{MM}▄▄▄▄{R}{M}█▄▄       {R}
            {M}   ░█{R}{MM}██▌▀▀▀█▄{R}{M}▄     {R}{G} {R}{M}  ░{R}{MM}▒{R}{MM}▓▓▒▓{R}{M}█▀   ░{R}{MM}░▓██▒{R}{MM}▓{R}{M}▌{R}{G} {R}{M}  ▀{R}{MM}░▄{R}{M}░{R}{MM}▒▓▓{R}{BM}███{R}{MM}░{R}{MM}▄{R}{M}▀▒{R}{MM}░{R}{BM}▓{R}{M}█{R}{MM}▄{R}{M} {R}{G} {R}{M}▓█{R}{MM}▓▓▓{R}{M}█   {R}{MM}▄▓▓▀{R}{MM}▄{R}{M}▀  {R}{G} {R}{M}   ░█{R}{MM}██▌▀▀▀█▄{R}{M}▄     {R}
            {M}  ░▐{R}{MM}▐██{R}{M}█  ▀{R}{MM} {R}{MM}██{R}{M}▌    {R}{G} {R}{M}  █{R}{MM} {R}{MM}▒▒▒{R}{M}█▌     ▀▀▀▀▀  {R}{G} {R}{M}   ▀  {R}{MM}░▓▓▓▓░{R}{MM}▐{R}{M}  ░{R}{MM}▄▄{R}{M}▀  {R}{G} {R}{M}▒█{R}{MM}▄{R}{M}▀{R}{MM}▀{R}{MM}▄▄█▓▓▒{R}{MM}▄{R}{M}▀    {R}{G} {R}{M}  ░▐{R}{MM}▐██{R}{M}█  ▀{R}{MM} {R}{MM}██{R}{M}▌    {R}
            {M}  ░█{R}{MM}▓▓░{R}{M}▌   ▓{R}{MM}▓▓▓{R}{M}▌   {R}{G} {R}{M}  ▓{R}{MM} {R}{MM}░░▒░{R}{M}▌ ▄▄▄▄▄▄     {R}{G} {R}{M}      {R}{MM}░{R}{MM}▒▒▒▒{R}{M}█{R}{MM}▓{R}{M}        {R}{G} {R}{M}░▓░{R}{MM}▄▓{R}{BM}▀{R}{M}▀▀{R}{MM}▀▓▒░{R}{M}▓▄   {R}{G} {R}{M}  ░█{R}{MM}▓▓░{R}{M}▌   ▓{R}{MM}▓▓▓{R}{M}▌   {R}
            {M} ░▐{R}{MM}░▒▒ {R}{M}  ▄▄{R}{MM}▄▓▒▒▒{R}{M}▄▄ {R}{G} {R}{M}  {R}{BM}▄{R}{MM} ░░{R}{M}▀{R}{MM}▀{R}{MM}▄█▀▀▀▀■ ░░{R}{M}▄▀▄{R}{G} {R}{M}      {R}{MM}▓{R}{MM}░░░░{R}{M}█▓        {R}{G} {R}{M}░▓▌{R}{MM}▒{R}{BM}▌{R}{MM} {R}{M}  ░█{R}{MM}▓▒░ {R}{M}▌  {R}{G} {R}{M} ░▐{R}{MM}░▒▒ {R}{M}  ▄▄{R}{MM}▄▓▒▒▒{R}{M}▄▄ {R}
            {M}▀▄█{R}{MM}░░{R}{MM}░{R}{M}▀▄█{R}{MM}▀{R}{MM}▄{R}{M}▀▓█{R}{MM}░░░{R}{M}▄ {R}{G} {R}{BM}▀{R}{M}█▄{R}{MM}▄{R}{MM}░{R}{M}▓{R}{MM}▌{R}{MM}▓░{R}{MM}▄{R}{M}▀ ▀░▄▀{R}{MM} {R}{MM}░{R}{M}█▌{R}{MM}▓{R}{G} {R}{M}      ▓{R}{MM}░░░░{R}{M}█{R}{MM}▓{R}{M}        {R}{G} {R}{M}▒█▒░{R}{MM}▀{R}{BM}▄{R}{M}   ▒{R}{MM} {R}{MM}░░░ {R}{M}▌ {R}{G} {R}{M}▀▄█{R}{MM}░░{R}{MM}░{R}{M}▀▄█{R}{MM}▀{R}{MM}▄{R}{M}▀▓█{R}{MM}░░░{R}{M}▄ {R}
            {M}░▓█{R}{MM}░{R}{M}▀{R}{MM}▄▀{R}{M}▀▀   ░{R}{MM}░{R}{M}████ {R}{G} {R}{M} ▀▀{R}{MM}▄{R}{M}▄▄{R}{MM}▀{R}{MM}░{R}{MM}▐{R}{M}    ░{R}{MM}█▀▌  {R}{M}▌{R}{MM}▒{R}{G} {R}{M}      ▒{R}{MM}▌{R}{MM}░░░{R}{M}█{R}{MM}▐{R}{M}        {R}{G} {R}{M}▓{R}{MM}▀▀░{R}{M}▄▒▀  ░▐█{R}{MM}░░{R}{M}█▌ {R}{G} {R}{M}░▓█{R}{MM}░{R}{M}▀{R}{MM}▄▀{R}{M}▀▀   ░{R}{MM}░{R}{M}████ {R}
            {M}░{R}{MM}░{R}{MM}░{R}{M}▌{R}{MM}▒░{R}{M}▌      ▒{R}{MM}░░{R}{M}██▌{R}{G} {R}{M}  ▄▄▄  ▀{R}{MM}▄▀{R}{M}▄▄ ▒{R}{MM}▓{R}{M}██{R}{MM}░░{R}{MM}▀░{R}{G} {R}{M}      ░{R}{MM}▌{R}{M}▓███{R}{MM}▐{R}{M}        {R}{G} {R}{M}▀██{R}{MM}░{R}{MM}░{R}{M}█    ░█{R}{MM}▓▒{R}{MM}▐{R}{M}█▌{R}{G} {R}{M}░{R}{MM}░{R}{MM}░{R}{M}▌{R}{MM}▒░{R}{M}▌      ▒{R}{MM}░░{R}{M}██▌{R}
            {M}▐█{R}{MM}▀{R}{MM}▀{R}{M}▄▀        ▓{R}{MM}░░{R}{M}██{R}{G} {R}{M}▄{R}{MM}▓▒░{R}{MM}░{R}{M}█{R}{MM}░{R}{M}▄    ▄▒{R}{MM}░{R}{M}████{R}{MM}▄{R}{M}▀{R}{G} {R}{M}       {R}{MM}▌{R}{M}████{R}{MM}▐{R}{M}        {R}{G} {R}{M}▒██{R}{MM}░{R}{MM}░{R}{M}█    ▒{R}{MM}▒▒▒{R}{M}██▌{R}{G} {R}{M}▐█{R}{MM}▀{R}{MM}▀{R}{M}▄▀        ▓{R}{MM}░░{R}{M}██{R}
            {MM}▀{R}{M}████{R}{MM}▀▓{R}{M}       ▄███▓{R}{G} {R}{M} ▀▀{R}{MM}▄██{R}{M}█{R}{MM}░{R}{M}█{R}{MM}▀{R}{M}▄{R}{MM}▀{R}{M}████{R}{MM}▄{R}{M}▀▀  {R}{G} {R}{M}     ▄{R}{MM}▀▒{R}{M}█████{R}{MM}▀{R}{M}▄      {R}{G} {R}{M}■▀▒▓██   ▀{R}{MM}▄{R}{MM}░▀{R}{M}██{R}{MM}▄{R}{M} {R}{G} {R}{MM}▀{R}{M}████{R}{MM}▀▓{R}{M}       ▄███▓{R}
            """
            .replace("{M}", ANSI_M)
            .replace("{BM}", ANSI_BM)
            .replace("{G}", ANSI_G)
            .replace("{MM}", ANSI_M + ANSI_BGM)
            .replace("{BMM}", ANSI_BM + ANSI_BGM)
            .replace("{R}", ANSI_RESET);

    private static final String[] ASTRA_LOGO = ASTRA_LOGO_TEMPLATE.split("\n");

    private static final int STARTUP_BLOCK_GAP = 4;

    private final ProgressRenderer progress = new ProgressRenderer();
    private final ProgressAnimator animator = new ProgressAnimator();
    private final AnswerFormatter answerFormatter = new AnswerFormatter();

    private final Object renderLock = new Object();

    private Thread idleThread;
    private volatile boolean idleRunning;

    private static final String[] BIG_BUNNY_ASCII = {
            "              , ,",
            "             /| |\\",
            "            / | | \\",
            "            | | | |     Neeeah, ready to explore some code?",
            "            \\ | | /",
            "             \\|w|/    /",
            "             /_ _\\   /      ,",
            "  /\\       _:()_():_       /]",
            "  ||_     : ._=Y=_  :     / /",
            " [)(_\\,   ',__\\W/ _,'    /  \\",
            " [) \\_/\\    _/'='\\      /-/\\)",
            "  [_| \\ \\  ///  \\ '._  / /",
            "  :;   \\ \\///   / |  '` /",
            "  ;::   \\ `|:   : |',_.'",
            "  \"\"\"    \\_|:   : |",
            "           |:   : |'\".",
            "           /`._.'  \\/",
            "          /  /|   /",
            "         |  \\ /  /",
            "          '. '. /",
            "            '. '",
            "            / \\ \\",
            "           / / \\'=,",
            "     .----' /   \\ (\\__",
            "    (((____/     \\ \\  )",
            "                  '.\\_)"
    };

    public void printBanner() {
        clearScreen();

        // Disable line wrap for ASCII art to gracefully clip on narrow screens rather
        // than break
        System.out.print("\u001b[?7l");

        try {
            // 1. ASTra Logo Block
            int logoMaxWidth = 0;
            for (String line : ASTRA_LOGO) {
                String clean = line.replaceAll("\u001b\\[[;\\d]*[mK]", "");
                if (clean.length() > logoMaxWidth) {
                    logoMaxWidth = clean.length();
                }
            }
            int logoPad = Math.max(0, (TerminalUtils.getTerminalWidth() - logoMaxWidth) / 2);
            String logoOffset = " ".repeat(logoPad);

            System.out.println();
            for (String logoLine : ASTRA_LOGO) {
                System.out.println(logoOffset + logoLine);
            }

            System.out.println();
            System.out.println(TerminalUtils.center(ColorPalette.ACCENT + "Code Intelligence CLI" + ColorPalette.RESET,
                    TerminalUtils.getTerminalWidth()));
            System.out.println(
                    TerminalUtils.center(ColorPalette.MUTED + "Understand • Search • Analyze" + ColorPalette.RESET,
                            TerminalUtils.getTerminalWidth()));
            System.out.println();
            System.out.println();

            // 2. Bunny Mascot Block
            int bunnyMaxWidth = 0;
            for (String line : BIG_BUNNY_ASCII) {
                if (line.length() > bunnyMaxWidth) {
                    bunnyMaxWidth = line.length();
                }
            }
            int bunnyPad = Math.max(0, (TerminalUtils.getTerminalWidth() - bunnyMaxWidth) / 2);
            String bunnyOffset = " ".repeat(bunnyPad);

            for (String bunnyLine : BIG_BUNNY_ASCII) {
                System.out.println(bunnyOffset + ColorPalette.MUTED + bunnyLine + ColorPalette.RESET);
            }
        } finally {
            // Re-enable line wrap for normal text blocks and interactive prompts
            System.out.print("\u001b[?7h");
        }

        System.out.println();
        System.out.println();

        // 3. Help Box
        String[] box = {
                "┌───────────────────────────────┐",
                "│  Type 'help' to see commands  │",
                "└───────────────────────────────┘"
        };
        for (String boxLine : box) {
            System.out.println(TerminalUtils.center(ColorPalette.ACCENT + boxLine + ColorPalette.RESET,
                    TerminalUtils.getTerminalWidth()));
        }

        System.out.println();
        System.out.println();

        // 4. GitHub Link
        System.out.println(TerminalUtils.center(ColorPalette.TEXT + "[ GitHub ]" + ColorPalette.RESET,
                TerminalUtils.getTerminalWidth()));
        System.out.println();
        System.out.println(TerminalUtils.renderSeparator(""));
        System.out.println();
    }

    public void startIdleAnimation(int linesUp) {
        // Idle animation disabled to preserve the new static layout
    }

    public void stopIdleAnimation() {
        // Idle animation disabled
    }

    public void printFarewell() {
        stopIdleAnimation();
        System.out.println();
        showSignBunny(BunnyDialogue.getDialogue(BunnyState.GOODBYE), BunnyState.GOODBYE);
        System.out.println();
    }

    public void showBunny(BunnyState state) {
        stopProgressAnimation();
        System.out.println(BunnyRenderer.renderSmall(state));
        System.out.println("  " + ColorPalette.TEXT + BunnyDialogue.getDialogue(state) + ColorPalette.RESET);
        System.out.println();
    }

    public void showSignBunny(String message, BunnyState state) {
        stopProgressAnimation();
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
        stopProgressAnimation();
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
        stopProgressAnimation();
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
        stopProgressAnimation();
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
            String clean = line.replaceAll("\u001b\\[[;\\d]*[mK]", "");
            if (clean.length() > max) {
                max = clean.length();
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
