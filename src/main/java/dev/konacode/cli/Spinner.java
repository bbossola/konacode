package dev.konacode.cli;

import java.io.PrintStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Draws a moving character while the agent works.
 *
 * <p>The class is not final, so a test can give {@link RichUi} a subclass that records the calls.
 * The thread is a daemon thread, so it cannot keep the process alive.
 */
public class Spinner {

    private static final char[] FRAMES = {'|', '/', '-', '\\'};
    private static final long PERIOD = 120;

    private final PrintStream out;
    private final String label;
    private final AtomicBoolean running = new AtomicBoolean();
    private Thread thread;

    public Spinner(PrintStream out, String label) {
        this.out = out;
        this.label = label;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        thread = new Thread(this::draw, "konacode-spinner");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        thread.interrupt();
        try {
            thread.join(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        out.print("\r" + Ansi.ERASE_LINE);
        out.flush();
    }

    boolean running() {
        return running.get();
    }

    private void draw() {
        int frame = 0;
        while (running.get()) {
            out.print("\r" + Ansi.style(FRAMES[frame++ % FRAMES.length] + " " + label, Ansi.DIM));
            out.flush();
            try {
                Thread.sleep(PERIOD);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
