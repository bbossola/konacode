package dev.konacode.cli;

import dev.konacode.trace.TraceEvent.FromAgent;
import dev.konacode.trace.TraceEvent.IterationStarted;
import dev.konacode.trace.TraceEvent.Judged;
import dev.konacode.trace.TraceEvent.Outcome;
import dev.konacode.trace.TraceEvent.ReplyReceived;
import dev.konacode.trace.TraceEvent.TokensUsed;
import dev.konacode.trace.TraceEvent.ToolCalled;
import dev.konacode.trace.TraceEvent.TurnEnded;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void aLinePrefixesTheAgentName() {
        assertEquals("judge> turn 1 iteration 1 of 1",
                TraceLine.of(new FromAgent("judge", new IterationStarted(1, 1, 1))));
    }

    @Test
    void aNameKeepsTheGuardOnThePayloadInsideIt() {
        String line = TraceLine.of(new FromAgent("judge",
                new ToolCalled(1, "run_command", "{\"command\":\"echo\nrm -rf /\"}")));

        assertEquals(1, line.lines().count(), line);
        assertTrue(line.startsWith("judge> "), line);
        assertTrue(line.contains("echo\u2400rm -rf /"), line);
    }

    @Test
    void showsWhatTheJudgeAnswered() {
        assertEquals("judged run_command `mvn -q test` allow",
                TraceLine.of(new Judged("run_command", "mvn -q test", "allow")));
    }

    @Test
    void guardsTheNameAndTheOperandOfAJudgement() {
        String line = TraceLine.of(new Judged("run\ncommand", "echo\nrm -rf /", "deny"));

        assertEquals(1, line.lines().count(), line);
        assertEquals("judged run\u2400command `echo\u2400rm -rf /` deny", line);
    }

    @Test
    void guardsTheArgumentsOfACall() {
        String line = TraceLine.of(new ToolCalled(1, "run_command",
                "{\"command\":\"echo\nrm -rf /\"}"));

        assertEquals(1, line.lines().count(), line);
        assertTrue(line.contains("echo\u2400rm -rf /"), line);
    }

    @Test
    void guardsTheBodyAndKeepsTheNewlineKonacodeWrites() {
        String line = TraceLine.of(new ReplyReceived(200, 15,
                "{\"a\":\"\u001B[2J\nb\"}"));

        assertEquals(2, line.lines().count(), line);
        assertFalse(line.contains("\u001B"), line);
    }
}
