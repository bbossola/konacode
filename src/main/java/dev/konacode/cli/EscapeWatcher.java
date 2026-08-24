package dev.konacode.cli;

import dev.konacode.agent.Cancellation;
import org.jline.utils.NonBlockingReader;

import java.io.IOException;
import java.util.function.BooleanSupplier;

/**
 * Reads the terminal while the agent works, and stops the turn when the user presses ESC.
 *
 * <p>ESC is not a signal. It is the byte 0x1B on standard input, so something must read the
 * terminal during a turn. This is a sibling of {@link Spinner}: one daemon thread, start and
 * stop, both idempotent. It is not final, so a test can subclass it and record the calls.
 */
public class EscapeWatcher {

    static final int ESCAPE = 27;
    private static final long POLL_MILLIS = 100;

    /**
     * Reads until ESC arrives, the input ends, or the watcher is stopped.
     *
     * <p>Any 0x1B stops the turn, including the first byte of an arrow key sequence. During a
     * turn there is no line to edit, so this is correct, and one ESC press then works with no
     * timeout.
     */
    static void watch(NonBlockingReader reader, Cancellation cancellation, BooleanSupplier running) {
        try {
            while (running.getAsBoolean()) {
                int c = reader.read(POLL_MILLIS);
                if (c == ESCAPE) {
                    cancellation.request();
                    return;
                }
                if (c == NonBlockingReader.EOF) {
                    return;
                }
            }
        } catch (IOException e) {
            // The terminal went away. The turn is no longer stoppable, and saying so on the
            // screen would corrupt the output the agent is writing.
        }
    }
}
