# Observability — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One event stream reports what the agent does during a turn. A file records it, and the screen shows it when you ask.

**Architecture:** A new package `dev.konacode.trace` holds a sealed `TraceEvent`, a one-method `Trace` sink, and a `Level`. The package depends on no other konacode package, so the loop and the provider both emit into it. Two sinks read the stream, and each sink holds its own level: `JsonlTrace` writes one JSON line for each event to `~/.konacode/traces/`, and the `Ui` prints a coloured line. `ToolCallListener` is deleted, because the `Ui` now reads the same stream.

**Tech Stack:** Java 21, Maven, JUnit 5, Mockito, JLine, Jackson.

**Spec:** [the observability design](../specs/2026-08-24-observability-design.md). **Issue:** [#23](https://github.com/bbossola/konacode/issues/23).

---

## Before you start

konacode needs Java 21. The default java on this machine is 11.

```bash
sdk use java 21.0.2-open
mvn test
```

Expected: `BUILD SUCCESS`, 226 tests, 0 failures. If that fails, stop and fix the environment first.

Every test in this repository is offline. No test you write may touch the network.

Read [ARCHITECTURE.md](../../../ARCHITECTURE.md) and the "Definitions" section of
[CLAUDE.md](../../../CLAUDE.md) before you change the loop.

---

## File structure

**New files**

| File | Responsibility |
|---|---|
| `src/main/java/dev/konacode/trace/TraceEvent.java` | One thing that happened. A sealed interface, nine records. |
| `src/main/java/dev/konacode/trace/Trace.java` | Where an event goes. One method. |
| `src/main/java/dev/konacode/trace/Level.java` | How much of the stream a sink keeps. |
| `src/main/java/dev/konacode/trace/JsonlTrace.java` | The file sink. Owns the directory and the sweep. |
| `src/main/java/dev/konacode/llm/openai/Usage.java` | The token counts of one reply. |
| `src/main/java/dev/konacode/cli/TraceLine.java` | One event as one line of text, for the screen. |
| `src/test/java/dev/konacode/trace/TraceTest.java` | |
| `src/test/java/dev/konacode/trace/LevelTest.java` | |
| `src/test/java/dev/konacode/trace/JsonlTraceTest.java` | |
| `src/test/java/dev/konacode/cli/TraceLineTest.java` | |
| `src/test/java/dev/konacode/agent/RecordingTrace.java` | The hand-written double. Replaces `RecordingToolCallListener`. |

**Deleted files**

| File | Reason |
|---|---|
| `src/main/java/dev/konacode/agent/ToolCallListener.java` | The `Trace` stream replaces it. |
| `src/test/java/dev/konacode/agent/RecordingToolCallListener.java` | `RecordingTrace` replaces it. |

**Modified files**

| File | Change |
|---|---|
| `src/main/java/dev/konacode/agent/Agent.java` | Takes a `Trace`. Emits the five loop events. Counts the turn. |
| `src/main/java/dev/konacode/llm/openai/ChatCompletionsCodec.java` | Gains `decodeUsage`. |
| `src/main/java/dev/konacode/llm/openai/OpenAiClient.java` | Takes a `Trace`. Emits the four provider events. |
| `src/main/java/dev/konacode/cli/Ui.java` | Extends `Trace`. Gains `liveTrace`. |
| `src/main/java/dev/konacode/cli/PlainUi.java` | Implements `emit`. Holds the screen level. |
| `src/main/java/dev/konacode/cli/RichUi.java` | Implements `emit`. Holds the screen level. |
| `src/main/java/dev/konacode/cli/Commands.java` | Gains `/trace`. Splits the line into a name and an argument. |
| `src/main/java/dev/konacode/cli/Main.java` | Reads the two properties, opens the file, builds the fan-out. |
| `src/test/java/dev/konacode/cli/RecordingUi.java` | Implements `emit` and `liveTrace`. |
| The tests for each of the above | |
| `README.md`, `CLAUDE.md`, `ARCHITECTURE.md` | The properties, the command, the definitions. |

---

## Task 1: The event and the sink

**Files:**
- Create: `src/main/java/dev/konacode/trace/TraceEvent.java`
- Create: `src/main/java/dev/konacode/trace/Trace.java`
- Test: `src/test/java/dev/konacode/trace/TraceTest.java`

- [ ] **Step 1: Write the failing test**

```java
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
}
```

- [ ] **Step 2: Run the test and see it fail**

Run: `mvn test -Dtest=TraceTest`
Expected: FAIL. The compiler reports that package `dev.konacode.trace` does not exist.

- [ ] **Step 3: Write `TraceEvent`**

```java
package dev.konacode.trace;

/**
 * One thing that happened during a turn.
 *
 * <p>Every case carries plain values, and nothing from another konacode package. No case carries a
 * {@code Message}, a {@code ToolResult} or a {@code JsonNode}. That is what keeps this package free
 * of every other konacode package, and it is what lets the agent loop and the provider both emit
 * into it.
 *
 * <p>Sealed, so a new case is a compile error at every sink.
 */
public sealed interface TraceEvent {

    /** How a turn finished. The fact the screen hides today. */
    enum Outcome { ANSWERED, STOPPED, EXHAUSTED, FAILED }

    record TurnStarted(int turn, String userText) implements TraceEvent {}

    record IterationStarted(int turn, int iteration, int maxIterations) implements TraceEvent {}

    record ToolCalled(int turn, String name, String argumentsJson) implements TraceEvent {}

    record ToolFinished(int turn, String name, boolean ok, String output, long millis)
            implements TraceEvent {}

    record TurnEnded(int turn, Outcome outcome, int iterations, long millis)
            implements TraceEvent {}

    record RequestSent(String url, String model, int messageCount, int toolCount, String bodyJson)
            implements TraceEvent {}

    record ReplyReceived(int status, long millis, String bodyJson) implements TraceEvent {}

    record TokensUsed(int prompt, int completion, int total) implements TraceEvent {}

    record RetryRequested(String reason) implements TraceEvent {}
}
```

- [ ] **Step 4: Write `Trace`**

```java
package dev.konacode.trace;

import java.util.List;

/**
 * Where a trace event goes.
 *
 * <p>One method, so a sink is a lambda. A sink must never throw into the caller: the loop reports
 * what it did, and reporting must not be able to end a turn.
 */
public interface Trace extends AutoCloseable {

    /** Discards every event. */
    Trace NONE = event -> {
    };

    void emit(TraceEvent event);

    /**
     * One stream to several sinks.
     *
     * <p>The result does not close its sinks. The caller that opened a sink closes it.
     */
    static Trace fanOut(Trace... sinks) {
        List<Trace> all = List.of(sinks);
        return event -> {
            for (Trace sink : all) {
                sink.emit(event);
            }
        };
    }

    @Override
    default void close() {
    }
}
```

- [ ] **Step 5: Run the test and see it pass**

Run: `mvn test -Dtest=TraceTest`
Expected: PASS, 3 tests.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/dev/konacode/trace src/test/java/dev/konacode/trace
git commit -m "feat: add the trace event and the trace sink"
```

---

## Task 2: The level

`Level` holds the whole filter rule. A sink asks one question and renders the answer.

**Files:**
- Create: `src/main/java/dev/konacode/trace/Level.java`
- Test: `src/test/java/dev/konacode/trace/LevelTest.java`

- [ ] **Step 1: Write the failing test**

```java
package dev.konacode.trace;

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
```

- [ ] **Step 2: Run the test and see it fail**

Run: `mvn test -Dtest=LevelTest`
Expected: FAIL. The compiler reports that `Level` does not exist.

- [ ] **Step 3: Write `Level`**

```java
package dev.konacode.trace;

import dev.konacode.trace.TraceEvent.IterationStarted;
import dev.konacode.trace.TraceEvent.ReplyReceived;
import dev.konacode.trace.TraceEvent.RequestSent;
import dev.konacode.trace.TraceEvent.RetryRequested;
import dev.konacode.trace.TraceEvent.TokensUsed;
import dev.konacode.trace.TraceEvent.ToolCalled;
import dev.konacode.trace.TraceEvent.ToolFinished;
import dev.konacode.trace.TraceEvent.TurnEnded;
import dev.konacode.trace.TraceEvent.TurnStarted;

import java.util.Locale;
import java.util.Optional;

/**
 * How much of the stream a sink keeps.
 *
 * <p>The rule lives here and not in the sinks, because each sink has its own level. The screen can
 * show {@code FULL} while the file records {@code BASIC}, so one filter in front of both cannot
 * work.
 */
public enum Level {

    OFF, BASIC, FULL;

    private static final int CAP = 2048;

    /**
     * The event this level keeps. Empty means the sink writes nothing.
     *
     * <p>A {@code BASIC} answer is a new event with the payloads already cut, so a sink never cuts
     * a string itself.
     */
    public Optional<TraceEvent> keep(TraceEvent event) {
        return switch (this) {
            case OFF -> Optional.empty();
            case FULL -> Optional.of(event);
            case BASIC -> Optional.of(cut(event));
        };
    }

    public static Optional<Level> parse(String name) {
        for (Level level : values()) {
            if (level.name().equalsIgnoreCase(name.trim())) {
                return Optional.of(level);
            }
        }
        return Optional.empty();
    }

    /** The name a command shows. */
    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * The level of the file, for the whole session.
     *
     * <p>A wrong value is an error and not a silent fall back, for the reason
     * {@code konacode.maxIterations} gives: a typo that quietly does nothing goes unnoticed.
     */
    public static Level configured() {
        String configured = System.getProperty("konacode.trace", "off");
        return parse(configured).orElseThrow(() -> new IllegalArgumentException(
                "konacode.trace must be off, basic or full, but was: " + configured));
    }

    private static TraceEvent cut(TraceEvent event) {
        return switch (event) {
            case TurnStarted e -> new TurnStarted(e.turn(), cap(e.userText()));
            case ToolCalled e -> new ToolCalled(e.turn(), e.name(), cap(e.argumentsJson()));
            case ToolFinished e ->
                    new ToolFinished(e.turn(), e.name(), e.ok(), cap(e.output()), e.millis());
            case RequestSent e ->
                    new RequestSent(e.url(), e.model(), e.messageCount(), e.toolCount(), "");
            case ReplyReceived e -> new ReplyReceived(e.status(), e.millis(), "");
            case RetryRequested e -> new RetryRequested(cap(e.reason()));
            case IterationStarted e -> e;
            case TurnEnded e -> e;
            case TokensUsed e -> e;
        };
    }

    private static String cap(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= CAP ? text : text.substring(0, CAP) + "…";
    }
}
```

- [ ] **Step 4: Run the test and see it pass**

Run: `mvn test -Dtest=LevelTest`
Expected: PASS, 9 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/konacode/trace/Level.java src/test/java/dev/konacode/trace/LevelTest.java
git commit -m "feat: add the trace level"
```

---

## Task 3: The file sink writes

**Files:**
- Create: `src/main/java/dev/konacode/trace/JsonlTrace.java`
- Test: `src/test/java/dev/konacode/trace/JsonlTraceTest.java`

- [ ] **Step 1: Write the failing test**

```java
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
```

- [ ] **Step 2: Run the test and see it fail**

Run: `mvn test -Dtest=JsonlTraceTest`
Expected: FAIL. The compiler reports that `JsonlTrace` does not exist.

- [ ] **Step 3: Write `JsonlTrace`**

Write only the part the test needs. Task 4 adds `open`, the sweep and the property.

```java
package dev.konacode.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.konacode.trace.TraceEvent.IterationStarted;
import dev.konacode.trace.TraceEvent.ReplyReceived;
import dev.konacode.trace.TraceEvent.RequestSent;
import dev.konacode.trace.TraceEvent.RetryRequested;
import dev.konacode.trace.TraceEvent.TokensUsed;
import dev.konacode.trace.TraceEvent.ToolCalled;
import dev.konacode.trace.TraceEvent.ToolFinished;
import dev.konacode.trace.TraceEvent.TurnEnded;
import dev.konacode.trace.TraceEvent.TurnStarted;

import java.io.IOException;
import java.io.PrintStream;
import java.io.Writer;
import java.time.Instant;

/**
 * The file sink. One JSON object for each event, one line for each object.
 *
 * <p>It flushes every line. A trace that a crash loses is a trace that does not answer the
 * question you opened it for.
 *
 * <p>It never throws. A file that fails warns once and the sink then discards, because
 * observability that can end a session is worse than no observability.
 */
public final class JsonlTrace implements Trace {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Level level;
    private final Writer out;
    private final PrintStream warnings;
    private boolean broken;

    JsonlTrace(Level level, Writer out, PrintStream warnings) {
        this.level = level;
        this.out = out;
        this.warnings = warnings;
    }

    @Override
    public void emit(TraceEvent event) {
        if (broken) {
            return;
        }
        level.keep(event).ifPresent(this::write);
    }

    private void write(TraceEvent event) {
        try {
            out.write(line(event));
            out.write('\n');
            out.flush();
        } catch (IOException e) {
            warnings.println("The trace file failed and is now off: " + e.getMessage());
            broken = true;
        }
    }

    @Override
    public void close() {
        try {
            out.close();
        } catch (IOException e) {
            warnings.println("Could not close the trace file: " + e.getMessage());
        }
    }

    private String line(TraceEvent event) {
        ObjectNode node = mapper.createObjectNode();
        node.put("at", Instant.now().toString());
        switch (event) {
            case TurnStarted e -> {
                node.put("event", "turn_started");
                node.put("turn", e.turn());
                node.put("userText", e.userText());
            }
            case IterationStarted e -> {
                node.put("event", "iteration_started");
                node.put("turn", e.turn());
                node.put("iteration", e.iteration());
                node.put("maxIterations", e.maxIterations());
            }
            case ToolCalled e -> {
                node.put("event", "tool_called");
                node.put("turn", e.turn());
                node.put("name", e.name());
                node.put("argumentsJson", e.argumentsJson());
            }
            case ToolFinished e -> {
                node.put("event", "tool_finished");
                node.put("turn", e.turn());
                node.put("name", e.name());
                node.put("ok", e.ok());
                node.put("output", e.output());
                node.put("millis", e.millis());
            }
            case TurnEnded e -> {
                node.put("event", "turn_ended");
                node.put("turn", e.turn());
                node.put("outcome", e.outcome().name());
                node.put("iterations", e.iterations());
                node.put("millis", e.millis());
            }
            case RequestSent e -> {
                node.put("event", "request_sent");
                node.put("url", e.url());
                node.put("model", e.model());
                node.put("messageCount", e.messageCount());
                node.put("toolCount", e.toolCount());
                node.put("bodyJson", e.bodyJson());
            }
            case ReplyReceived e -> {
                node.put("event", "reply_received");
                node.put("status", e.status());
                node.put("millis", e.millis());
                node.put("bodyJson", e.bodyJson());
            }
            case TokensUsed e -> {
                node.put("event", "tokens_used");
                node.put("prompt", e.prompt());
                node.put("completion", e.completion());
                node.put("total", e.total());
            }
            case RetryRequested e -> {
                node.put("event", "retry_requested");
                node.put("reason", e.reason());
            }
        }
        return node.toString();
    }
}
```

- [ ] **Step 4: Run the test and see it pass**

Run: `mvn test -Dtest=JsonlTraceTest`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/konacode/trace/JsonlTrace.java src/test/java/dev/konacode/trace/JsonlTraceTest.java
git commit -m "feat: write the trace to a file"
```

---

## Task 4: The file sink opens the directory and sweeps it

konacode makes one file for each session and keeps the last 100.

**Files:**
- Modify: `src/main/java/dev/konacode/trace/JsonlTrace.java`
- Test: `src/test/java/dev/konacode/trace/JsonlTraceTest.java`

- [ ] **Step 1: Write the failing test**

Add these to `JsonlTraceTest`, and add the imports `java.nio.file.Files`, `java.nio.file.Path`,
`java.util.List`, `java.util.stream.Stream`, `org.junit.jupiter.api.io.TempDir` and
`static org.junit.jupiter.api.Assertions.assertFalse`.

```java
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
    void openGivesBackTheEmptySinkWhenTheLevelIsOff() {
        // Not the @TempDir field itself: JUnit makes that directory before the test runs.
        Path traces = directory.resolve("traces");

        assertEquals(Trace.NONE, JsonlTrace.open(Level.OFF, traces, 100, System.err));
        assertFalse(Files.exists(traces));
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

    @Test
    void theConfiguredCountDefaultsToOneHundred() {
        System.clearProperty("konacode.trace.maxFiles");

        assertEquals(100, JsonlTrace.configuredMaxFiles());
    }

    @Test
    void aCountThatIsNotAWholeNumberIsAnError() {
        System.setProperty("konacode.trace.maxFiles", "many");
        try {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    JsonlTrace::configuredMaxFiles);
            assertTrue(e.getMessage().contains("konacode.trace.maxFiles"), e.getMessage());
        } finally {
            System.clearProperty("konacode.trace.maxFiles");
        }
    }

    @Test
    void aCountBelowOneIsAnError() {
        System.setProperty("konacode.trace.maxFiles", "0");
        try {
            assertThrows(IllegalArgumentException.class, JsonlTrace::configuredMaxFiles);
        } finally {
            System.clearProperty("konacode.trace.maxFiles");
        }
    }
```

Add the import `static org.junit.jupiter.api.Assertions.assertThrows`.

- [ ] **Step 2: Run the test and see it fail**

Run: `mvn test -Dtest=JsonlTraceTest`
Expected: FAIL. The compiler reports that `open`, `sweep` and `configuredMaxFiles` do not exist.

- [ ] **Step 3: Add `open`, `sweep` and `configuredMaxFiles` to `JsonlTrace`**

Add these imports: `java.nio.charset.StandardCharsets`, `java.nio.file.Files`,
`java.nio.file.Path`, `java.time.ZoneId`, `java.time.format.DateTimeFormatter`, `java.util.List`,
`java.util.stream.Stream`.

```java
    public static final int DEFAULT_MAX_FILES = 100;

    private static final DateTimeFormatter NAME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss-SSS").withZone(ZoneId.systemDefault());

    /**
     * Opens the file for this session, after it removes the oldest files.
     *
     * <p>A failure here is a warning and never an exception. konacode then runs with
     * {@link Trace#NONE}.
     */
    public static Trace open(Level level, Path directory, int maxFiles, PrintStream warnings) {
        if (level == Level.OFF) {
            return Trace.NONE;
        }
        try {
            Files.createDirectories(directory);
            sweep(directory, maxFiles, warnings);
            Path file = directory.resolve(NAME.format(Instant.now()) + ".jsonl");
            return new JsonlTrace(level,
                    Files.newBufferedWriter(file, StandardCharsets.UTF_8), warnings);
        } catch (IOException e) {
            warnings.println("Could not open the trace file: " + e.getMessage());
            return Trace.NONE;
        }
    }

    /**
     * Removes the oldest trace files, and leaves room for the file this session is about to make.
     *
     * <p>The name of a file is the time it started, so a sort by name is a sort by age.
     */
    static void sweep(Path directory, int maxFiles, PrintStream warnings) {
        try (Stream<Path> files = Files.list(directory)) {
            List<Path> traces = files
                    .filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                    .sorted()
                    .toList();
            int excess = traces.size() - (maxFiles - 1);
            for (int index = 0; index < excess; index++) {
                Files.delete(traces.get(index));
            }
        } catch (IOException e) {
            warnings.println("Could not sweep the trace directory: " + e.getMessage());
        }
    }

    /**
     * How many trace files konacode keeps.
     *
     * <p>A wrong value is an error, for the reason {@code konacode.maxIterations} gives.
     */
    public static int configuredMaxFiles() {
        String configured = System.getProperty("konacode.trace.maxFiles");
        if (configured == null) {
            return DEFAULT_MAX_FILES;
        }
        int value;
        try {
            value = Integer.parseInt(configured.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "konacode.trace.maxFiles must be a whole number, but was: " + configured);
        }
        if (value < 1) {
            throw new IllegalArgumentException(
                    "konacode.trace.maxFiles must be 1 or more, but was: " + configured);
        }
        return value;
    }
```

- [ ] **Step 4: Run the test and see it pass**

Run: `mvn test -Dtest=JsonlTraceTest`
Expected: PASS, 14 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/konacode/trace/JsonlTrace.java src/test/java/dev/konacode/trace/JsonlTraceTest.java
git commit -m "feat: keep the last 100 trace files"
```

---

## Task 5: The `Ui` becomes a `Trace`

This step is additive. `Ui` extends both interfaces, and `emit` sends the two tool events to the
methods that already exist. Nothing changes on the screen. Task 6 then removes
`ToolCallListener`.

**Files:**
- Modify: `src/main/java/dev/konacode/cli/Ui.java`
- Modify: `src/main/java/dev/konacode/cli/PlainUi.java`
- Modify: `src/main/java/dev/konacode/cli/RichUi.java`
- Modify: `src/test/java/dev/konacode/cli/RecordingUi.java`

- [ ] **Step 1: Change `Ui`**

```java
public interface Ui extends ToolCallListener, Trace, AutoCloseable {
```

Add the import `dev.konacode.trace.Trace`.

- [ ] **Step 2: Add `emit` to `PlainUi`, `RichUi` and `RecordingUi`**

The same body in all three. Add the imports `dev.konacode.trace.TraceEvent`,
`dev.konacode.trace.TraceEvent.ToolCalled` and `dev.konacode.trace.TraceEvent.ToolFinished`, and
`dev.konacode.tools.ToolResult` where it is not there already.

```java
    @Override
    public void emit(TraceEvent event) {
        switch (event) {
            case ToolCalled called -> onToolCall(called.name(), called.argumentsJson());
            case ToolFinished finished -> onToolResult(finished.name(), finished.ok()
                    ? ToolResult.ok(finished.output())
                    : ToolResult.err(finished.output()));
            default -> {
            }
        }
    }
```

- [ ] **Step 3: Run the whole suite**

Run: `mvn test`
Expected: PASS, 226 tests. Nothing changed for the user.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/dev/konacode/cli src/test/java/dev/konacode/cli/RecordingUi.java
git commit -m "refactor: the ui reads the trace stream"
```

---

## Task 6: The loop emits, and `ToolCallListener` goes

**Files:**
- Create: `src/test/java/dev/konacode/agent/RecordingTrace.java`
- Delete: `src/main/java/dev/konacode/agent/ToolCallListener.java`
- Delete: `src/test/java/dev/konacode/agent/RecordingToolCallListener.java`
- Modify: `src/main/java/dev/konacode/agent/Agent.java`
- Modify: `src/main/java/dev/konacode/cli/Ui.java`, `PlainUi.java`, `RichUi.java`
- Modify: `src/test/java/dev/konacode/cli/RecordingUi.java`
- Modify: `src/test/java/dev/konacode/agent/AgentTest.java`

- [ ] **Step 1: Write the double**

`results()` gives back a `ToolResult`, so every assertion in `AgentTest` keeps working.

```java
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
```

- [ ] **Step 2: Write the failing tests**

Add these to `AgentTest`. Add the imports `dev.konacode.trace.TraceEvent.Outcome`,
`dev.konacode.trace.TraceEvent.IterationStarted`, `dev.konacode.trace.TraceEvent.TurnStarted` and
`dev.konacode.trace.TraceEvent.TurnEnded`.

```java
    @Test
    void reportsAnAnsweredTurn() {
        FakeLlmClient client = new FakeLlmClient().replyText("Hello.");
        RecordingTrace trace = new RecordingTrace();

        agent(client, ToolRegistry.of(new EchoTool("echo")), new AllowAllPolicy(), trace, 8)
                .respond("hi");

        assertEquals(List.of(Outcome.ANSWERED), trace.outcomes());
        assertEquals(new TurnStarted(1, "hi"), trace.events().get(0));
        assertEquals(new IterationStarted(1, 1, 8), trace.events().get(1));
    }

    @Test
    void reportsATurnThatRanOutOfIterations() {
        FakeLlmClient client = new FakeLlmClient()
                .reply(new AssistantMessage("", List.of(call("c1", "echo", "{}"))))
                .reply(new AssistantMessage("", List.of(call("c2", "echo", "{}"))));
        RecordingTrace trace = new RecordingTrace();

        agent(client, ToolRegistry.of(new EchoTool("echo")), new AllowAllPolicy(), trace, 2)
                .respond("go");

        assertEquals(List.of(Outcome.EXHAUSTED), trace.outcomes());
    }

    @Test
    void reportsATurnThatFailed() {
        FakeLlmClient client = new FakeLlmClient().failWith(new LlmException("HTTP 401"));
        RecordingTrace trace = new RecordingTrace();

        agent(client, ToolRegistry.of(new EchoTool("echo")), new AllowAllPolicy(), trace, 8)
                .respond("go");

        assertEquals(List.of(Outcome.FAILED), trace.outcomes());
    }

    @Test
    void countsTheTurns() {
        FakeLlmClient client = new FakeLlmClient().replyText("One.").replyText("Two.");
        RecordingTrace trace = new RecordingTrace();
        Agent agent = agent(client, ToolRegistry.of(new EchoTool("echo")), new AllowAllPolicy(),
                trace, 8);

        agent.respond("first");
        agent.respond("second");

        assertEquals(new TurnStarted(1, "first"), trace.events().get(0));
        assertEquals(new TurnStarted(2, "second"), trace.events().get(3));
    }
```

`FakeLlmClient.failWith` already exists, and `AgentTest` already imports `LlmException`.

There is a test in `AgentTest` for a stopped turn. Add
`assertEquals(List.of(Outcome.STOPPED), trace.outcomes());` to it, so all four outcomes are
proved.

- [ ] **Step 3: Rename the double through the test**

```bash
git rm src/test/java/dev/konacode/agent/RecordingToolCallListener.java
sed -i 's/RecordingToolCallListener/RecordingTrace/g' src/test/java/dev/konacode/agent/AgentTest.java
```

- [ ] **Step 4: Run the test and see it fail**

Run: `mvn test -Dtest=AgentTest`
Expected: FAIL. The compiler reports that `Agent` wants a `ToolCallListener`.

- [ ] **Step 5: Change `Agent`**

Replace the field, the constructor parameter, `respond` and `perform`. Add the imports
`dev.konacode.trace.Trace`, `dev.konacode.trace.TraceEvent.IterationStarted`,
`dev.konacode.trace.TraceEvent.Outcome`, `dev.konacode.trace.TraceEvent.ToolCalled`,
`dev.konacode.trace.TraceEvent.ToolFinished`, `dev.konacode.trace.TraceEvent.TurnEnded` and
`dev.konacode.trace.TraceEvent.TurnStarted`.

```java
    private final Trace trace;
    private int turn;
```

```java
    public Agent(LlmClient client,
                 ToolRegistry registry,
                 ToolPolicy policy,
                 Conversation conversation,
                 Trace trace,
                 Cancellation cancellation,
                 int maxIterations) {
        this.client = Objects.requireNonNull(client, "client");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.conversation = Objects.requireNonNull(conversation, "conversation");
        this.trace = Objects.requireNonNull(trace, "trace");
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
        if (maxIterations < 1) {
            throw new IllegalArgumentException("maxIterations must be at least 1.");
        }
        this.maxIterations = maxIterations;
    }
```

```java
    public String respond(String userText) {
        cancellation.clear();
        turn++;
        long started = System.nanoTime();
        trace.emit(new TurnStarted(turn, userText));
        conversation.add(new UserMessage(userText));
        List<ToolSpec> tools = ToolSpecs.from(registry);
        int iterations = 0;
        try {
            for (int iteration = 0; iteration < maxIterations; iteration++) {
                iterations = iteration + 1;
                trace.emit(new IterationStarted(turn, iterations, maxIterations));
                AssistantMessage reply = chat(tools);

                // Before running anything: providers reject a tool result whose originating
                // assistant message is absent from the history.
                conversation.add(reply);

                if (!reply.hasToolCalls()) {
                    return end(Outcome.ANSWERED, iterations, started, reply.text());
                }

                List<ToolCall> calls = reply.toolCalls();
                for (int index = 0; index < calls.size(); index++) {
                    if (cancellation.stopped()) {
                        return end(Outcome.STOPPED, iterations, started,
                                closeStoppedTurn(calls.subList(index, calls.size())));
                    }
                    ToolCall call = calls.get(index);
                    ToolResult result = perform(call);
                    conversation.add(new ToolMessage(call.id(), result.render()));
                }

                if (cancellation.stopped()) {
                    return end(Outcome.STOPPED, iterations, started, closeStoppedTurn(List.of()));
                }
            }
            return end(Outcome.EXHAUSTED, iterations, started,
                    fail("<error> Exceeded maximum tool iterations."));
        } catch (LlmException e) {
            if (cancellation.stopped()) {
                return end(Outcome.STOPPED, iterations, started, closeStoppedTurn(List.of()));
            }
            return end(Outcome.FAILED, iterations, started, fail("<error> " + e.getMessage()));
        }
    }

    /** Reports how the turn finished, and gives back the answer unchanged. */
    private String end(Outcome outcome, int iterations, long started, String answer) {
        trace.emit(new TurnEnded(turn, outcome, iterations, millisSince(started)));
        return answer;
    }

    private static long millisSince(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
```

```java
    private ToolResult perform(ToolCall call) {
        trace.emit(new ToolCalled(turn, call.name(), call.argumentsJson()));
        long started = System.nanoTime();
        ToolResult result = run(call);
        String output = switch (result) {
            case ToolResult.Ok ok -> ok.text();
            case ToolResult.Err err -> err.message();
        };
        trace.emit(new ToolFinished(turn, call.name(), result instanceof ToolResult.Ok, output,
                millisSince(started)));
        return result;
    }
```

- [ ] **Step 6: Move the rendering into `emit` and delete `ToolCallListener`**

In `Ui`, `PlainUi`, `RichUi` and `RecordingUi`, delete `onToolCall` and `onToolResult`, and put
their bodies in `emit`. `PlainUi`:

```java
    @Override
    public void emit(TraceEvent event) {
        if (event instanceof ToolCalled called) {
            out.println("tool: " + called.name() + "(" + called.argumentsJson() + ")");
        }
    }
```

`RichUi`, which keeps the spinner behaviour exactly as it is today:

```java
    @Override
    public void emit(TraceEvent event) {
        switch (event) {
            case ToolCalled called -> {
                spinner.stop();
                out.println(Ansi.style(
                        "tool: " + called.name() + "(" + called.argumentsJson() + ")", Ansi.GREEN));
            }
            case ToolFinished ignored -> spinner.start();
            default -> {
            }
        }
    }
```

`RecordingUi`:

```java
    @Override
    public void emit(TraceEvent event) {
        if (event instanceof ToolCalled called) {
            events.add("tool:" + called.name());
        }
    }
```

`Ui` then reads:

```java
public interface Ui extends Trace, AutoCloseable {
```

Delete the file `src/main/java/dev/konacode/agent/ToolCallListener.java`. `Main` needs no change:
it passes `ui` where the constructor now wants a `Trace`.

- [ ] **Step 7: Run the whole suite**

Run: `mvn test`
Expected: PASS. `PlainUiTest.printsOneLineForEachToolCall` calls `onToolCall`, and `RichUiTest`
calls both methods. Change each `onToolCall(name, args)` call to
`emit(new ToolCalled(1, name, args))`, and each `onToolResult` call to
`emit(new ToolFinished(1, "read_file", true, "text", 5))`. Add the imports
`dev.konacode.trace.TraceEvent.ToolCalled` and `dev.konacode.trace.TraceEvent.ToolFinished`.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat: the loop reports the whole turn"
```

---

## Task 7: The codec reads the token counts

**Files:**
- Create: `src/main/java/dev/konacode/llm/openai/Usage.java`
- Modify: `src/main/java/dev/konacode/llm/openai/ChatCompletionsCodec.java`
- Test: `src/test/java/dev/konacode/llm/openai/ChatCompletionsCodecTest.java`

- [ ] **Step 1: Write the failing test**

Add to `ChatCompletionsCodecTest`. Add the import `java.util.Optional`.

```java
    @Test
    void readsTheTokenCounts() {
        String body = """
                {"choices":[{"message":{"content":"hi"}}],
                 "usage":{"prompt_tokens":11,"completion_tokens":22,"total_tokens":33}}""";

        assertEquals(Optional.of(new Usage(11, 22, 33)), codec.decodeUsage(body));
    }

    @Test
    void reportsNoTokenCountsWhenTheReplyHasNone() {
        assertEquals(Optional.empty(),
                codec.decodeUsage("{\"choices\":[{\"message\":{\"content\":\"hi\"}}]}"));
    }

    @Test
    void reportsNoTokenCountsForABodyThatIsNotJson() {
        assertEquals(Optional.empty(), codec.decodeUsage("not json"));
    }
```

`codec` is the field the test class already holds.

- [ ] **Step 2: Run the test and see it fail**

Run: `mvn test -Dtest=ChatCompletionsCodecTest`
Expected: FAIL. The compiler reports that `Usage` and `decodeUsage` do not exist.

- [ ] **Step 3: Write `Usage`**

```java
package dev.konacode.llm.openai;

/** The token counts of one reply. */
public record Usage(int prompt, int completion, int total) {
}
```

- [ ] **Step 4: Add `decodeUsage` to the codec**

Add the import `java.util.Optional`.

```java
    /**
     * The token counts of a reply, when the provider reported them.
     *
     * <p>It never throws. The counts are a diagnostic, so a reply konacode cannot read here is a
     * reply with no counts, and never a failed turn. It parses the body a second time on purpose:
     * this class stays free of state, and each method is testable alone.
     */
    public Optional<Usage> decodeUsage(String body) {
        JsonNode usage;
        try {
            usage = mapper.readTree(body).path("usage");
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
        if (!usage.isObject()) {
            return Optional.empty();
        }
        return Optional.of(new Usage(
                usage.path("prompt_tokens").asInt(0),
                usage.path("completion_tokens").asInt(0),
                usage.path("total_tokens").asInt(0)));
    }
```

- [ ] **Step 5: Run the test and see it pass**

Run: `mvn test -Dtest=ChatCompletionsCodecTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/dev/konacode/llm/openai src/test/java/dev/konacode/llm/openai
git commit -m "feat: read the token counts of a reply"
```

---

## Task 8: The provider emits

**Files:**
- Modify: `src/main/java/dev/konacode/llm/openai/OpenAiClient.java`
- Test: `src/test/java/dev/konacode/llm/openai/OpenAiClientTest.java`

- [ ] **Step 1: Write the failing test**

Add to `OpenAiClientTest`. It uses the scripted sender that the file already uses for
`sendUntilAccepted`, so it stays offline.

`ScriptedSender`, `garbled()`, `plain()` and `validator()` are already in the test class. Use
them.

```java
    @Test
    void reportsEveryRetry() {
        List<TraceEvent> events = new ArrayList<>();
        ScriptedSender sender = new ScriptedSender(garbled(), plain("Two files here."));

        OpenAiClient.sendUntilAccepted(validator(), sender, events::add);

        assertEquals(1, events.size(), events.toString());
        assertInstanceOf(RetryRequested.class, events.get(0));
    }

    @Test
    void reportsNoRetryWhenTheFirstReplyIsAccepted() {
        List<TraceEvent> events = new ArrayList<>();

        OpenAiClient.sendUntilAccepted(validator(), new ScriptedSender(plain("Done.")),
                events::add);

        assertEquals(List.of(), events);
    }
```

Add the imports `dev.konacode.trace.Trace`, `dev.konacode.trace.TraceEvent`,
`dev.konacode.trace.TraceEvent.RetryRequested`, `java.util.ArrayList` and
`static org.junit.jupiter.api.Assertions.assertInstanceOf`.

A sink is a lambda, because `Trace` has one abstract method.

- [ ] **Step 2: Run the test and see it fail**

Run: `mvn test -Dtest=OpenAiClientTest`
Expected: FAIL. `sendUntilAccepted` takes two arguments.

- [ ] **Step 3: Change `OpenAiClient`**

Add the imports `dev.konacode.trace.Trace`, `dev.konacode.trace.TraceEvent.ReplyReceived`,
`dev.konacode.trace.TraceEvent.RequestSent`, `dev.konacode.trace.TraceEvent.RetryRequested` and
`dev.konacode.trace.TraceEvent.TokensUsed`.

`java.net.http.HttpRequest` and `TraceEvent.RequestSent` are two different things with similar
names. Keep the `HttpRequest` import and use the imported `RequestSent`.

```java
    private final Trace trace;

    public OpenAiClient(OpenAiConfig config, Trace trace) {
        this(config,
                HttpClient.newBuilder().connectTimeout(config.timeout()).build(),
                new ChatCompletionsCodec(new ObjectMapper()),
                trace);
    }

    public OpenAiClient(OpenAiConfig config, HttpClient http, ChatCompletionsCodec codec,
                        Trace trace) {
        this.config = config;
        this.http = http;
        this.codec = codec;
        this.trace = trace;
    }

    @Override
    public AssistantMessage chat(List<Message> history, List<ToolSpec> tools) {
        ObjectNode body = codec.encodeRequest(config.model(), history, tools);
        ReplyValidator validator = ReplyValidator.create(config.model(), tools);

        return sendUntilAccepted(validator,
                () -> sendOnce(body, history.size(), tools.size()), trace);
    }

    static AssistantMessage sendUntilAccepted(
            ReplyValidator validator, Supplier<AssistantMessage> send, Trace trace) {
        AssistantMessage reply = send.get();
        while (!validator.accepts(reply)) {
            trace.emit(new RetryRequested("The reply carried a tool call written as prose."));
            reply = send.get();
        }
        return reply;
    }
```

In `sendOnce`, take the two counts, and emit around the send. `ReplyReceived` is emitted for a
status that is not 2xx as well: that is the case you open the file for.

```java
    private AssistantMessage sendOnce(ObjectNode body, int messageCount, int toolCount) {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(config.chatCompletionsUri())
                    .timeout(config.timeout())
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + config.apiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                    .build();
        } catch (IllegalArgumentException e) {
            // A malformed base URL, or a key carrying a control character - a trailing newline
            // survives isBlank() - would otherwise escape as an unchecked exception and kill the
            // session, since the agent loop catches only LlmException.
            throw new LlmException("Could not build the request: " + e.getMessage(), e);
        }

        // The body and never the headers. The API key is a header, so it cannot reach a sink.
        trace.emit(new RequestSent(config.chatCompletionsUri().toString(), config.model(),
                messageCount, toolCount, body.toString()));

        long started = System.nanoTime();
        HttpResponse<String> response;
        try {
            response = http.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new LlmException(
                    "Request to " + config.chatCompletionsUri() + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("Request was interrupted.", e);
        }
        trace.emit(new ReplyReceived(response.statusCode(),
                (System.nanoTime() - started) / 1_000_000, response.body()));

        if (response.statusCode() / 100 != 2) {
            throw new LlmException(
                    "HTTP " + response.statusCode() + ": " + truncate(response.body()));
        }

        codec.decodeUsage(response.body()).ifPresent(usage ->
                trace.emit(new TokensUsed(usage.prompt(), usage.completion(), usage.total())));

        return codec.decodeResponse(response.body());
    }
```

- [ ] **Step 4: Fix every caller**

`OpenAiClientTest` builds a client at line 22 and calls `sendUntilAccepted` three times. Pass
`Trace.NONE` in each. `Main` is fixed in Task 11.

- [ ] **Step 5: Run the test and see it pass**

Run: `mvn test -Dtest=OpenAiClientTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/dev/konacode/llm/openai src/test/java/dev/konacode/llm/openai
git commit -m "feat: the provider reports the request and the reply"
```

---

## Task 9: The screen shows the trace

**Files:**
- Create: `src/main/java/dev/konacode/cli/TraceLine.java`
- Test: `src/test/java/dev/konacode/cli/TraceLineTest.java`
- Modify: `src/main/java/dev/konacode/cli/Ui.java`, `PlainUi.java`, `RichUi.java`
- Modify: `src/test/java/dev/konacode/cli/RecordingUi.java`, `PlainUiTest.java`, `RichUiTest.java`

- [ ] **Step 1: Write the failing test for `TraceLine`**

```java
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
```

- [ ] **Step 2: Run the test and see it fail**

Run: `mvn test -Dtest=TraceLineTest`
Expected: FAIL. `TraceLine` does not exist.

- [ ] **Step 3: Write `TraceLine`**

```java
package dev.konacode.cli;

import dev.konacode.trace.TraceEvent;
import dev.konacode.trace.TraceEvent.IterationStarted;
import dev.konacode.trace.TraceEvent.ReplyReceived;
import dev.konacode.trace.TraceEvent.RequestSent;
import dev.konacode.trace.TraceEvent.RetryRequested;
import dev.konacode.trace.TraceEvent.TokensUsed;
import dev.konacode.trace.TraceEvent.ToolCalled;
import dev.konacode.trace.TraceEvent.ToolFinished;
import dev.konacode.trace.TraceEvent.TurnEnded;
import dev.konacode.trace.TraceEvent.TurnStarted;

/** One event as one line of text. The two interfaces share the words. */
final class TraceLine {

    private TraceLine() {
    }

    static String of(TraceEvent event) {
        return switch (event) {
            case TurnStarted e -> "turn " + e.turn() + " started";
            case IterationStarted e ->
                    "turn " + e.turn() + " iteration " + e.iteration() + " of " + e.maxIterations();
            case ToolCalled e -> "tool " + e.name() + " " + e.argumentsJson();
            case ToolFinished e ->
                    "tool " + e.name() + (e.ok() ? " ok" : " error") + " in " + e.millis() + "ms";
            case TurnEnded e -> "turn " + e.turn() + " " + e.outcome() + " after "
                    + e.iterations() + " iterations, " + e.millis() + "ms";
            case RequestSent e -> "request " + e.model() + ", " + e.messageCount()
                    + " messages, " + e.toolCount() + " tools" + body(e.bodyJson());
            case ReplyReceived e -> "reply " + e.status() + " in " + e.millis() + "ms"
                    + body(e.bodyJson());
            case TokensUsed e -> "tokens " + e.prompt() + " + " + e.completion() + " = " + e.total();
            case RetryRequested e -> "retry: " + e.reason();
        };
    }

    private static String body(String json) {
        return json.isEmpty() ? "" : "\n" + json;
    }
}
```

- [ ] **Step 4: Run the test and see it pass**

Run: `mvn test -Dtest=TraceLineTest`
Expected: PASS, 4 tests.

- [ ] **Step 5: Write the failing test for the screen level**

Add to `PlainUiTest`. `ui(String)` and `written()` are the helpers the class already holds.

```java
    @Test
    void showsNoTraceEventWhenTheScreenIsOff() {
        ui("").emit(new TurnEnded(1, Outcome.ANSWERED, 2, 30));

        assertEquals("", written());
    }

    @Test
    void showsATraceEventWhenTheScreenIsOn() {
        PlainUi ui = ui("");
        ui.liveTrace(Level.BASIC);

        ui.emit(new TurnEnded(1, Outcome.ANSWERED, 2, 30));

        assertTrue(written().contains("ANSWERED"), written());
    }

    @Test
    void alwaysShowsTheToolCall() {
        ui("").emit(new ToolCalled(1, "read_file", "{}"));

        assertTrue(written().contains("tool: read_file({})"), written());
    }
```

Add the imports `dev.konacode.trace.Level`, `dev.konacode.trace.TraceEvent.Outcome`,
`dev.konacode.trace.TraceEvent.ToolCalled` and `dev.konacode.trace.TraceEvent.TurnEnded`.

- [ ] **Step 6: Run the test and see it fail**

Run: `mvn test -Dtest=PlainUiTest`
Expected: FAIL. `liveTrace` does not exist.

- [ ] **Step 7: Add `liveTrace` to `Ui`**

```java
    /** How much of the trace the screen shows. `/trace` changes it. */
    void liveTrace(Level level);

    Level liveTrace();
```

Add the import `dev.konacode.trace.Level`.

- [ ] **Step 8: Implement it in `PlainUi`**

```java
    private Level live = Level.OFF;

    @Override
    public void liveTrace(Level level) {
        this.live = level;
    }

    @Override
    public Level liveTrace() {
        return live;
    }

    @Override
    public void emit(TraceEvent event) {
        if (event instanceof ToolCalled called) {
            out.println("tool: " + called.name() + "(" + called.argumentsJson() + ")");
            return;
        }
        live.keep(event).ifPresent(kept -> out.println("trace: " + TraceLine.of(kept)));
    }
```

- [ ] **Step 9: Implement it in `RichUi`**

The trace is magenta. Green is a tool, blue is the prompt, red is an error and cyan is the banner.

```java
    private Level live = Level.OFF;

    @Override
    public void liveTrace(Level level) {
        this.live = level;
    }

    @Override
    public Level liveTrace() {
        return live;
    }

    @Override
    public void emit(TraceEvent event) {
        if (event instanceof ToolCalled called) {
            spinner.stop();
            out.println(Ansi.style(
                    "tool: " + called.name() + "(" + called.argumentsJson() + ")", Ansi.GREEN));
            return;
        }
        live.keep(event).ifPresent(kept -> {
            spinner.stop();
            out.println(Ansi.style("trace: " + TraceLine.of(kept), Ansi.MAGENTA));
        });
        if (event instanceof ToolFinished) {
            spinner.start();
        }
    }
```

- [ ] **Step 10: Implement it in `RecordingUi`**

```java
    Level live = Level.OFF;

    @Override
    public void liveTrace(Level level) {
        this.live = level;
    }

    @Override
    public Level liveTrace() {
        return live;
    }
```

- [ ] **Step 11: Run the whole suite**

Run: `mvn test`
Expected: PASS.

- [ ] **Step 12: Commit**

```bash
git add -A
git commit -m "feat: show the trace on the screen"
```

---

## Task 10: The `/trace` command

**Files:**
- Modify: `src/main/java/dev/konacode/cli/Commands.java`
- Test: `src/test/java/dev/konacode/cli/CommandsTest.java`

- [ ] **Step 1: Write the failing test**

Add to `CommandsTest`, and change the `commands` helper to pass a file level.

```java
    private Commands commands(RecordingUi ui, Conversation conversation) {
        Workspace workspace = new Workspace(root);
        return new Commands(conversation, SYSTEM,
                ToolRegistry.of(new ListFiles(workspace, StopCheck.NEVER),
                        new ReadFile(workspace, StopCheck.NEVER)), ui, Level.BASIC);
    }

    @Test
    void traceWithNoLevelShowsBothLevels() {
        RecordingUi ui = new RecordingUi();

        commands(ui, new Conversation(SYSTEM)).run("/trace");

        String shown = String.join("\n", ui.answers);
        assertTrue(shown.contains("off"), shown);
        assertTrue(shown.contains("basic"), shown);
    }

    @Test
    void traceSetsTheLevelOfTheScreen() {
        RecordingUi ui = new RecordingUi();

        commands(ui, new Conversation(SYSTEM)).run("/trace full");

        assertEquals(Level.FULL, ui.liveTrace());
    }

    @Test
    void traceRefusesAnUnknownLevel() {
        RecordingUi ui = new RecordingUi();

        commands(ui, new Conversation(SYSTEM)).run("/trace loud");

        assertEquals(1, ui.errors.size());
        assertTrue(ui.errors.get(0).contains("loud"), ui.errors.get(0));
        assertEquals(Level.OFF, ui.liveTrace());
    }

    @Test
    void helpNamesTrace() {
        RecordingUi ui = new RecordingUi();

        commands(ui, new Conversation(SYSTEM)).run("/help");

        assertTrue(String.join("\n", ui.answers).contains("/trace"), ui.answers.toString());
    }
```

Add the import `dev.konacode.trace.Level`.

- [ ] **Step 2: Run the test and see it fail**

Run: `mvn test -Dtest=CommandsTest`
Expected: FAIL. The constructor takes four arguments.

- [ ] **Step 3: Change `Commands`**

The switch reads the first word, so `/trace full` reaches the same case as `/trace`. No test
depends on `/help extra` being unknown.

```java
    private final Level fileLevel;

    Commands(Conversation conversation, Message systemMessage, ToolRegistry registry, Ui ui,
             Level fileLevel) {
        this.conversation = conversation;
        this.systemMessage = systemMessage;
        this.registry = registry;
        this.ui = ui;
        this.fileLevel = fileLevel;
    }

    /** Returns false when the session must end. */
    boolean run(String line) {
        String[] parts = line.trim().split("\\s+", 2);
        String name = parts[0];
        String argument = parts.length > 1 ? parts[1].trim() : "";
        switch (name) {
            case "/help" -> help();
            case "/tools" -> tools();
            case "/clear" -> clear();
            case "/trace" -> trace(argument);
            case "/exit" -> {
                return false;
            }
            default -> ui.showError("Unknown command: " + line + ". Type /help for the list.");
        }
        return true;
    }

    private void trace(String argument) {
        if (argument.isEmpty()) {
            ui.showAnswer("The screen shows `" + ui.liveTrace().label()
                    + "`. The file records `" + fileLevel.label() + "`.");
            return;
        }
        Optional<Level> level = Level.parse(argument);
        if (level.isEmpty()) {
            ui.showError("Unknown level: " + argument + ". Use off, basic or full.");
            return;
        }
        ui.liveTrace(level.get());
        ui.showAnswer("The screen now shows `" + level.get().label() + "`.");
    }
