package dev.konacode.cli;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class SpinnerTest {

    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(captured, true, StandardCharsets.UTF_8);

    private String written() {
        return captured.toString(StandardCharsets.UTF_8);
    }

    /**
     * Waits for the first frame. start() returns before the thread draws, so a test that reads
     * the screen at once can read it empty.
     */
    private void awaitFirstFrame() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (written().isEmpty()) {
            if (System.nanoTime() >= deadline) {
                fail("the spinner drew no frame");
            }
            Thread.sleep(1);
        }
    }

    @Test
    void drawsTheLabelAfterItStarts() throws InterruptedException {
        Spinner spinner = new Spinner(out, "thinking");
        spinner.start();
        awaitFirstFrame();
        spinner.stop();

        assertTrue(written().contains("thinking"), written());
    }

    @Test
    void erasesTheLineWhenItStops() throws InterruptedException {
        Spinner spinner = new Spinner(out, "thinking");
        spinner.start();
        awaitFirstFrame();
        spinner.stop();

        // Spaces leave residue. Printing 8 characters over 40 spaces leaves 32 of them.
        assertTrue(written().endsWith("\r" + Ansi.ERASE_LINE), written());
    }

    @Test
    void writesNothingWhenItNeverStarts() {
        new Spinner(out, "thinking").stop();

        assertEquals("", written());
    }

    @Test
    void survivesTwoCallsToStopAndTwoCallsToStart() throws InterruptedException {
        Spinner spinner = new Spinner(out, "thinking");
        spinner.start();
        spinner.start();
        awaitFirstFrame();
        spinner.stop();
        spinner.stop();

        assertFalse(spinner.running());
    }

    @Test
    void stopsTheThread() throws InterruptedException {
        Spinner spinner = new Spinner(out, "thinking");
        spinner.start();
        awaitFirstFrame();
        assertTrue(spinner.running());

        spinner.stop();

        assertFalse(spinner.running());
    }
}
