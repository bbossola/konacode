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
 * must hold one lock across both.
 */
final class CappedOutput {

    private final ByteArrayOutputStream head = new ByteArrayOutputStream();
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

    /** What the model reads. The bytes are decoded as UTF-8, and a bad byte becomes U+FFFD. */
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
        return first + "\n… " + removedLines + " lines (" + removedBytes + " bytes) removed …\n"
                + second;
    }
}
