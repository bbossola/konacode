package dev.konacode.cli;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpinnerTest {

    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(captured, true, StandardCharsets.UTF_8);

    private String written() {
        return captured.toString(StandardCharsets.UTF_8);
    }

    @Test
    void drawsTheLabelAfterItStarts() throws InterruptedException {
        Spinner spinner = new Spinner(out, "thinking");
        spinner.start();
        Thread.sleep(250);
        spinner.stop();

        assertTrue(written().contains("thinking"), written());
    }

    @Test
    void erasesTheLineWhenItStops() throws InterruptedException {
        Spinner spinner = new Spinner(out, "thinking");
        spinner.start();
        Thread.sleep(250);
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
        Thread.sleep(150);
        spinner.stop();
        spinner.stop();

        assertFalse(spinner.running());
    }

    @Test
    void stopsTheThread() throws InterruptedException {
        Spinner spinner = new Spinner(out, "thinking");
        spinner.start();
        Thread.sleep(150);
        assertTrue(spinner.running());

        spinner.stop();

        assertFalse(spinner.running());
    }
}