```

Add the imports `dev.konacode.trace.Level` and `java.util.Optional`.

Add the line to `help()`:

```java
    private void help() {
        ui.showAnswer("""
                ```
                esc      stop the turn
                /help    show this list
                /tools   show the tools the model can call
                /trace   show or set how much the screen reports
                /clear   forget the conversation and start again
                /exit    end the session
                ```""");
    }
```

- [ ] **Step 4: Run the test and see it pass**

Run: `mvn test -Dtest=CommandsTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/konacode/cli/Commands.java src/test/java/dev/konacode/cli/CommandsTest.java
git commit -m "feat: add the /trace command"
```

---

## Task 11: `Main` wires it

**Files:**
- Modify: `src/main/java/dev/konacode/cli/Main.java`
- Test: `src/test/java/dev/konacode/cli/MainTest.java`

- [ ] **Step 1: Write the failing test**

Add to `MainTest`. It proves the wrong value fails loudly, which is the rule in CLAUDE.md. Follow
how `MainTest` tests `konacode.ui` today, and use the same shape.

```java
    @Test
    void aWrongTraceLevelIsAnError() {
        System.setProperty("konacode.trace", "loud");
        try {
            assertThrows(IllegalArgumentException.class, Level::configured);
        } finally {
            System.clearProperty("konacode.trace");
        }
    }
