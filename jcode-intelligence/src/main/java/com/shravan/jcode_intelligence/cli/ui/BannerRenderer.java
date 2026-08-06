package com.shravan.jcode_intelligence.cli.ui;

public class BannerRenderer {
    public static void render() {
        String border = ColorPalette.PRIMARY + "╭──────────────────────────────────────────────╮" + ColorPalette.RESET;
        String title = ColorPalette.PRIMARY + "│" + ColorPalette.ACCENT + "                  ASTra                       " + ColorPalette.PRIMARY + "│" + ColorPalette.RESET;
        String subtitle = ColorPalette.PRIMARY + "│       " + ColorPalette.TEXT + "Repository Intelligence for Java      " + ColorPalette.PRIMARY + "│" + ColorPalette.RESET;
        String bottom = ColorPalette.PRIMARY + "╰──────────────────────────────────────────────╯" + ColorPalette.RESET;

        System.out.println(border);
        System.out.println(title);
        System.out.println(subtitle);
        System.out.println(bottom);
        System.out.println();
        BunnyRenderer.render();
        System.out.println(ColorPalette.TEXT + "Type \"help\" to begin." + ColorPalette.RESET);
        System.out.println();
    }
}
