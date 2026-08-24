package dev.konacode.cli;

import dev.konacode.agent.Cancellation;
import dev.konacode.trace.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainTest {

    @AfterEach
    void clearTheProperty() {
        System.clearProperty("konacode.ui");
    }

    @Test
    void choosesThePlainInterfaceWhenAsked() throws Exception {
        System.setProperty("konacode.ui", "plain");

        assertInstanceOf(PlainUi.class, Main.selectUi(new Cancellation()));
    }

    @Test
    void choosesThePlainInterfaceForAPipe() throws Exception {
        System.setProperty("konacode.ui", "auto");

        assertInstanceOf(PlainUi.class, Main.selectUi(new Cancellation()));
    }

    @Test
    void defaultsToAuto() throws Exception {
        assertInstanceOf(PlainUi.class, Main.selectUi(new Cancellation()));
    }

    @Test
    void refusesAValueItCannotRead() {
        System.setProperty("konacode.ui", "rihc");

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> Main.selectUi(new Cancellation()));

        assertTrue(thrown.getMessage().contains("rihc"), thrown.getMessage());
    }

    @Test
    void aWrongTraceLevelIsAnError() {
        System.setProperty("konacode.trace", "loud");
        try {
            assertThrows(IllegalArgumentException.class, Level::configured);
        } finally {
            System.clearProperty("konacode.trace");
        }
    }
}
