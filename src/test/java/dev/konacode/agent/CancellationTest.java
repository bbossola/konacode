package dev.konacode.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CancellationTest {

    @Test
    void startsUnstopped() {
        assertFalse(new Cancellation().stopped());
    }

    @Test
    void requestStopsIt() {
        Cancellation cancellation = new Cancellation();

        cancellation.request();

        assertTrue(cancellation.stopped());
    }

    @Test
    void clearResetsIt() {
        Cancellation cancellation = new Cancellation();
        cancellation.request();

        cancellation.clear();

        assertFalse(cancellation.stopped());
    }
}
