package com.shravan.jcode_intelligence.cli.ui;

import java.util.Random;

public class BunnyDialogue {
    private static final Random RANDOM = new Random();

    private static final String[] WELCOME = {
            "Hey there, code wizard.",
            "Oh good, another human with Java.",
            "Hope you brought coffee.",
            "Ready to explore some ASTs?",
            "Time to dig into the codebase."
    };

    private static final String[] IDLE = {
            "Ready."
    };

    private static final String[] THINKING = {
            "Thinking...",
            "Consulting the AST...",
            "Connecting the dots...",
            "Looking through the code...",
            "Thinking very hard..."
    };

    private static final String[] READING = {
            "Reading the code...",
            "Looking through the files...",
            "Understanding the project...",
            "Piecing everything together...",
            "Studying the blueprints..."
    };

    private static final String[] SEARCHING = {
            "Sniffing around...",
            "Following method calls...",
            "Checking my burrows...",
            "Looking for clues...",
            "Untangling dependencies..."
    };

    private static final String[] DIGGING = {
            "Digging through the AST...",
            "Packing vectors...",
            "Exploring packages...",
            "Finding the important bits...",
            "Brewing embeddings..."
    };

    private static final String[] SUCCESS = {
            "Found it!",
            "Got something interesting.",
            "Here you go.",
            "Done!",
            "All mapped!",
            "Success!"
    };

    private static final String[] CONFUSED = {
            "I'm a bit lost.",
            "My nose couldn't find that.",
            "Not quite sure..."
    };

    private static final String[] ERROR = {
            "Hmm...",
            "I got lost.",
            "That didn't work.",
            "Let's try another path.",
            "Something went wrong."
    };

    private static final String[] GOODBYE = {
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
