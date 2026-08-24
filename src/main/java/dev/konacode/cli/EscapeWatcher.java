package dev.konacode.cli;

import dev.konacode.agent.Cancellation;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.utils.NonBlockingReader;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
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

    private final Terminal terminal;
    private final Cancellation cancellation;
    private final AtomicBoolean running = new AtomicBoolean();
    private Thread thread;
    private Attributes saved;

    public EscapeWatcher(Terminal terminal, Cancellation cancellation) {
        this.terminal = terminal;
        this.cancellation = cancellation;
    }

    /**
     * Enters raw mode so one keystroke arrives without a newline, and turns signal generation
     * back on so ctrl-C still ends konacode.
     */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        saved = terminal.enterRawMode();
        Attributes signals = terminal.getAttributes();
        signals.setLocalFlag(Attributes.LocalFlag.ISIG, true);
        terminal.setAttributes(signals);

        thread = new Thread(() -> watch(terminal.reader(), cancellation, running::get),
                "konacode-escape");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Stops the watcher and restores the terminal's attributes.
     *
     * <p>The thread is never interrupted. It leaves within one poll, and interrupting it would
     * risk the interrupt landing on work that follows.
     */
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        try {
            thread.join(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        terminal.setAttributes(saved);
    }

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
