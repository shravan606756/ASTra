package com.shravan.jcode_intelligence.cli.ui;

import java.util.Random;

public class BunnyDialogue {
    private static final Random RANDOM = new Random();

    private static final String[] WELCOME = {
            "Ready when you are.",
            "Let's explore some code.",
            "Point me to a Java project."
    };

    private static final String[] IDLE = {
            "Ready."
    };

    private static final String[] THINKING = {
            "Connecting the dots...",
            "Thinking..."
    };

    private static final String[] READING = {
            "Reading through the code...",
            "Understanding the project..."
    };

    private static final String[] SEARCHING = {
            "Looking for references...",
            "Following method calls..."
    };

    private static final String[] DIGGING = {
            "Building the AST...",
            "Digging through the repository..."
    };

    private static final String[] SUCCESS = {
            "Found something interesting.",
            "Done."
    };

    private static final String[] CONFUSED = {
            "I'm a bit lost.",
            "Not quite sure..."
    };

    private static final String[] ERROR = {
            "I couldn't understand that.",
            "Let's try another path."
    };

    private static final String[] GOODBYE = {
            "See you next compile.",
            "Happy refactoring."
    };

    private static final String[] TIPS = {
            "Try 'architecture' to get a high-level view of a component.",
            "Use 'stats' to see how many methods and classes I've indexed.",
            "The 'dependencies' command shows you what a class relies on.",
            "Use 'calls' to find out who uses a specific method.",
            "Make sure to 'use' a repository before asking questions.",
            "I can 'search' the whole repository for semantic matches."
    };

    private static String getRandom(String[] array) {
        return array[RANDOM.nextInt(array.length)];
    }

    public static String getDialogue(BunnyState state) {
        return switch (state) {
            case WELCOME -> getRandom(WELCOME);
            case IDLE -> getRandom(IDLE);
            case THINKING -> getRandom(THINKING);
            case READING -> getRandom(READING);
            case SEARCHING -> getRandom(SEARCHING);
            case DIGGING -> getRandom(DIGGING);
            case SUCCESS -> getRandom(SUCCESS);
            case CONFUSED -> getRandom(CONFUSED);
            case ERROR -> getRandom(ERROR);
            case GOODBYE -> getRandom(GOODBYE);
        };
    }

    public static String getTip() {
        return getRandom(TIPS);
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

    public static String[] getIndexingAnimation() {
        return INDEXING_ANIMATION;
    }
}