```

- [ ] **Step 2: Change `Main`**

```java
        Cancellation cancellation = new Cancellation();
        OpenAiConfig config;
        int maxIterations;
        Level traceLevel;
        int maxTraceFiles;
        Ui ui;
        try {
            config = OpenAiConfig.fromEnvironment(System.getenv());
            maxIterations = Agent.configuredMaxIterations();
            traceLevel = Level.configured();
            maxTraceFiles = JsonlTrace.configuredMaxFiles();
            ui = selectUi(cancellation);
        } catch (IllegalArgumentException | IOException e) {
            System.err.println(e.getMessage());
            System.exit(1);
            return;
        }

        Trace file = JsonlTrace.open(traceLevel,
                Path.of(System.getProperty("user.home"), ".konacode", "traces"),
                maxTraceFiles, System.err);
        Trace trace = Trace.fanOut(ui, file);

        Workspace workspace = Workspace.ofCurrentDirectory();
        ToolRegistry registry = ToolRegistry.of(
                new ListFiles(workspace, cancellation),
                new ReadFile(workspace, cancellation),
                new EditFile(workspace, cancellation),
                new DeleteFile(workspace));
        SystemMessage system = new SystemMessage(SYSTEM_PROMPT);
        Conversation conversation = new Conversation(system);

        Agent agent = new Agent(new OpenAiClient(config, trace), registry, new AllowAllPolicy(),
                conversation, trace, cancellation, maxIterations);

        try (ui; file) {
            new Repl(agent, ui, new Commands(conversation, system, registry, ui, traceLevel)).run();
        } catch (Exception e) {
            System.err.println(e.getMessage());
            System.exit(1);
        }
