package dev.konacode.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.Objects;

/**
 * Runs one shell line with {@code sh -c}, and gives back what it printed.
 *
 * <p>A shell line, and not an argument list. The model writes a shell line, because every example
 * it read is a shell line. A list would also refuse a pipe and refuse {@code &&}, and the model
 * would then write {@code sh -c} inside the list.
 */
public final class RunCommand implements Tool {

    static final int HEAD_BYTES = 50_000;
    static final int TAIL_BYTES = 50_000;
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(600);

    /** Each character makes the line mean something else on another day. */
    private static final String EXPANDING = "$`*?[~";

    private final Workspace workspace;
    private final StopCheck stop;
    private final Duration timeout;

    public RunCommand(Workspace workspace, StopCheck stop, Duration timeout) {
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.stop = Objects.requireNonNull(stop, "stop");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    @Override
    public String name() {
        return "run_command";
    }

    @Override
    public String description() {
        return """
                Run a shell command in the project directory and give back what it printed. \
                The command runs with `sh -c`, so a pipe, `&&` and `;` all work. \
                Standard output and standard error come back together, and the last line gives \
                the exit code. A command that ends with a non-zero exit code is normal output, \
                and not an error: read the output and decide what to do. \
                The command gets no standard input, so a command that waits for input fails at \
                once. Long output keeps the first part and the last part.""";
    }

    @Override
    public ObjectNode inputSchema() {
        return Schemas.object()
                .requiredString("command", "The shell command line to run, for example 'mvn -q test'.")
                .build();
    }

    @Override
    public boolean stopsOnInterrupt() {
        return true;
    }

    @Override
    public Action computeAction(JsonNode args) {
        String line = line(args);
        if (line == null) {
            return Action.once(Effect.RUNS, name());
        }
        if (expands(line)) {
            // The line means something else on another day, so no standing permission is honest.
            return Action.once(Effect.RUNS, line);
        }
        return Action.of(Effect.RUNS, line, new Permission.ExactCommand(name(), line));
    }

    @Override
    public ToolResult execute(JsonNode args) {
        return ToolResult.err("Not implemented yet.");
    }

    /** The command line, or null when the argument is absent, not text, or blank. */
    private static String line(JsonNode args) {
        JsonNode command = args.path("command");
        if (!command.isTextual() || command.asText().isBlank()) {
            return null;
        }
        return command.asText();
    }

    static boolean expands(String line) {
        for (int index = 0; index < line.length(); index++) {
            if (EXPANDING.indexOf(line.charAt(index)) >= 0) {
                return true;
            }
        }
        return false;
    }
}
