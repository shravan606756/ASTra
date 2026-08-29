package com.shravan.jcode_intelligence.cli.ui;

public class TestFormatter {
    public static void main(String[] args) throws Exception {
        AnswerFormatter formatter = new AnswerFormatter();

        String sample = "1. REPOSITORY OVERVIEW & STATISTICS\n\n" +
                "1. Packages\n\n" +
                "   91\n\n" +
                "2. Classes\n\n" +
                "   1\u202F763\n\n" +
                "3. Interfaces\n\n" +
                "   189\n\n" +
                "4. Enums\n\n" +
                "   53\n\n" +
                "5. Methods\n\n" +
                "   19\u202F786\n\n" +
                "The repository is dominated by generated meta-model classes...\n";

        String out = formatter.format(sample);
        String cleanOut = out.replaceAll("\u001B\\[[;\\d]*m", "");
        System.out.println("---- Test 3: LLM generated numbered lists for labels ----");
        System.out.println(cleanOut);
    }
}
