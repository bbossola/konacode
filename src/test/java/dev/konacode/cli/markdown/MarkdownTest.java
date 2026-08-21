package dev.konacode.cli.markdown;

import dev.konacode.cli.Ansi;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownTest {

    /** The visible layout, with the codes and the trailing spaces removed. */
    private static List<String> layout(String markdown, int width) {
        return Arrays.stream(Markdown.render(markdown, width).split("\n", -1))
                .map(Ansi::strip)
                .map(String::stripTrailing)
                .toList();
    }

    @Test
    void putsAHeadingInBoldAndAColour() {
        assertTrue(Markdown.render("# Title", 40).startsWith(Ansi.BOLD + Ansi.CYAN));
        assertEquals(List.of("Title"), layout("# Title", 40));
    }

    @Test
    void marksBoldAndItalicText() {
        String rendered = Markdown.render("a **b** and *c*", 40);

        assertTrue(rendered.contains(Ansi.BOLD), rendered);
        assertTrue(rendered.contains(Ansi.ITALIC), rendered);
        assertEquals(List.of("a b and c"), layout("a **b** and *c*", 40));
    }

    @Test
    void putsACodeSpanInOneColour() {
        assertTrue(Markdown.render("call `read_file` now", 40).contains(Ansi.YELLOW));
    }

    @Test
    void marksStrikethrough() {
        assertTrue(Markdown.render("~~gone~~", 40).contains(Ansi.STRIKE));
    }

    @Test
    void showsTheAddressOfALink() {
        String rendered = Markdown.render("[the design](docs/design.md)", 40);

        assertTrue(rendered.contains(Ansi.UNDERLINE), rendered);
        assertEquals(List.of("the design (docs/design.md)"),
                layout("[the design](docs/design.md)", 40));
    }

    @Test
    void wrapsProseAtTheGivenWidth() {
        assertEquals(List.of("alpha beta gamma", "delta epsilon zeta"),
                layout("alpha beta gamma delta epsilon zeta", 20));
    }

    @Test
    void neverWrapsInsideACodeBlock() {
        String source = "```\nthis is a very long line of code that exceeds twenty\n```";

        assertEquals(List.of("  this is a very long line of code that exceeds twenty"),
                layout(source, 20));
    }

    @Test
    void putsNoBlankLineBetweenTheItemsOfAList() {
        assertEquals(List.of("- one", "- two", "- three"), layout("- one\n- two\n- three", 40));
    }

    @Test
    void numbersAnOrderedList() {
        assertEquals(List.of("1. first", "2. second"), layout("1. first\n2. second", 40));
    }

    @Test
    void alignsTheColumnsOfATable() {
        String source = "| A | Bee |\n|---|-----|\n| 1 | 22 |";

        assertEquals(List.of(" A | Bee", "---+-----", " 1 | 22"), layout(source, 40));
    }

    @Test
    void marksABlockQuoteWithABar() {
        assertEquals(List.of("  | quoted text here"), layout("> quoted text here", 40));
    }

    @Test
    void drawsAThematicBreakAcrossTheWidth() {
        assertEquals(List.of("-".repeat(20)), layout("---", 20));
    }

    @Test
    void endsTheLineAtAHardBreakWrittenWithTwoSpaces() {
        assertEquals(List.of("first", "second"), layout("first  \nsecond", 40));
    }

    @Test
    void endsTheLineAtAHardBreakWrittenWithABackslash() {
        assertEquals(List.of("first", "second"), layout("first\\\nsecond", 40));
    }

    @Test
    void stillJoinsALineThatEndsWithoutAHardBreak() {
        assertEquals(List.of("first second"), layout("first\nsecond", 40));
    }

    @Test
    void putsOneBlankLineBetweenBlocks() {
        assertEquals(List.of("first", "", "second"), layout("first\n\nsecond", 40));
    }
}
