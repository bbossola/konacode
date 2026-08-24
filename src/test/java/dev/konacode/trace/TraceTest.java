package dev.konacode.trace;

import dev.konacode.trace.TraceEvent.TokensUsed;
import dev.konacode.trace.TraceEvent.TurnStarted;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TraceTest {

    private static final class Recorder implements Trace {
        final List<TraceEvent> events = new ArrayList<>();

        @Override
        public void emit(TraceEvent event) {
            events.add(event);
        }
    }

    @Test
    void noneDiscardsEveryEvent() {
        Trace.NONE.emit(new TurnStarted(1, "hi"));
    }

    @Test
    void fanOutSendsOneEventToEverySink() {
        Recorder first = new Recorder();
        Recorder second = new Recorder();
        TraceEvent event = new TokensUsed(10, 20, 30);

        Trace.fanOut(first, second).emit(event);

        assertEquals(List.of(event), first.events);
        assertEquals(List.of(event), second.events);
    }

    @Test
    void anEventCarriesItsComponents() {
        TurnStarted started = new TurnStarted(3, "list the files");

        assertEquals(3, started.turn());
        assertEquals("list the files", started.userText());
    }

    private static final class ThrowingSink implements Trace {
        @Override
        public void emit(TraceEvent event) {
            throw new RuntimeException("a sink that fails");
        }
    }

    @Test
    void aSinkThatThrowsDoesNotStopTheNextSinkOrReachTheCaller() {
        ThrowingSink first = new ThrowingSink();
        Recorder second = new Recorder();
        TraceEvent event = new TokensUsed(10, 20, 30);

        Trace.fanOut(first, second).emit(event);

        assertEquals(List.of(event), second.events);
    }
}