```

Add the imports `dev.konacode.trace.JsonlTrace`, `dev.konacode.trace.Level`,
`dev.konacode.trace.Trace` and `java.nio.file.Path`.

- [ ] **Step 3: Run the whole suite**

Run: `mvn test`
Expected: PASS.

- [ ] **Step 4: Try it by hand, with no key**

```bash
mvn -q package -DskipTests
echo "/trace basic" | KONACODE_MODEL=x OPENAI_API_KEY=x java -Dkonacode.ui=plain -Dkonacode.trace=basic -jar target/konacode.jar
ls ~/.konacode/traces | tail -3
```

Expected: the command prints the two levels, and `ls` shows a new `.jsonl` file.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/konacode/cli/Main.java src/test/java/dev/konacode/cli/MainTest.java
git commit -m "feat: wire the trace into the session"
```

---

## Task 12: The documents

**Files:**
- Modify: `README.md`, `CLAUDE.md`, `ARCHITECTURE.md`

- [ ] **Step 1: `README.md`**

Change "Plus two system properties" to "Plus four system properties", and add the two rows:

```markdown
| `konacode.trace` | `off`, `basic`, `full`, default `off` | how much the trace file records |
| `konacode.trace.maxFiles` | a whole number, default `100` | how many trace files konacode keeps |
```

