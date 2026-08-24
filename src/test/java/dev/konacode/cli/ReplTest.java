package dev.konacode.cli;

import dev.konacode.agent.Agent;
import dev.konacode.agent.Cancellation;
import dev.konacode.agent.Conversation;
import dev.konacode.llm.LlmClient;
import dev.konacode.llm.Message;
import dev.konacode.llm.Message.AssistantMessage;
import dev.konacode.llm.Message.SystemMessage;
import dev.konacode.llm.ToolSpec;
import dev.konacode.policy.AllowAllPolicy;
import dev.konacode.tools.ToolRegistry;
import dev.konacode.tools.Workspace;
import dev.konacode.tools.ListFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplTest {

    @TempDir
    Path root;

    private static final SystemMessage SYSTEM = new SystemMessage("You are konacode.");

    private Repl repl(RecordingUi ui) {
        LlmClient client = (history, tools) -> new AssistantMessage("the answer", List.of());
        Conversation conversation = new Conversation(SYSTEM);
        ToolRegistry registry = ToolRegistry.of(new ListFiles(new Workspace(root)));
        Agent agent = new Agent(client, registry, new AllowAllPolicy(), conversation, ui,
                new Cancellation(), 8);
        return new Repl(agent, ui, new Commands(conversation, SYSTEM, registry, ui));
    }

    @Test
    void showsTheBannerBeforeItReadsAnything() {
        RecordingUi ui = new RecordingUi();

        repl(ui).run();

        assertEquals("welcome", ui.events.get(0));
    }

    @Test
    void sendsTheLineToTheAgentAndShowsTheAnswer() {
        RecordingUi ui = new RecordingUi("hello");

        repl(ui).run();

        assertEquals(List.of("the answer"), ui.answers);
    }

    @Test
    void tellsTheInterfaceThatWorkStartedBeforeItAsksTheAgent() {
        RecordingUi ui = new RecordingUi("hello");

        repl(ui).run();

        assertTrue(ui.events.indexOf("thinking") < ui.events.indexOf("answer"), ui.events.toString());
    }

    @Test
    void skipsAnEmptyLine() {
        RecordingUi ui = new RecordingUi("", "   ", "hello");

        repl(ui).run();

        assertEquals(1, ui.answers.size());
    }

    @Test
    void stopsAtTheEndOfInput() {
        RecordingUi ui = new RecordingUi("one", "two");

        repl(ui).run();

        assertEquals(2, ui.answers.size());
    }

    @Test
    void stopsWhenTheUserTypesExit() {
        RecordingUi ui = new RecordingUi("/exit", "never reached");

        repl(ui).run();

        assertEquals(List.of(), ui.answers);
    }

    @Test
    void sendsACommandToTheCommandsAndNotToTheAgent() {
        RecordingUi ui = new RecordingUi("/help");

        repl(ui).run();

        assertTrue(ui.events.stream().noneMatch("thinking"::equals), ui.events.toString());
    }
}
