package dev.konacode.cli;

/** The three colour codes the REPL uses. */
final class Ansi {

    private static final String RESET = "\u001B[0m";
    private static final String BLUE = "\u001B[34m";
    private static final String GREEN = "\u001B[32m";

    private Ansi() {
    }

    static String blue(String text) {
        return BLUE + text + RESET;
    }

    static String green(String text) {
        return GREEN + text + RESET;
    }
}
