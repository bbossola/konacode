package dev.konacode.agent;

import dev.konacode.tools.ToolResult;
import dev.konacode.trace.Trace;
import dev.konacode.trace.TraceEvent;
import dev.konacode.trace.TraceEvent.Outcome;
import dev.konacode.trace.TraceEvent.ToolCalled;
import dev.konacode.trace.TraceEvent.ToolFinished;
import dev.konacode.trace.TraceEvent.TurnEnded;

import java.util.ArrayList;
import java.util.List;

/** Captures what the loop reported, so tests can assert on it without capturing stdout. */
final class RecordingTrace implements Trace {

    private final List<TraceEvent> events = new ArrayList<>();

    List<TraceEvent> events() {
        return events;
    }

    List<String> calls() {
        return events.stream()
                .filter(ToolCalled.class::isInstance)
                .map(ToolCalled.class::cast)
                .map(event -> event.name() + "(" + event.argumentsJson() + ")")
                .toList();
    }

    List<ToolResult> results() {
        return events.stream()
                .filter(ToolFinished.class::isInstance)
                .map(ToolFinished.class::cast)
                .map(event -> event.ok()
                        ? ToolResult.ok(event.output())
                        : ToolResult.err(event.output()))
                .toList();
    }

    List<Outcome> outcomes() {
        return events.stream()
                .filter(TurnEnded.class::isInstance)
                .map(TurnEnded.class::cast)
                .map(TurnEnded::outcome)
                .toList();
    }

    @Override
    public void emit(TraceEvent event) {
        events.add(event);
    }
}
