package dev.konacode.trace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.konacode.trace.TraceEvent.Outcome;
import dev.konacode.trace.TraceEvent.RequestSent;
import dev.konacode.trace.TraceEvent.ToolCalled;
import dev.konacode.trace.TraceEvent.TurnEnded;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonlTraceTest {

    private final StringWriter written = new StringWriter();
    private final ByteArrayOutputStream warnings = new ByteArrayOutputStream();

    private JsonlTrace trace(Level level) {
        return new JsonlTrace(level, written, new PrintStream(warnings, true, StandardCharsets.UTF_8));
    }

    private JsonNode onlyLine() throws IOException {
        String[] lines = written.toString().split("\n");
        assertEquals(1, lines.length, written.toString());
        return new ObjectMapper().readTree(lines[0]);
    }

    @Test
    void writesOneLineForOneEvent() throws IOException {
        trace(Level.FULL).emit(new ToolCalled(2, "read_file", "{\"path\":\"a\"}"));

        JsonNode line = onlyLine();
        assertEquals("tool_called", line.get("event").asText());
        assertEquals(2, line.get("turn").asInt());
        assertEquals("read_file", line.get("name").asText());
        assertEquals("{\"path\":\"a\"}", line.get("argumentsJson").asText());
        assertTrue(line.hasNonNull("at"), line.toString());
    }

    @Test
    void writesTheOutcomeOfATurn() throws IOException {
        trace(Level.BASIC).emit(new TurnEnded(1, Outcome.EXHAUSTED, 8, 1234));

        JsonNode line = onlyLine();
        assertEquals("turn_ended", line.get("event").asText());
        assertEquals("EXHAUSTED", line.get("outcome").asText());
        assertEquals(8, line.get("iterations").asInt());
        assertEquals(1234, line.get("millis").asLong());
    }

    @Test
    void appliesItsOwnLevel() throws IOException {
        trace(Level.BASIC).emit(new RequestSent("http://x", "gpt-5-mini", 3, 4, "{\"big\":1}"));

        assertEquals("", onlyLine().get("bodyJson").asText());
    }

    @Test
    void writesNothingWhenTheLevelIsOff() {
        trace(Level.OFF).emit(new ToolCalled(1, "read_file", "{}"));

        assertEquals("", written.toString());
    }

    @Test
    void aWriteThatFailsWarnsOnceAndStopsTheSink() {
        Writer broken = new Writer() {
            @Override
            public void write(char[] buffer, int offset, int length) throws IOException {
                throw new IOException("disk full");
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        JsonlTrace trace = new JsonlTrace(Level.FULL, broken,
                new PrintStream(warnings, true, StandardCharsets.UTF_8));

        trace.emit(new ToolCalled(1, "read_file", "{}"));
        trace.emit(new ToolCalled(2, "read_file", "{}"));

        String warned = warnings.toString(StandardCharsets.UTF_8);
        assertEquals(1, warned.lines().count(), warned);
        assertTrue(warned.contains("disk full"), warned);
    }
}
