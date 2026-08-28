package dev.konacode.tools;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
    void theCapSplitsExactlyAtTheTwoLimits() {
        assertEquals("AB\n<removed 0 lines, 1 byte from the middle>\nDE",
                write(2, 2, "ABCDE").text());
    }

    @Test
    void theCapKeepsTheFirstPartAndTheLastPart() {
        assertEquals("AAAA\n<removed 0 lines, 16 bytes from the middle>\nBBBB",
                write(4, 4, "AAAA1111222233334444BBBB").text());
    }

    @Test
    void theCapNamesTheLinesAndTheBytesItRemoved() {
        assertEquals("A\n<removed 3 lines, 7 bytes from the middle>\nB",
                write(1, 1, "A\nxx\nyy\nB").text());
    }

    @Test
    void aRemovedPartWithNoLineBreakReportsNoLines() {
        assertEquals("A\n<removed 0 lines, 3 bytes from the middle>\nB", write(1, 1, "AxyzB").text());
    }

    @Test
    void writingAcrossSeveralCallsMatchesOneCall() {
        CappedOutput output = new CappedOutput(1, 1);
        output.write("Ax".getBytes(StandardCharsets.UTF_8), 2);
        output.write("yB".getBytes(StandardCharsets.UTF_8), 2);

        assertEquals("A\n<removed 0 lines, 2 bytes from the middle>\nB", output.text());
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
