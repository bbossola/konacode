package dev.konacode.cli.markdown;

import dev.konacode.cli.Ansi;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WrapTest {

    @Test
    void breaksAtASpace() {
        assertEquals(List.of("one two", "three"), Wrap.lines("one two three", 8));
    }

    @Test
    void keepsAShortLineWhole() {
        assertEquals(List.of("short"), Wrap.lines("short", 40));
    }

    @Test
    void measuresTheVisibleWidthAndNotTheBytes() {
        String bold = Ansi.style("aaaa", Ansi.BOLD);

        assertEquals(1, Wrap.lines(bold + " bb", 8).size());
    }

    @Test
    void repeatsTheStyleAfterABreakSoTheSecondLineKeepsIt() {
        String text = Ansi.BOLD + "one two three" + Ansi.RESET;

        List<String> lines = Wrap.lines(text, 8);

        assertEquals(2, lines.size());
        assertTrue(lines.get(1).startsWith(Ansi.BOLD), lines.get(1));
    }

    @Test
    void breaksAWordThatIsLongerThanTheWidth() {
        List<String> lines = Wrap.lines("aaaaaaaaaaaa", 5);

        assertEquals(3, lines.size());
        assertEquals("aaaaa", lines.get(0));
    }

    @Test
    void returnsOneEmptyLineForEmptyText() {
        assertEquals(List.of(""), Wrap.lines("", 10));
    }
}
