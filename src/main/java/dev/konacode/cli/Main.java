package dev.konacode.cli;

import dev.konacode.agent.Agent;
import dev.konacode.agent.Approvals;
import dev.konacode.agent.Cancellation;
import dev.konacode.agent.Conversation;
import dev.konacode.llm.Message.SystemMessage;
import dev.konacode.llm.openai.OpenAiClient;
import dev.konacode.llm.openai.OpenAiConfig;
import dev.konacode.policy.AllowAllPolicy;
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

        Workspace workspace = Workspace.ofCurrentDirectory();
        ToolRegistry registry = ToolRegistry.of(
                new ListFiles(workspace, cancellation),
                new ReadFile(workspace, cancellation),
                new EditFile(workspace, cancellation),
                new DeleteFile(workspace));
        SkillRegistry skills = new SkillRegistry(new Workspace(skillsRoot()));
        SystemMessage system = new SystemMessage(SYSTEM_PROMPT);
        Conversation conversation = new Conversation(system);

        Agent agent = new Agent(new OpenAiClient(config, trace), registry, new AllowAllPolicy(),
                new Approvals(ui), conversation, trace, cancellation, maxIterations);

        try (ui; file) {
            new Repl(agent, ui,
                    new Commands(conversation, system, registry, skills, ui, fileLevel)).run();
        } catch (Exception e) {
            System.err.println(e.getMessage());
            System.exit(1);
        }
    }

    static Path skillsRoot() {
        return Path.of(System.getProperty("user.home"), ".konacode", "skills");
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
