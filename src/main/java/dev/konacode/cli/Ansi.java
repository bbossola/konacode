package dev.konacode.cli;

import java.util.regex.Pattern;

public final class Ansi {

    /** Erases the whole line. A row of spaces leaves residue past the text that follows. */
    public static final String ERASE_LINE = "\u001B[2K";

    public static final String RESET = "\u001B[0m";
    public static final String BOLD = "\u001B[1m";
    public static final String DIM = "\u001B[2m";
    public static final String ITALIC = "\u001B[3m";
    public static final String UNDERLINE = "\u001B[4m";
    public static final String STRIKE = "\u001B[9m";
    public static final String RED = "\u001B[31m";
    public static final String GREEN = "\u001B[32m";
    public static final String YELLOW = "\u001B[33m";
    public static final String BLUE = "\u001B[34m";
    public static final String MAGENTA = "\u001B[35m";
    public static final String CYAN = "\u001B[36m";

    private static final Pattern CODE = Pattern.compile("\u001B\\[[0-9;]*m");

    private Ansi() {
    }

    public static String style(String text, String... codes) {
        return String.join("", codes) + text + RESET;
    }

    public static String strip(String text) {
        return CODE.matcher(text).replaceAll("");
    }

    public static int visibleLength(String text) {
        return strip(text).length();
    }

    public static String blue(String text) {
        return style(text, BLUE);
    }

    public static String green(String text) {
        return style(text, GREEN);
    }
}
