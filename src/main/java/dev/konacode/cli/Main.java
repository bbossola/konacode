package dev.konacode.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.konacode.agent.Agent;
import dev.konacode.agent.AgentJudge;
import dev.konacode.agent.Approvals;
import dev.konacode.agent.Cancellation;
import dev.konacode.agent.Conversation;
import dev.konacode.agent.PlanTool;
import dev.konacode.agent.TurnBudget;
import dev.konacode.llm.LlmClient;
import dev.konacode.llm.Message.SystemMessage;
import dev.konacode.llm.openai.ChatCompletionsCodec;
import dev.konacode.llm.openai.OpenAiClient;
import dev.konacode.llm.openai.OpenAiConfig;
import dev.konacode.policy.EffectPolicy;
import dev.konacode.policy.Judge;
import dev.konacode.policy.JudgePolicy;
import dev.konacode.policy.SelectedPolicy;
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
import dev.konacode.trace.NamedTrace;
import dev.konacode.trace.Trace;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public final class Main {

    static final int DEFAULT_MAX_ITERATIONS = 8;
    static final int DEFAULT_PLANNED_MAX_ITERATIONS = 24;
    static final int DEFAULT_MAX_TRACE_FILES = 100;
    static final Duration DEFAULT_COMMAND_TIMEOUT = Duration.ofSeconds(600);

    private Main() {
    }

    public static void main(String[] args) {
        Cancellation cancellation = new Cancellation();
        OpenAiConfig config;
        TurnBudget budget;
        Level traceLevel;
        int maxTraceFiles;
        Duration commandTimeout;
        Ui ui;
        try {
            config = OpenAiConfig.fromEnvironment(System.getenv());
            budget = new TurnBudget(maxIterations(), plannedMaxIterations());
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
            HttpClient http = HttpClient.newBuilder().connectTimeout(config.timeout()).build();
            Clients clients = clients(config, http, trace);
            build(clients.loop(), clients.judge(), skills, ui, fileLevel, cancellation,
                    budget, trace, workspace, commandTimeout).run();
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
    static Repl build(LlmClient client, LlmClient judgeClient, SkillRegistry skills, Ui ui,
                       Level fileLevel, Cancellation cancellation, TurnBudget budget, Trace trace,
                       Workspace workspace, Duration commandTimeout) {
        ToolRegistry registry = ToolRegistry.of(
                new ListFiles(workspace, cancellation),
                new ReadFile(workspace, cancellation),
                new EditFile(workspace, cancellation),
                new DeleteFile(workspace),
                new RunCommand(workspace, cancellation, commandTimeout),
                new PlanTool(budget));
        SystemMessage system = new SystemMessage(systemPrompt(workspace.root()));
        Conversation conversation = new Conversation(system);
        Trace kona = new NamedTrace("kona", trace);
        Judge judge = new AgentJudge(judgeClient, workspace.root(), trace, cancellation);
        JudgePolicy judgePolicy = new JudgePolicy(new EffectPolicy(), judge, kona);
        SelectedPolicy policies = new SelectedPolicy(judgePolicy);

        Agent agent = new Agent(client, registry, policies, new Approvals(ui), conversation, kona,
                cancellation, budget);
        Commands commands = new Commands(conversation, system, registry, skills, ui, fileLevel,
                policies, judgePolicy);

        return new Repl(agent, ui, cancellation, commands);
    }

    /**
     * The standing instruction. It stays short, because every turn pays for it. Each line after the
     * first answers a question the model would otherwise answer with a failed tool call.
     */
    static String systemPrompt(Path directory) {
        return """
                You are konacode, a concise CLI assistant.
                The working directory is %s.
                Plan with the plan tool before work that needs more than two or three tool calls.
                Read a file before you edit the file, because edit_file needs the exact text of old_str.
                An <error> reports a failed tool call. Read the reason and try a different approach.
                """.formatted(directory);
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

    /**
     * The maximum for a turn in which the model records a plan. Work that plans needs more
     * iterations than read-read-edit, and a turn that does not plan must not pay for them.
     */
    static int plannedMaxIterations() {
        String configured = System.getProperty("konacode.maxIterations.planned");
        if (configured == null) {
            return DEFAULT_PLANNED_MAX_ITERATIONS;
        }
        try {
            return Integer.parseInt(configured.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("konacode.maxIterations.planned must be a whole number, but was: " + configured);
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

    /** The loop's client and the judge's client. Each names its own events, so the two stay apart. */
    record Clients(LlmClient loop, LlmClient judge) {
    }

    /**
     * Builds the two clients on one {@link HttpClient} and one {@link ChatCompletionsCodec}. Both
     * are stateless for a request, and one connection pool serves both agents.
     *
     * <p>Each client gets its own name, because a judgement makes its own request and reports its
     * own token counts. Without the name a user cannot tell the cost of a judgement from the cost
     * of the turn.
     */
    static Clients clients(OpenAiConfig config, HttpClient http, Trace trace) {
        ChatCompletionsCodec codec = new ChatCompletionsCodec(new ObjectMapper());
        return new Clients(new OpenAiClient(config, http, codec, new NamedTrace("kona", trace)),
                new OpenAiClient(config.forJudge(), http, codec, new NamedTrace("judge", trace)));
    }

    static Path skillsRoot() {
        return Path.of(System.getProperty("user.home"), ".konacode", "skills");
    }

    /** The launch directory, and the skills folder, which a tool may read and never write. */
    static Workspace workspace() {
        return new Workspace(Path.of(System.getProperty("user.dir")), List.of(skillsRoot()));
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
