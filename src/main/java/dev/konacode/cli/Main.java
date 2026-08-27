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
import dev.konacode.tools.ToolRegistry;
import dev.konacode.tools.Workspace;
import dev.konacode.trace.JsonlTrace;
import dev.konacode.trace.Level;
import dev.konacode.trace.Trace;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public final class Main {

    private static final String SYSTEM_PROMPT = "You are konacode, a concise CLI assistant.";

    private Main() {
    }

    public static void main(String[] args) {
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
        // JsonlTrace.open falls back to Trace.NONE when it cannot open the file, so the
        // configured level is not always the level the file got.
        Level fileLevel = file == Trace.NONE ? Level.OFF : traceLevel;

        Workspace workspace = workspace();
        SkillRegistry skills = new SkillRegistry(new Workspace(skillsRoot()));

        try (ui; file) {
            build(new OpenAiClient(config, trace), skills, ui, fileLevel, cancellation,
                    maxIterations, trace, workspace).run();
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
                       Workspace workspace) {
        ToolRegistry registry = ToolRegistry.of(
                new ListFiles(workspace, cancellation),
                new ReadFile(workspace, cancellation),
                new EditFile(workspace, cancellation),
                new DeleteFile(workspace));
        SystemMessage system = new SystemMessage(SYSTEM_PROMPT);
        Conversation conversation = new Conversation(system);
        SelectedPolicy policies = new SelectedPolicy(defaultPolicy(ui.canAsk(), workspace));

        Agent agent = new Agent(client, registry, policies, new Approvals(ui), conversation,
                trace, cancellation, maxIterations);

        return new Repl(agent, ui, new Commands(conversation, system, registry, skills, ui,
                fileLevel, policies, workspace));
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
    static ToolPolicy defaultPolicy(boolean canAsk, Workspace workspace) {
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