Change "Three commands work in both interfaces" to "Five commands work in both interfaces", and
add the row:

```markdown
| `/trace` | show or set how much the screen reports |
```

Add a paragraph after the command table:

```markdown
konacode writes a trace of each session to `~/.konacode/traces/`, one JSON line for each event.
`konacode.trace=basic` records the loop, the times, the outcome of each turn and the token counts.
`konacode.trace=full` adds the request and the reply, so you can replay a call. `/trace basic`
shows the same events on the screen while the session runs.
```

- [ ] **Step 2: `CLAUDE.md`**

Add the two properties to the configuration table. Remove the `ToolCallListener` row from the
`dev.konacode.agent` table. Change the `Ui` row to say it extends `Trace`. Change the `Commands`
row to name `/trace`. Add a `TraceLine` row to the `dev.konacode.cli` table. Add a new section
before `dev.konacode.agent`:

```markdown
### `dev.konacode.trace` — what happened during a turn

| Element | Kind | Definition |
|---|---|---|
| `TraceEvent` | sealed interface | One thing that happened. Nine records. Each carries plain values and nothing from another konacode package, so `agent` and `llm` can both emit into it. |
| `Trace` | interface | `void emit(TraceEvent)`. `NONE` discards, `fanOut` combines. A sink never throws into the caller. |
| `Level` | enum | `OFF`, `BASIC`, `FULL`. `keep(TraceEvent)` gives back the event a level keeps, with the payloads already cut. The rule lives here, because each sink holds its own level. |
| `JsonlTrace` | implements `Trace` | The file sink. One JSON line for each event, in `~/.konacode/traces/`, one file for each session. It sweeps the oldest files when it opens, and it flushes every line. |
```

Change the dependency rule to:

```
cli -> agent -> { llm, tools, policy } -> trace
```

- [ ] **Step 3: `ARCHITECTURE.md`**

Add the trace to the picture of a turn: the loop emits `TurnStarted`, one `IterationStarted` for
each round, `ToolCalled` and `ToolFinished` for each tool, and one `TurnEnded` with the outcome.
The provider emits `RequestSent`, `ReplyReceived`, `TokensUsed` and `RetryRequested`. Keep it to
one short paragraph and one list.

- [ ] **Step 4: Run the whole suite one last time**

Run: `mvn test`
Expected: `BUILD SUCCESS`, 0 failures. Count the tests and use the number in the CLAUDE.md
"Commands" section, which says 226 today.

- [ ] **Step 5: Commit and close the issue**

```bash
git add -A
git commit -m "docs: record the trace

Closes #23"
```

---

## When you are finished

Run `mvn test` and `mvn package`. Then read the design once more and check every row of its
"Wiring" table against the code. Report anything you did not do, and why.
