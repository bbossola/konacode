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

    /**
     * The columns a string takes on a terminal, with every code removed first.
     *
     * <p>A character is not a column. A character in the East Asian Wide or Fullwidth category
     * takes two columns, so a count of characters lets a padded string pass a cut and then wrap.
     * A wrapped line starts at column 1, and it reads as a line konacode wrote.
     */
    public static int visibleLength(String text) {
        String plain = strip(text);
        int total = 0;
        for (int index = 0; index < plain.length(); index += Character.charCount(plain.codePointAt(index))) {
            total += widthOf(plain.codePointAt(index));
        }
        return total;
    }

    /**
     * The start of a string that fits in the columns given. It never cuts a character in half.
     *
     * <p>Every caller passes a line that {@link #oneLine} made, so the line holds no code and a
     * cut cannot remove the reset of one.
     */
    public static String cutToColumns(String text, int columns) {
        int total = 0;
        int index = 0;
        while (index < text.length()) {
            int code = text.codePointAt(index);
            if (total + widthOf(code) > columns) {
                break;
            }
            total += widthOf(code);
            index += Character.charCount(code);
        }
        return text.substring(0, index);
    }

    /**
     * The ranges a terminal draws in two columns: East Asian Wide, Fullwidth, and the emoji.
     *
     * <p>Ranges, and not {@code Character.UnicodeBlock}, because a block is not the category a
     * terminal reads, and a range test needs no dependency.
     */
    private static final int[][] WIDE = {
        {0x1100, 0x115F}, {0x2E80, 0x303E}, {0x3041, 0x33FF}, {0x3400, 0x4DBF}, {0x4E00, 0x9FFF},
        {0xA000, 0xA4CF}, {0xA960, 0xA97F}, {0xAC00, 0xD7A3}, {0xF900, 0xFAFF}, {0xFE10, 0xFE19},
        {0xFE30, 0xFE6F}, {0xFF00, 0xFF60}, {0xFFE0, 0xFFE6}, {0x17000, 0x18AFF}, {0x1B000, 0x1B12F},
        {0x1F300, 0x1F64F}, {0x1F680, 0x1F6FF}, {0x1F900, 0x1F9FF}, {0x20000, 0x3FFFD}
    };

    private static int widthOf(int code) {
        for (int[] range : WIDE) {
            if (code >= range[0] && code <= range[1]) {
                return 2;
            }
        }
        return 1;
    }

    public static String blue(String text) {
        return style(text, BLUE);
    }

    public static String green(String text) {
        return style(text, GREEN);
    }
}
