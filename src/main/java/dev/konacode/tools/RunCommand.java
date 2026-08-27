package dev.konacode.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
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
                Use this to build the project, to run the tests, or to run a program. \
                Do not use this to read a file, to list a folder, to change a file or to \
                delete a file. The tools read_file, list_files, edit_file and delete_file \
                do those, and konacode judges the path that each one touches. \
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
        String line = line(args);
        if (line == null) {
            return ToolResult.err("Give a command as a non-empty string in the field 'command'.");
        }
        Process process;
        try {
            process = new ProcessBuilder("sh", "-c", line)
                    .directory(workspace.root().toFile())
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException e) {
            return ToolResult.err("Could not start a shell for: " + line + ". " + e.getMessage());
        }
        closeInput(process);
        CappedOutput output = new CappedOutput(HEAD_BYTES, TAIL_BYTES);
        Thread drain = drain(process, output);
        return waitFor(process, drain, output, line);
    }

    private ToolResult waitFor(Process process, Thread drain, CappedOutput output, String line) {
        try {
            process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return kill(process, drain, "Interrupted while this command ran: " + line);
        }
        join(drain);
        synchronized (output) {
            return ToolResult.ok(output.text() + "\nexit " + process.exitValue());
        }
    }

    /**
     * A command with an open input holds the turn until it is stopped. A closed input makes it
     * fail at once instead.
     */
    private static void closeInput(Process process) {
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
            // The process is already gone, so there is no input to close.
        }
    }

    /**
     * Reads the output on its own thread. A pipe that fills stops the process, so somebody must
     * read it while the process runs.
     */
    private static Thread drain(Process process, CappedOutput output) {
        Thread thread = new Thread(() -> {
            byte[] buffer = new byte[8192];
            try (InputStream in = process.getInputStream()) {
                int read;
                while ((read = in.read(buffer)) != -1) {
                    synchronized (output) {
                        output.write(buffer, read);
                    }
                }
            } catch (IOException ignored) {
                // The process died. What arrived is what the model reads.
            }
        }, "konacode-command-output");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void join(Thread drain) {
        try {
            drain.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static ToolResult kill(Process process, Thread drain, String message) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        join(drain);
        return ToolResult.err(message);
    }

    /** The command line, or null when the argument is absent, not text, or blank. */
    private static String line(JsonNode args) {
        JsonNode command = args.path("command");
        if (!command.isTextual() || command.asText().isBlank()) {
            return null;
        }
        return command.asText();
    }

    /**
     * True when the line holds a character whose meaning depends on the environment or on the
     * filesystem.
     *
     * <p>This reads the characters and does not parse the line, so a quote defeats it:
     * {@code grep '$HOME' notes.txt} cannot expand, and konacode still offers no standing
     * permission for it. That answer is the safe one. A parser that tracked a quote, an escape
     * and a here-document would fail in the other direction, and one bug in it would give a
     * standing permission to a line that does expand.
     */
    static boolean expands(String line) {
        for (int index = 0; index < line.length(); index++) {
            if (EXPANDING.indexOf(line.charAt(index)) >= 0) {
                return true;
            }
        }
        return false;
    }
}
