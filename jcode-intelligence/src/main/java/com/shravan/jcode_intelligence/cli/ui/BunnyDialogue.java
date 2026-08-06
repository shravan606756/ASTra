package com.shravan.jcode_intelligence.cli.ui;

import java.util.Random;

public class BunnyDialogue {
    private static final Random RANDOM = new Random();

    public static final String BUNNY_ART = 
            " (\\_/)\n" +
            " (•.•)\n" +
            " / >_";

    private static final String[] GREETINGS = {
            "Hey there, code wizard.",
            "Oh good, another human with Java.",
            "I've already sniffed around the repository.",
            "Hope you brought coffee.",
            "Ready to explore some ASTs?",
            "Time to dig into the codebase."
    };

    private static final String[] FAREWELLS = {
            "See you next compile.",
            "Don't forget to commit.",
            "My burrow is always open.",
            "May your builds stay green.",
            "Happy refactoring!"
    };

    private static final String[] TIPS = {
            "Try 'architecture' to get a high-level view of a component.",
            "Use 'stats' to see how many methods and classes I've indexed.",
            "The 'dependencies' command shows you what a class relies on.",
            "Use 'calls' to find out who uses a specific method.",
            "Make sure to 'use' a repository before asking questions.",
            "I can 'search' the whole repository for semantic matches."
    };

    private static final String[] PROGRESS_MESSAGES = {
            "Searching burrows...",
            "Sniffing repository...",
            "Reading Java...",
            "Consulting the AST...",
            "Following method calls...",
            "Thinking very hard...",
            "Digging through code..."
    };

    private static final String[] SUCCESS_MESSAGES = {
            "Done!",
            "Found it.",
            "That was easy.",
            "Success!"
    };

    private static final String[] ERROR_MESSAGES = {
            "Oops...",
            "Uh oh...",
            "Something went wrong.",
            "My nose couldn't find that."
    };

    private static final String[] INDEX_SUCCESS_MESSAGES = {
            "That was a big one...",
            "Repository successfully mapped.",
            "Lots of Java in there."
    };

    private static final String[] REMOVE_SUCCESS_MESSAGES = {
            "Poof! It's gone.",
            "Cleared out that burrow.",
            "Deleted."
    };
    
    private static final String[] DOCTOR_OFFLINE = {
            "My nose can't find the backend...",
            "Is the server running?",
            "Backend is hiding."
    };

    private static String getRandom(String[] array) {
        return array[RANDOM.nextInt(array.length)];
    }

    private static final String[] INDEXING_ANIMATION = {
            "Bunny is exploring the repository...",
            "Parsing Java files...",
            "Building the AST...",
            "Packing knowledge chunks...",
            "Brewing embeddings...",
            "Filling the vector vault...",
            "Almost there..."
    };

    public static String getGreeting() { return getRandom(GREETINGS); }
    public static String getFarewell() { return getRandom(FAREWELLS); }
    public static String getTip() { return getRandom(TIPS); }
    public static String getProgress() { return getRandom(PROGRESS_MESSAGES); }
    public static String getSuccess() { return getRandom(SUCCESS_MESSAGES); }
    public static String getError() { return getRandom(ERROR_MESSAGES); }
    public static String getIndexSuccess() { return getRandom(INDEX_SUCCESS_MESSAGES); }
    public static String getRemoveSuccess() { return getRandom(REMOVE_SUCCESS_MESSAGES); }
    public static String getDoctorOffline() { return getRandom(DOCTOR_OFFLINE); }

    public static String[] getIndexingAnimation() { return INDEXING_ANIMATION; }
    public static String[] getThinkingAnimation() { return PROGRESS_MESSAGES; }
}
