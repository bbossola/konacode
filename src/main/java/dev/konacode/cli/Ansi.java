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

    /**
     * One line of a string the model wrote.
     *
     * <p>The model chooses a path, a command line and the arguments of a call. A newline there
     * draws a second question below the real one, and an escape code repaints the screen, so the
     * user approves something they did not read. A user cannot approve what they cannot read.
     *
     * <p>{@link #strip} runs first, because it removes a whole colour code. The replace then
     * covers every byte that is left, and it must not run first: a stripped escape byte would
     * become a picture that {@link #strip} can never match.
     *
     * <p>The four categories are chosen, and not guessed. {@code Cc} covers every control
     * character, and {@code \p{Cntrl}} would miss U+0080 to U+009F, where U+009B is the eight bit
     * form of {@code ESC [}. {@code Cf} covers a direction override, where U+202E reverses how a
     * terminal draws a line. {@code Zl} and {@code Zp} end a line by definition. An accented
     * character, a CJK character and an emoji are in none of them, and they survive.
     */
    public static String oneLine(String text) {
        return strip(text).replaceAll("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]", "\u2400");
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
