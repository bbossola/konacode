package dev.konacode.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AnsiTest {

    @Test
    void putsTheCodeBeforeTheTextAndAResetAfterIt() {
        assertEquals(Ansi.BOLD + "hello" + Ansi.RESET, Ansi.style("hello", Ansi.BOLD));
    }

    @Test
    void appliesMoreThanOneCode() {
        assertEquals(Ansi.BOLD + Ansi.CYAN + "hi" + Ansi.RESET,
                Ansi.style("hi", Ansi.BOLD, Ansi.CYAN));
    }

    @Test
    void countsOnlyTheVisibleCharacters() {
        assertEquals(5, Ansi.visibleLength(Ansi.style("hello", Ansi.BOLD)));
    }

    @Test
    void countsAFullwidthCharacterAsTwoColumns() {
        // A count of characters lets a padded operand pass a cut and then wrap on a terminal.
        assertEquals(6, Ansi.visibleLength("ｍｍｍ"));
        assertEquals(4, Ansi.visibleLength("文書"));
    }

    @Test
    void countsAnEmojiAsTwoColumns() {
        assertEquals(2, Ansi.visibleLength("🚀"));
    }

    @Test
    void cutsAWideCharacterByColumnsAndNeverInside() {
        assertEquals("aｍ", Ansi.cutToColumns("aｍｍ", 3));
        assertEquals("ab", Ansi.cutToColumns("ab", 5));
    }

    @Test
    void removesEveryCode() {
        assertEquals("hello", Ansi.strip(Ansi.style("hello", Ansi.BOLD, Ansi.RED)));
    }

    @Test
    void leavesPlainTextAlone() {
        assertEquals(5, Ansi.visibleLength("plain"));
        assertEquals("plain", Ansi.strip("plain"));
    }

    @Test
    void oneLineStripsACodeBeforeItReplacesEveryByteThatIsLeft() {
        // The other order makes a picture of the escape byte, and leaves the tail of the code.
        assertEquals("echo hi", Ansi.oneLine("echo \u001B[31mhi\u001B[0m"));
    }

    @Test
    void oneLineReplacesAnEscapeByteThatStartsNoCode() {
        assertEquals("echo \u2400[2J hi", Ansi.oneLine("echo \u001B[2J hi"));
    }

    @Test
    void oneLineReplacesEveryC1Control() {
        // Cntrl covers the ASCII range only. U+009B is the eight bit form of ESC and a bracket.
        for (int code = 0x80; code <= 0x9F; code++) {
            assertEquals("echo\u2400", Ansi.oneLine("echo" + (char) code),
                    "U+00" + Integer.toHexString(code));
        }
    }

    @Test
    void oneLineReplacesTheLineAndTheParagraphSeparator() {
        assertEquals("a\u2400b\u2400c", Ansi.oneLine("a\u2028b\u2029c"));
    }

    @Test
    void oneLineReplacesADirectionOverride() {
        // The override is written as an escape. A literal one reverses this file in an editor.
        assertEquals("echo \u2400gnahc\u2400 safe", Ansi.oneLine("echo \u202Egnahc\u202C safe"));
    }

    @Test
    void oneLineKeepsAnAccentACjkNameAndAnEmoji() {
        String real = "caf\u00E9 \u6587\u66F8 \uD83D\uDE80";

        assertEquals(real, Ansi.oneLine(real));
    }
}
