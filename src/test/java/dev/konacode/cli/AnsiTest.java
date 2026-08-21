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
    void removesEveryCode() {
        assertEquals("hello", Ansi.strip(Ansi.style("hello", Ansi.BOLD, Ansi.RED)));
    }

    @Test
    void leavesPlainTextAlone() {
        assertEquals(5, Ansi.visibleLength("plain"));
        assertEquals("plain", Ansi.strip("plain"));
    }
}
