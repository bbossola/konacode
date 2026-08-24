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

    @Test
    void requestInterruptsTheArmedThread() {
        Cancellation cancellation = new Cancellation();
        cancellation.arm();
        try {
            cancellation.request();

            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            cancellation.disarm();
        }
    }

    @Test
    void requestDoesNotInterruptWhenNothingIsArmed() {
        Cancellation cancellation = new Cancellation();

        cancellation.request();

        assertFalse(Thread.interrupted());
    }

    @Test
    void disarmClearsTheInterruptStatus() {
        Cancellation cancellation = new Cancellation();
        cancellation.arm();
        cancellation.request();

        cancellation.disarm();

        assertFalse(Thread.interrupted());
    }

    @Test
    void disarmClearsTheStatusEvenWhenTheRequestRacesIt() throws Exception {
        // Runs the race many times. Without the lock, a request that reads the armed thread just
        // before disarm nulls it delivers the interrupt after the clear, and the status survives.
        for (int attempt = 0; attempt < 2000; attempt++) {
            Cancellation cancellation = new Cancellation();
            cancellation.arm();

            Thread requester = new Thread(cancellation::request);
            requester.start();
            cancellation.disarm();
            requester.join();

            assertFalse(Thread.interrupted(), "the interrupt status leaked on attempt " + attempt);
        }
    }
}
