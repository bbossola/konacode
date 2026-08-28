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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Stream;

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
