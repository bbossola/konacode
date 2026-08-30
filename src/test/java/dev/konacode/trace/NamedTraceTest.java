package dev.konacode.trace;

import dev.konacode.trace.TraceEvent.FromAgent;
import dev.konacode.trace.TraceEvent.TokensUsed;
import dev.konacode.trace.TraceEvent.TurnStarted;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NamedTraceTest {

    private final RecordingTrace target = new RecordingTrace();

    @Test
    void everyEventIsPutInsideAFromAgent() {
        new NamedTrace("judge", target).emit(new TurnStarted(1, "go"));

        assertEquals(List.of(new FromAgent("judge", new TurnStarted(1, "go"))), target.events());
    }

    @Test
    void eachEventKeepsTheSameName() {
        NamedTrace trace = new NamedTrace("kona", target);

        trace.emit(new TurnStarted(1, "go"));
        trace.emit(new TokensUsed(1, 2, 3));

        assertEquals(List.of(new FromAgent("kona", new TurnStarted(1, "go")),
                new FromAgent("kona", new TokensUsed(1, 2, 3))), target.events());
    }

    @Test
    void aMissingNameIsAnError() {
        assertThrows(NullPointerException.class, () -> new NamedTrace(null, target));
        assertThrows(NullPointerException.class, () -> new NamedTrace("judge", null));
    }
}
