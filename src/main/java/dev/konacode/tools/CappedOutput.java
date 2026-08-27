package dev.konacode.tools;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Keeps the first part and the last part of a stream, and counts what it removed between them.
 *
 * <p>A build prints its command line at the start and its error at the end. A cap that kept the
 * first part only would remove the answer.
 *
 * <p>This class is not thread safe. A caller that writes from one thread and reads from another
 * must hold one lock across the write and the read. A caller that locks the read only gets no
 * error: it gets stale output or partial output, because the Java Memory Model gives no
 * visibility without a lock on both sides.
 */
final class CappedOutput {

    private final ByteArrayOutputStream head;
    private final int headBytes;
    private final byte[] tail;
    private int tailStart;
    private int tailLength;
    private long removedBytes;
    private long removedLines;

    CappedOutput(int headBytes, int tailBytes) {
        if (headBytes < 1 || tailBytes < 1) {
            throw new IllegalArgumentException("Each part must keep at least one byte.");
        }
        this.headBytes = headBytes;
        this.head = new ByteArrayOutputStream(headBytes);
        this.tail = new byte[tailBytes];
    }

    /** Adds the first {@code length} bytes of {@code buffer}. */
    void write(byte[] buffer, int length) {
        for (int index = 0; index < length; index++) {
            add(buffer[index]);
        }
    }

    private void add(byte value) {
        if (head.size() < headBytes) {
            head.write(value);
            return;
        }
        if (tailLength < tail.length) {
            tail[(tailStart + tailLength) % tail.length] = value;
            tailLength++;
            return;
        }
        byte dropped = tail[tailStart];
        if (dropped == '\n') {
            removedLines++;
        }
        removedBytes++;
        tail[tailStart] = value;
        tailStart = (tailStart + 1) % tail.length;
    }

    /**
     * What the model reads. A cut can land in the middle of a character, and
     * {@code new String(byte[], Charset)} replaces a bad byte with U+FFFD rather than throwing.
     * konacode never reports a cut as a broken file, and {@code Workspace.readUtf8Capped} states
     * the same rule for a file.
     */
    String text() {
        String first = head.toString(StandardCharsets.UTF_8);
        byte[] last = new byte[tailLength];
        for (int index = 0; index < tailLength; index++) {
            last[index] = tail[(tailStart + index) % tail.length];
        }
        String second = new String(last, StandardCharsets.UTF_8);
        if (removedBytes == 0) {
            return first + second;
        }
        return first + "\n<removed " + count(removedLines, "line") + ", "
                + count(removedBytes, "byte") + " from the middle>\n" + second;
    }

    /** The model reads this line, so "1 lines" must not appear in it. */
    private static String count(long value, String noun) {
        return value + " " + noun + (value == 1 ? "" : "s");
    }
}
