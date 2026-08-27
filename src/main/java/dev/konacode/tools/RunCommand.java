package dev.konacode.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

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

    /** How long konacode waits for the last of the output after the command finishes. */
    static final long DRAIN_JOIN_MILLIS = 1000;

    /**
     * How long konacode waits between two questions about one running command.
     *
     * <p>A stop then takes about 70 ms, which a person reads as at once. A smaller value wakes
     * the thread more often and gains nothing a person can see. A larger value makes the stop
     * slower, and it lets the command pass its deadline by that same amount.
     */
    private static final long POLL_MILLIS = 50;

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
                Standard output and standard error come back together, and the last line is \
                `<exit N>`, where N is the exit code. \
                A process that keeps running after the command finishes may lose its output, \
                so run a job in the background only when you do not need what it prints. \
                A command that ends with a non-zero exit code is normal output, and not an \
                error: read the output and decide what to do. \
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
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            // The stop is asked first. When both are true in one 50 ms window, a user who pressed
            // ESC must read that they stopped it, and not that konacode ran out of time.
            while (!process.waitFor(POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                if (stop.stopped()) {
                    return kill(process, drain, stopMessage(line));
                }
                // The subtraction stays correct when nanoTime passes the end of its range.
                if (System.nanoTime() - deadline >= 0) {
                    return kill(process, drain, "This command did not finish in "
                            + seconds(timeout.toSeconds()) + " and was stopped: " + line);
                }
            }
        } catch (InterruptedException e) {
            // Cancellation sets its flag before it interrupts, so this tells a user who pressed
            // ESC from a thread that was interrupted for another reason.
            ToolResult stopped = kill(process, drain, stop.stopped()
                    ? stopMessage(line)
                    : "Interrupted while this command ran: " + line);
            Thread.currentThread().interrupt();
            return stopped;
        }
        // konacode cannot free a drain thread that an orphan holds open. A close does not wake a
        // read that already blocks on the pipe, and an interrupt does not either. The thread is a
        // daemon, so it never holds konacode open, and it ends when the orphan closes the pipe.
        // The reader is told with <output may be incomplete>.
        //
        // That marker catches a slow orphan and misses a fast one. When the JDK reaps the shell
        // it keeps the bytes already in the pipe and reports the end of the stream, so a drain
        // thread that is not yet blocked inside read ends cleanly and this answers true, while
        // output was lost. Measured at 13 runs in 200 of `(sleep 0.05; echo delayed) & echo now`.
        // A temporary file would remove the race and let one runaway command fill the disk, so
        // konacode keeps the pipe and the description tells the model.
        boolean whole = join(drain);
        synchronized (output) {
            return ToolResult.ok(output.text() + endOf(process, whole));
        }
    }

    private static String stopMessage(String line) {
        return "Stopped by the user before this command finished: " + line;
    }

    private static String seconds(long value) {
        return value + (value == 1 ? " second" : " seconds");
    }

    /**
     * The last line konacode adds. The angle brackets say that konacode writes this line and the
     * command does not, in the way {@code <removed …>} does. A command can print its own line
     * that reads {@code exit 0}, and the model must be able to tell the two apart.
     */
    private static String endOf(Process process, boolean whole) {
        StringBuilder end = new StringBuilder("\n<exit ").append(process.exitValue()).append('>');
        if (!whole) {
            end.append("\n<output may be incomplete: a background process still holds it open>");
        }
        return end.toString();
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
                // The stream ended before the output did. What arrived is what the model reads,
                // and the caller says so with <output may be incomplete>.
            }
        }, "konacode-command-output");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /** True when the drain thread finished. False when konacode stopped waiting for it. */
    private static boolean join(Thread drain) {
        try {
            drain.join(DRAIN_JOIN_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return !drain.isAlive();
    }

    /**
     * Ends the command and every process it still owns.
     *
     * <p>{@code descendants()} asks the operating system now, and it sees only a process that the
     * shell still owns. A shell that starts a background job and then exits gives that job to
     * process 1, and konacode cannot reach it. konacode ends what it can see.
     */
    private static ToolResult kill(Process process, Thread drain, String message) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        join(drain);
        return ToolResult.err(message);
    }

    /**
     * How long konacode waits for one command.
     *
     * <p>A wrong value is an error, for the reason {@code konacode.maxIterations} gives. The user
     * owns this value, and the model does not: a model that could raise it would escape the limit.
     */
    public static Duration configuredTimeout() {
        String configured = System.getProperty("konacode.command.timeoutSeconds");
        if (configured == null) {
            return DEFAULT_TIMEOUT;
        }
        long seconds;
        try {
            seconds = Long.parseLong(configured.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("konacode.command.timeoutSeconds must be a whole"
                    + " number of seconds, but was: " + configured);
        }
        if (seconds < 1) {
            throw new IllegalArgumentException("konacode.command.timeoutSeconds must be at least"
                    + " 1, but was: " + configured);
        }
        return Duration.ofSeconds(seconds);
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
