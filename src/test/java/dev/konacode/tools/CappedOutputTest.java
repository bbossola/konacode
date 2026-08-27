package dev.konacode.tools;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CappedOutputTest {

    private static CappedOutput write(int head, int tail, String text) {
        CappedOutput output = new CappedOutput(head, tail);
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        output.write(bytes, bytes.length);
        return output;
    }

    @Test
    void outputBelowTheCapComesBackWhole() {
        assertEquals("hello", write(10, 10, "hello").text());
    }

    @Test
    void outputAtTheCapComesBackWhole() {
        assertEquals("abcdefghij", write(5, 5, "abcdefghij").text());
    }

    @Test
    void theCapKeepsTheFirstPartAndTheLastPart() {
        String text = write(4, 4, "AAAA1111222233334444BBBB").text();

        assertTrue(text.startsWith("AAAA"), text);
        assertTrue(text.endsWith("BBBB"), text);
    }

    @Test
    void theCapNamesTheLinesAndTheBytesItRemoved() {
        assertEquals("A\n… 3 lines (7 bytes) removed …\nB",
                write(1, 1, "A\nxx\nyy\nB").text());
    }

    @Test
    void aRemovedPartWithNoLineBreakReportsNoLines() {
        assertEquals("A\n… 0 lines (3 bytes) removed …\nB", write(1, 1, "AxyzB").text());
    }

    @Test
    void severalWritesBehaveAsOneStream() {
        CappedOutput output = new CappedOutput(1, 1);
        output.write("Ax".getBytes(StandardCharsets.UTF_8), 2);
        output.write("yB".getBytes(StandardCharsets.UTF_8), 2);

        assertEquals("A\n… 0 lines (2 bytes) removed …\nB", output.text());
    }

    @Test
    void writeUsesTheLengthAndNotTheWholeBuffer() {
        CappedOutput output = new CappedOutput(10, 10);
        output.write(new byte[] {'a', 'b', 'c', 0, 0}, 3);

        assertEquals("abc", output.text());
    }

    @Test
    void aCapOfZeroIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new CappedOutput(0, 10));
        assertThrows(IllegalArgumentException.class, () -> new CappedOutput(10, 0));
    }
}
