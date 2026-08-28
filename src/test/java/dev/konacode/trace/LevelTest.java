package dev.konacode.trace;

import dev.konacode.trace.TraceEvent.FromAgent;
import dev.konacode.trace.TraceEvent.Judged;
import dev.konacode.trace.TraceEvent.ReplyReceived;
import dev.konacode.trace.TraceEvent.RequestSent;
import dev.konacode.trace.TraceEvent.ToolCalled;
import dev.konacode.trace.TraceEvent.TokensUsed;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LevelTest {

    @Test
    void offKeepsNothing() {
        assertEquals(Optional.empty(), Level.OFF.keep(new TokensUsed(1, 2, 3)));
    }

    @Test
    void fullKeepsTheEventUnchanged() {
        RequestSent event = new RequestSent("http://x", "m", 2, 3, "{\"a\":1}");

        assertEquals(Optional.of(event), Level.FULL.keep(event));
    }

    @Test
    void basicDropsTheRequestBody() {
        RequestSent kept = assertInstanceOf(RequestSent.class,
                Level.BASIC.keep(new RequestSent("http://x", "m", 2, 3, "{\"a\":1}")).orElseThrow());

        assertEquals("", kept.bodyJson());
        assertEquals("m", kept.model());
        assertEquals(2, kept.messageCount());
    }

    @Test
    void basicDropsTheReplyBody() {
        ReplyReceived kept = assertInstanceOf(ReplyReceived.class,
                Level.BASIC.keep(new ReplyReceived(200, 12, "{\"b\":2}")).orElseThrow());

        assertEquals("", kept.bodyJson());
        assertEquals(200, kept.status());
    }

    @Test
    void basicCutsALongPayload() {
        String long_ = "x".repeat(5000);

        ToolCalled kept = assertInstanceOf(ToolCalled.class,
                Level.BASIC.keep(new ToolCalled(1, "read_file", long_)).orElseThrow());

        assertEquals(2049, kept.argumentsJson().length());
        assertTrue(kept.argumentsJson().endsWith("…"), kept.argumentsJson());
    }

    @Test
    void basicKeepsAShortPayloadWhole() {
        ToolCalled kept = assertInstanceOf(ToolCalled.class,
                Level.BASIC.keep(new ToolCalled(1, "read_file", "{\"path\":\"a\"}")).orElseThrow());

        assertEquals("{\"path\":\"a\"}", kept.argumentsJson());
    }

    @Test
    void basicCutsThePayloadInsideAFromAgentAndKeepsTheName() {
        FromAgent kept = assertInstanceOf(FromAgent.class, Level.BASIC
                .keep(new FromAgent("judge", new RequestSent("http://x", "m", 2, 3, "{\"a\":1}")))
                .orElseThrow());

        assertEquals("judge", kept.agent());
        assertEquals(new RequestSent("http://x", "m", 2, 3, ""), kept.event());
    }

    @Test
    void aFromAgentGoesWhenTheEventInsideItGoes() {
        assertEquals(Optional.empty(),
                Level.OFF.keep(new FromAgent("judge", new TokensUsed(1, 2, 3))));
    }

    @Test
    void basicKeepsAJudgement() {
        Judged event = new Judged("run_command", "mvn -q test", "allow");

        assertEquals(Optional.of(event), Level.BASIC.keep(event));
    }

    @Test
    void basicCutsTheOperandOfAJudgement() {
        Judged kept = assertInstanceOf(Judged.class,
                Level.BASIC.keep(new Judged("run_command", "x".repeat(5000), "deny")).orElseThrow());

        assertEquals(2049, kept.toolOperand().length());
        assertTrue(kept.toolOperand().endsWith("…"), kept.toolOperand());
        assertEquals("deny", kept.verdict());
    }

    @Test
    void parsesAName() {
        assertEquals(Optional.of(Level.BASIC), Level.parse("basic"));
        assertEquals(Optional.of(Level.FULL), Level.parse("FULL"));
        assertEquals(Optional.empty(), Level.parse("loud"));
    }

    @Test
    void theConfiguredLevelDefaultsToOff() {
        System.clearProperty("konacode.trace");

        assertEquals(Level.OFF, Level.configured());
    }

    @Test
    void aWrongConfiguredLevelIsAnError() {
        System.setProperty("konacode.trace", "loud");
        try {
            IllegalArgumentException e =
                    assertThrows(IllegalArgumentException.class, Level::configured);
            assertTrue(e.getMessage().contains("konacode.trace"), e.getMessage());
        } finally {
            System.clearProperty("konacode.trace");
        }
    }
}
