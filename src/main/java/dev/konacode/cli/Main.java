package dev.konacode.cli;

import dev.konacode.agent.Agent;
import dev.konacode.agent.Approvals;
import dev.konacode.agent.Cancellation;
import dev.konacode.agent.Conversation;
import dev.konacode.llm.LlmClient;
import dev.konacode.llm.Message.SystemMessage;
import dev.konacode.llm.openai.OpenAiClient;
import dev.konacode.llm.openai.OpenAiConfig;
import dev.konacode.policy.AllowAllPolicy;
import dev.konacode.policy.EffectPolicy;
import dev.konacode.policy.SelectedPolicy;
import dev.konacode.policy.ToolPolicy;
import dev.konacode.skills.SkillRegistry;
import dev.konacode.tools.DeleteFile;
import dev.konacode.tools.EditFile;
import dev.konacode.tools.ListFiles;
import dev.konacode.tools.ReadFile;
import dev.konacode.tools.RunCommand;
import dev.konacode.tools.ToolRegistry;
import dev.konacode.tools.Workspace;
import dev.konacode.trace.JsonlTrace;
import dev.konacode.trace.Level;
import dev.konacode.trace.Trace;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public final class Main {

    private static final String SYSTEM_PROMPT = "You are konacode, a concise CLI assistant.";

    static final int DEFAULT_MAX_ITERATIONS = 8;
    static final int DEFAULT_MAX_TRACE_FILES = 100;
    static final Duration DEFAULT_COMMAND_TIMEOUT = Duration.ofSeconds(600);

    private Main() {
    }

    public static void main(String[] args) {
        Cancellation cancellation = new Cancellation();
        OpenAiConfig config;
        int maxIterations;
        Level traceLevel;
        int maxTraceFiles;
        Duration commandTimeout;
        Ui ui;
        try {
            config = OpenAiConfig.fromEnvironment(System.getenv());
            maxIterations = maxIterations();
            traceLevel = Level.configured();
            maxTraceFiles = maxTraceFiles();
            commandTimeout = commandTimeout();
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
        // JsonlTrace.open falls back to Trace.NONE when it cannot open the file, so the
        // configured level is not always the level the file got.
        Level fileLevel = file == Trace.NONE ? Level.OFF : traceLevel;

        Workspace workspace = workspace();
        SkillRegistry skills = new SkillRegistry(new Workspace(skillsRoot()));

        try (ui; file) {
            build(new OpenAiClient(config, trace), skills, ui, fileLevel, cancellation,
                    maxIterations, trace, workspace, commandTimeout).run();
        } catch (Exception e) {
            System.err.println(e.getMessage());
            System.exit(1);
        }
    }

    /**
     * Builds the loop and the commands around one {@link SelectedPolicy} and one
     * {@link ToolRegistry}, both rooted at the same {@code workspace}. A registry built anywhere
     * else could resolve a call the policy allowed to a different place. A test gives this its own
     * collaborators to prove the loop and the command share the policy.
     */
    static Repl build(LlmClient client, SkillRegistry skills, Ui ui, Level fileLevel,
                       Cancellation cancellation, int maxIterations, Trace trace,
                       Workspace workspace, Duration commandTimeout) {
        ToolRegistry registry = ToolRegistry.of(
                new ListFiles(workspace, cancellation),
                new ReadFile(workspace, cancellation),
                new EditFile(workspace, cancellation),
                new DeleteFile(workspace),
                new RunCommand(workspace, cancellation, commandTimeout));
        SystemMessage system = new SystemMessage(SYSTEM_PROMPT);
        Conversation conversation = new Conversation(system);
        SelectedPolicy policies = new SelectedPolicy(defaultPolicy(ui.canAsk()));

        Agent agent = new Agent(client, registry, policies, new Approvals(ui), conversation,
                trace, cancellation, maxIterations);

        return new Repl(agent, ui, cancellation, new Commands(conversation, system, registry, skills, ui, fileLevel, policies));
    }

    /**
     * Eight is enough for read-read-edit and too few for anything that plans.
     *
     * <p>A malformed value is an error rather than a silent fall back to the default: this is set
     * once in a shell script or a unit file, and a typo that quietly does nothing would go
     * unnoticed indefinitely.
     */
    static int maxIterations() {
        String configured = System.getProperty("konacode.maxIterations");
        if (configured == null) {
            return DEFAULT_MAX_ITERATIONS;
        }
        try {
            return Integer.parseInt(configured.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("konacode.maxIterations must be a whole number, but was: " + configured);
        }
    }

    /** How many trace files konacode keeps. A wrong value is an error, as every property is. */
    static int maxTraceFiles() {
        String configured = System.getProperty("konacode.trace.maxFiles");
        if (configured == null) {
            return DEFAULT_MAX_TRACE_FILES;
        }
        int value;
        try {
            value = Integer.parseInt(configured.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("konacode.trace.maxFiles must be a whole number, but was: " + configured);
        }
        if (value < 1) {
            throw new IllegalArgumentException("konacode.trace.maxFiles must be 1 or more, but was: " + configured);
        }
        return value;
    }

    /**
     * How long konacode waits for one command.
     *
     * <p>The user owns this value, and the model does not: a model that could raise it would
     * escape the limit.
     */
    static Duration commandTimeout() {
        String configured = System.getProperty("konacode.command.timeoutSeconds");
        if (configured == null) {
            return DEFAULT_COMMAND_TIMEOUT;
        }
        long seconds;
        try {
            seconds = Long.parseLong(configured.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("konacode.command.timeoutSeconds must be a whole number of seconds, but was: " + configured);
        }
        if (seconds < 1) {
            throw new IllegalArgumentException("konacode.command.timeoutSeconds must be at least 1, but was: " + configured);
        }
        return Duration.ofSeconds(seconds);
    }

    static Path skillsRoot() {
        return Path.of(System.getProperty("user.home"), ".konacode", "skills");
    }

    /** The launch directory, and the skills folder, which a tool may read and never write. */
    static Workspace workspace() {
        return new Workspace(Path.of(System.getProperty("user.dir")), List.of(skillsRoot()));
    }

    /**
     * An interface that cannot ask a question keeps today's behaviour, because a question there
     * would refuse every call outside the project. An interface that can ask uses the new policy.
     */
    static ToolPolicy defaultPolicy(boolean canAsk) {
        return canAsk ? new EffectPolicy() : new AllowAllPolicy();
    }

    static Ui selectUi(Cancellation cancellation) throws IOException {
        String choice = System.getProperty("konacode.ui", "auto");
        return switch (choice) {
            case "plain" -> PlainUi.open();
            case "rich" -> RichUi.open(cancellation);
            case "auto" -> System.console() == null
                    ? PlainUi.open()
                    : openRichOrFallBack(cancellation);
            default -> throw new IllegalArgumentException(
                    "konacode.ui must be auto, plain or rich, but was: " + choice);
        };
    }

    private static Ui openRichOrFallBack(Cancellation cancellation) {
        try {
            return RichUi.open(cancellation);
        } catch (IOException e) {
            return PlainUi.open();
        }
    }
}
