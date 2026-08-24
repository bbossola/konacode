package dev.konacode.cli;

import dev.konacode.trace.TraceEvent.Outcome;
import dev.konacode.trace.TraceEvent.ReplyReceived;
import dev.konacode.trace.TraceEvent.TokensUsed;
import dev.konacode.trace.TraceEvent.TurnEnded;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceLineTest {

    @Test
    void namesTheOutcomeOfATurn() {
        String line = TraceLine.of(new TurnEnded(2, Outcome.EXHAUSTED, 8, 900));

        assertTrue(line.contains("EXHAUSTED"), line);
        assertTrue(line.contains("8"), line);
        assertTrue(line.contains("900"), line);
    }

    @Test
    void showsTheTokenCounts() {
        assertEquals("tokens 11 + 22 = 33", TraceLine.of(new TokensUsed(11, 22, 33)));
    }

    @Test
    void leavesAnEmptyBodyOut() {
        assertEquals("reply 200 in 15ms", TraceLine.of(new ReplyReceived(200, 15, "")));
    }

    @Test
    void putsABodyOnItsOwnLine() {
        String line = TraceLine.of(new ReplyReceived(200, 15, "{\"a\":1}"));

        assertEquals("reply 200 in 15ms\n{\"a\":1}", line);
    }
}
