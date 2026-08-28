package dev.konacode.trace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.konacode.trace.TraceEvent.FromAgent;
import dev.konacode.trace.TraceEvent.Judged;
import dev.konacode.trace.TraceEvent.Outcome;
import dev.konacode.trace.TraceEvent.RequestSent;
import dev.konacode.trace.TraceEvent.ToolCalled;
import dev.konacode.trace.TraceEvent.TurnEnded;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void writesWhatTheJudgeAnswered() throws IOException {
        trace(Level.BASIC).emit(new Judged("run_command", "allow", 412, "mvn -q test"));

        JsonNode line = onlyLine();
        assertEquals("judged", line.get("event").asText());
        assertEquals("run_command", line.get("toolName").asText());
        assertEquals("mvn -q test", line.get("toolOperand").asText());
        assertEquals("allow", line.get("verdict").asText());
        assertEquals(412, line.get("millis").asLong());
    }

    @Test
    void appliesItsOwnLevel() throws IOException {
        trace(Level.BASIC).emit(new RequestSent("http://x", "gpt-5-mini", 3, 4, "{\"big\":1}"));

        assertEquals("", onlyLine().get("bodyJson").asText());
    }

    @Test
    void writesTheAgentBesideTheEventItMade() throws IOException {
        trace(Level.FULL).emit(new FromAgent("judge", new ToolCalled(2, "read_file", "{}")));

        JsonNode line = onlyLine();
        assertEquals("judge", line.get("agent").asText());
        assertEquals("tool_called", line.get("event").asText());
        assertEquals(2, line.get("turn").asInt());
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

    @TempDir
    Path directory;

    private List<String> names() throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void openMakesOneFileForTheSession() throws IOException {
        try (Trace trace = JsonlTrace.open(Level.BASIC, directory, 100, System.err)) {
            trace.emit(new ToolCalled(1, "read_file", "{}"));
        }

        assertEquals(1, names().size(), names().toString());
        assertTrue(names().get(0).endsWith(".jsonl"), names().get(0));
        assertEquals(1, Files.readAllLines(directory.resolve(names().get(0))).size());
    }

    @Test
    void openGivesBackTheEmptySinkWhenTheLevelIsOff() throws IOException {
        // @TempDir already makes `directory` before the test runs. This path stays untouched,
        // so it proves the OFF level makes no directory of its own.
        Path notMade = directory.resolve("traces");

        assertEquals(Trace.NONE, JsonlTrace.open(Level.OFF, notMade, 100, System.err));
        assertFalse(Files.exists(notMade));
    }

    @Test
    void theSweepRemovesTheOldestAndLeavesRoomForTheNewFile() throws IOException {
        for (String name : List.of("a.jsonl", "b.jsonl", "c.jsonl", "d.jsonl")) {
            Files.writeString(directory.resolve(name), "{}\n");
        }
        Files.writeString(directory.resolve("keep.txt"), "not a trace");

        JsonlTrace.sweep(directory, 3, System.err);

        assertEquals(List.of("c.jsonl", "d.jsonl", "keep.txt"), names());
    }

    @Test
    void theSweepDoesNothingWhenTheDirectoryHasRoom() throws IOException {
        Files.writeString(directory.resolve("a.jsonl"), "{}\n");

        JsonlTrace.sweep(directory, 100, System.err);

        assertEquals(List.of("a.jsonl"), names());
    }

    @Test
    void aDirectoryThatCannotBeSweptWarnsAndTheSessionContinues() {
        Path missing = directory.resolve("gone");

        JsonlTrace.sweep(missing, 3, new PrintStream(warnings, true, StandardCharsets.UTF_8));

        assertTrue(warnings.toString(StandardCharsets.UTF_8).contains("trace"),
                warnings.toString(StandardCharsets.UTF_8));
    }

    @Test
    void aFileThatCannotBeOpenedWarnsAndGivesBackTheEmptySink() throws IOException {
        Path blocked = directory.resolve("blocked");
        Files.writeString(blocked, "I am a file, not a directory");

        Trace trace = JsonlTrace.open(Level.BASIC, blocked, 100,
                new PrintStream(warnings, true, StandardCharsets.UTF_8));

        assertEquals(Trace.NONE, trace);
        assertTrue(warnings.toString(StandardCharsets.UTF_8).contains("trace"),
                warnings.toString(StandardCharsets.UTF_8));
    }
}
