package dev.konacode.cli;

import dev.konacode.agent.Agent;
import dev.konacode.agent.Cancellation;
import dev.konacode.agent.Conversation;
import dev.konacode.llm.Message.SystemMessage;
import dev.konacode.llm.openai.OpenAiClient;
import dev.konacode.llm.openai.OpenAiConfig;
import dev.konacode.policy.AllowAllPolicy;
import dev.konacode.tools.EditFile;
import dev.konacode.tools.ListFiles;
import dev.konacode.tools.ReadFile;
import dev.konacode.tools.StopCheck;
import dev.konacode.tools.ToolRegistry;
import dev.konacode.tools.Workspace;

import java.io.IOException;

public final class Main {

    private static final String SYSTEM_PROMPT = "You are konacode, a concise CLI assistant.";

    private Main() {
    }

    public static void main(String[] args) {
        OpenAiConfig config;
        int maxIterations;
        Ui ui;
        try {
            config = OpenAiConfig.fromEnvironment(System.getenv());
            maxIterations = Agent.configuredMaxIterations();
            ui = selectUi();
        } catch (IllegalArgumentException | IOException e) {
            System.err.println(e.getMessage());
            System.exit(1);
            return;
        }

        Workspace workspace = Workspace.ofCurrentDirectory();
        ToolRegistry registry = ToolRegistry.of(
                new ListFiles(workspace, StopCheck.NEVER), new ReadFile(workspace),
                new EditFile(workspace));
        SystemMessage system = new SystemMessage(SYSTEM_PROMPT);
        Conversation conversation = new Conversation(system);

        Cancellation cancellation = new Cancellation();

        Agent agent = new Agent(new OpenAiClient(config), registry, new AllowAllPolicy(),
                conversation, ui, cancellation, maxIterations);

        try (ui) {
            new Repl(agent, ui, new Commands(conversation, system, registry, ui)).run();
        } catch (Exception e) {
            System.err.println(e.getMessage());
            System.exit(1);
        }
    }

    static Ui selectUi() throws IOException {
        String choice = System.getProperty("konacode.ui", "auto");
        return switch (choice) {
            case "plain" -> PlainUi.open();
            case "rich" -> RichUi.open();
            case "auto" -> System.console() == null ? PlainUi.open() : openRichOrFallBack();
            default -> throw new IllegalArgumentException(
                    "konacode.ui must be auto, plain or rich, but was: " + choice);
        };
    }

    private static Ui openRichOrFallBack() {
        try {
            return RichUi.open();
        } catch (IOException e) {
            return PlainUi.open();
        }
    }
}
