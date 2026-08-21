package dev.konacode.cli;

import dev.konacode.agent.Conversation;
import dev.konacode.llm.Message.SystemMessage;
import dev.konacode.llm.Message.UserMessage;
import dev.konacode.tools.ToolRegistry;
import dev.konacode.tools.Workspace;
import dev.konacode.tools.ListFiles;
import dev.konacode.tools.ReadFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandsTest {

    @TempDir
    Path root;

    private static final SystemMessage SYSTEM = new SystemMessage("You are konacode.");

    private Commands commands(RecordingUi ui, Conversation conversation) {
        Workspace workspace = new Workspace(root);
        return new Commands(conversation, SYSTEM,
                ToolRegistry.of(new ListFiles(workspace), new ReadFile(workspace)), ui);
    }

    @Test
    void handlesOnlyALineThatStartsWithASlash() {
        Commands commands = commands(new RecordingUi(), new Conversation(SYSTEM));

        assertTrue(commands.handles("/help"));
        assertFalse(commands.handles("help"));
        assertFalse(commands.handles("what files are here?"));
    }

    @Test
    void helpNamesEveryCommand() {
        RecordingUi ui = new RecordingUi();

        commands(ui, new Conversation(SYSTEM)).run("/help");

        String shown = String.join("\n", ui.answers);
        assertTrue(shown.contains("/help"), shown);
        assertTrue(shown.contains("/tools"), shown);
        assertTrue(shown.contains("/clear"), shown);
        assertTrue(shown.contains("/exit"), shown);
    }

    @Test
    void toolsNamesEveryTool() {
        RecordingUi ui = new RecordingUi();

        commands(ui, new Conversation(SYSTEM)).run("/tools");

        String shown = String.join("\n", ui.answers);
        assertTrue(shown.contains("list_files"), shown);
        assertTrue(shown.contains("read_file"), shown);
    }

    @Test
    void clearKeepsTheSystemMessageAndRemovesTheRest() {
        Conversation conversation = new Conversation(SYSTEM);
        conversation.add(new UserMessage("forget me"));
        RecordingUi ui = new RecordingUi();

        commands(ui, conversation).run("/clear");

        assertEquals(1, conversation.messages().size());
        assertEquals(SYSTEM, conversation.messages().get(0));
    }

    @Test
    void exitEndsTheSession() {
        assertFalse(commands(new RecordingUi(), new Conversation(SYSTEM)).run("/exit"));
    }

    @Test
    void everyOtherCommandLetsTheSessionContinue() {
        Commands commands = commands(new RecordingUi(), new Conversation(SYSTEM));

        assertTrue(commands.run("/help"));
        assertTrue(commands.run("/tools"));
        assertTrue(commands.run("/clear"));
        assertTrue(commands.run("/tolos"));
    }

    @Test
    void refusesAnUnknownCommand() {
        RecordingUi ui = new RecordingUi();

        commands(ui, new Conversation(SYSTEM)).run("/tolos");

        assertEquals(1, ui.errors.size());
        assertTrue(ui.errors.get(0).contains("/tolos"), ui.errors.get(0));
    }
}
