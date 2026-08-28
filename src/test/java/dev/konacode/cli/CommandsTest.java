package dev.konacode.cli;

import dev.konacode.agent.Conversation;
import dev.konacode.llm.Message;
import dev.konacode.llm.Message.AssistantMessage;
import dev.konacode.llm.Message.SystemMessage;
import dev.konacode.llm.Message.UserMessage;
import dev.konacode.policy.AllowAllPolicy;
import dev.konacode.policy.EffectPolicy;
import dev.konacode.policy.SelectedPolicy;
import dev.konacode.skills.SkillRegistry;
import dev.konacode.tools.ToolRegistry;
import dev.konacode.tools.Workspace;
import dev.konacode.tools.ListFiles;
import dev.konacode.tools.ReadFile;
import dev.konacode.tools.StopCheck;
import dev.konacode.trace.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandsTest {

    @TempDir
    Path root;

    private static final SystemMessage SYSTEM = new SystemMessage("You are konacode.");

    private SelectedPolicy selected;

    private Commands commands(RecordingUi ui, Conversation conversation) {
        Workspace workspace = new Workspace(root);
        Workspace skillRoot = new Workspace(root.resolve("skills"));
        selected = new SelectedPolicy(new EffectPolicy());
        return new Commands(conversation, SYSTEM,
                ToolRegistry.of(new ListFiles(workspace, StopCheck.NEVER),
                        new ReadFile(workspace, StopCheck.NEVER)),
                new SkillRegistry(skillRoot), ui, Level.BASIC, selected);
    }

    private void writeSkill(String name, String description) throws IOException {
        Path directory = Files.createDirectories(root.resolve("skills").resolve(name));
        Files.writeString(directory.resolve("SKILL.md"),
                "---\nname: " + name + "\ndescription: " + description + "\n---\nThe body of "
                        + name + ".\n");
    }

    @Test
    void handlesOnlyALineThatStartsWithASlash() {
        Commands commands = commands(new RecordingUi(), new Conversation(SYSTEM));

        assertTrue(commands.handles("/help"));
        assertFalse(commands.handles("help"));
        assertFalse(commands.handles("what files are here?"));
    }

    @Test
    void helpNamesTheStopKey() {
        RecordingUi ui = new RecordingUi();
        Commands commands = commands(ui, new Conversation(SYSTEM));

        commands.run("/help");

        assertTrue(ui.answers.get(0).contains("esc"), ui.answers.get(0));
    }

    @Test
    void helpNamesEveryCommand() {
        RecordingUi ui = new RecordingUi();

        commands(ui, new Conversation(SYSTEM)).run("/help");

        String shown = String.join("\n", ui.answers);
        assertTrue(shown.contains("/help"), shown);
        assertTrue(shown.contains("/tools"), shown);
        assertTrue(shown.contains("/skill"), shown);
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

    @Test
    void skillWithNoNameListsEverySkill() throws IOException {
        writeSkill("commit-message", "Use for a commit.");
        writeSkill("review", "Use for a review.");
        RecordingUi ui = new RecordingUi();

        commands(ui, new Conversation(SYSTEM)).run("/skill");

        String shown = String.join("\n", ui.answers);
        assertTrue(shown.contains("commit-message"), shown);
        assertTrue(shown.contains("Use for a review."), shown);
    }

    @Test
    void skillWithNoNameSaysWhenThereAreNoSkills() {
        RecordingUi ui = new RecordingUi();

        commands(ui, new Conversation(SYSTEM)).run("/skill");

        assertTrue(String.join("\n", ui.answers).contains("No skill"), ui.answers.toString());
    }

    @Test
    void theEmptyListNamesTheRealSkillsFolder() {
        RecordingUi ui = new RecordingUi();

        commands(ui, new Conversation(SYSTEM)).run("/skill");

        assertTrue(ui.answers.get(0).contains(root.resolve("skills").toString()),
                ui.answers.toString());
    }

    @Test
    void skillWithNoNameSaysWhenTheFolderIsEmpty() throws IOException {
        Files.createDirectories(root.resolve("skills"));
        RecordingUi ui = new RecordingUi();

        commands(ui, new Conversation(SYSTEM)).run("/skill");

        assertTrue(String.join("\n", ui.answers).contains("No skill"), ui.answers.toString());
    }

    @Test
    void aCommandThatTakesNoArgumentStaysUnknownWithOne() {
        RecordingUi ui = new RecordingUi();

        commands(ui, new Conversation(SYSTEM)).run("/help me");

        assertTrue(ui.errors.get(0).contains("/help me"), ui.errors.toString());
        assertTrue(ui.answers.isEmpty(), ui.answers.toString());
    }

    @Test
    void skillAppendsTheBodyAndAnAcknowledgement() throws IOException {
        writeSkill("commit-message", "Use for a commit.");
        Conversation conversation = new Conversation(SYSTEM);
        RecordingUi ui = new RecordingUi();

        commands(ui, conversation).run("/skill commit-message");

        List<Message> messages = conversation.messages();
        assertEquals(3, messages.size());
        UserMessage loaded = (UserMessage) messages.get(1);
        assertTrue(loaded.text().contains("The body of commit-message."), loaded.text());
        assertEquals("The skill `commit-message` is loaded.",
                ((AssistantMessage) messages.get(2)).text());
        assertEquals(List.of("The skill `commit-message` is loaded."), ui.answers);
    }

    @Test
    void skillFramesTheBodyForTheModel() throws IOException {
        writeSkill("commit-message", "Use for a commit.");
        Conversation conversation = new Conversation(SYSTEM);

        commands(new RecordingUi(), conversation).run("/skill commit-message");

        String folder = root.resolve("skills").resolve("commit-message").toRealPath().toString();
        assertEquals("The skill `commit-message` is loaded. Its folder is `" + folder
                        + "`. Use read_file to read a file inside that folder.\n\n"
                        + "The body of commit-message.",
                ((UserMessage) conversation.messages().get(1)).text());
    }

    @Test
    void twoSkillsBothStayInTheHistory() throws IOException {
        writeSkill("one", "The first.");
        writeSkill("two", "The second.");
        Conversation conversation = new Conversation(SYSTEM);
        Commands commands = commands(new RecordingUi(), conversation);

        commands.run("/skill one");
        commands.run("/skill two");

        assertEquals(5, conversation.messages().size());
    }

    @Test
    void clearRemovesEverySkill() throws IOException {
        writeSkill("one", "The first.");
        Conversation conversation = new Conversation(SYSTEM);
        Commands commands = commands(new RecordingUi(), conversation);

        commands.run("/skill one");
        assertEquals(3, conversation.messages().size());

        commands.run("/clear");

        assertEquals(List.of(SYSTEM), conversation.messages());
    }

    @Test
    void reportsAnUnknownSkillAndChangesNoMessage() {
        Conversation conversation = new Conversation(SYSTEM);
        RecordingUi ui = new RecordingUi();

        commands(ui, conversation).run("/skill absent");

        assertTrue(ui.errors.get(0).contains("absent"), ui.errors.toString());
        assertEquals(1, conversation.messages().size());
    }

    @Test
    void aTabOrTwoSpacesSeparatesTheNameFromTheCommand() throws IOException {
        writeSkill("commit-message", "Use for a commit.");
        Conversation conversation = new Conversation(SYSTEM);
        Commands commands = commands(new RecordingUi(), conversation);

        commands.run("/skill\tcommit-message");
        commands.run("/skill  commit-message");

        assertEquals(5, conversation.messages().size());
    }

    @Test
    void reportsANameThatIsNotAFolderNameAndChangesNoMessage() {
        Conversation conversation = new Conversation(SYSTEM);
        RecordingUi ui = new RecordingUi();

        commands(ui, conversation).run("/skill ..");

        assertTrue(ui.errors.get(0).contains("one folder name"), ui.errors.toString());
        assertEquals(1, conversation.messages().size());
    }

    @Test
    void reportsABrokenSkillAndChangesNoMessage() throws IOException {
        Path directory = Files.createDirectories(root.resolve("skills").resolve("broken"));
        Files.writeString(directory.resolve("SKILL.md"), "---\nname: broken\n---\nbody\n");
        Conversation conversation = new Conversation(SYSTEM);
        RecordingUi ui = new RecordingUi();

        commands(ui, conversation).run("/skill broken");

        assertTrue(ui.errors.get(0).contains("description"), ui.errors.toString());
        assertEquals(1, conversation.messages().size());
    }

    @Test
    void oneBlankLineSitsAboveTheBody() throws IOException {
        Path directory = Files.createDirectories(root.resolve("skills").resolve("spaced"));
        Files.writeString(directory.resolve("SKILL.md"),
                "---\nname: spaced\ndescription: Use it.\n---\n\n\n  The body.\n\n\n");
        Conversation conversation = new Conversation(SYSTEM);

        commands(new RecordingUi(), conversation).run("/skill spaced");

        String text = ((UserMessage) conversation.messages().get(1)).text();
        assertTrue(text.endsWith("that folder.\n\n  The body."), text);
    }

    @Test
    void traceWithNoLevelShowsBothLevels() {
        RecordingUi ui = new RecordingUi();

        commands(ui, new Conversation(SYSTEM)).run("/trace");

        String shown = String.join("\n", ui.answers);
        assertTrue(shown.contains("off"), shown);
        assertTrue(shown.contains("basic"), shown);
    }

    @Test
    void traceSetsTheLevelOfTheScreen() {
        RecordingUi ui = new RecordingUi();

        commands(ui, new Conversation(SYSTEM)).run("/trace full");

        assertEquals(Level.FULL, ui.liveTrace());
    }

    @Test
    void traceRefusesAnUnknownLevel() {
        RecordingUi ui = new RecordingUi();

        commands(ui, new Conversation(SYSTEM)).run("/trace loud");

        assertEquals(1, ui.errors.size());
        assertTrue(ui.errors.get(0).contains("loud"), ui.errors.get(0));
        assertEquals(Level.OFF, ui.liveTrace());
    }

    @Test
    void helpNamesTrace() {
        RecordingUi ui = new RecordingUi();

        commands(ui, new Conversation(SYSTEM)).run("/help");

        assertTrue(String.join("\n", ui.answers).contains("/trace"), ui.answers.toString());
    }

    @Test
    void policyWithNoNameShowsBothChoicesAndTheOneInUse() {
        RecordingUi ui = new RecordingUi();
        Commands commands = commands(ui, new Conversation(SYSTEM));
        commands.run("/policy allow-all");

        commands.run("/policy");

        String shown = ui.answers.get(ui.answers.size() - 1);
        assertTrue(shown.contains("uses `allow-all`"), shown);
        assertTrue(shown.contains("- `allow-all`"), shown);
        assertTrue(shown.contains("- `effect`"), shown);
    }

    @Test
    void policyWithNoNameNamesTheDefaultPolicy() {
        RecordingUi ui = new RecordingUi();

        commands(ui, new Conversation(SYSTEM)).run("/policy");

        assertTrue(String.join("\n", ui.answers).contains("uses `effect`"), ui.answers.toString());
    }

    @Test
    void policyWithNoNameNotesTheInterfaceCannotAskWhenItCannot() {
        RecordingUi ui = new RecordingUi();
        ui.canAsk = false;

        commands(ui, new Conversation(SYSTEM)).run("/policy");

        String shown = ui.answers.get(ui.answers.size() - 1);
        assertTrue(shown.contains("cannot ask a question"), shown);
        assertTrue(shown.contains("`effect` refuses every call outside this project"), shown);
    }

    @Test
    void policyWithNoNameAddsNoNoteWhenTheInterfaceCanAsk() {
        RecordingUi ui = new RecordingUi();

        commands(ui, new Conversation(SYSTEM)).run("/policy");

        String shown = ui.answers.get(ui.answers.size() - 1);
        assertFalse(shown.contains("cannot ask"), shown);
    }

    @Test
    void policyChoosesAllowAll() {
        commands(new RecordingUi(), new Conversation(SYSTEM)).run("/policy allow-all");

        assertInstanceOf(AllowAllPolicy.class, selected.selected());
    }

    @Test
    void policyChoosesEffect() {
        Commands commands = commands(new RecordingUi(), new Conversation(SYSTEM));
        commands.run("/policy allow-all");

        commands.run("/policy effect");

        assertInstanceOf(EffectPolicy.class, selected.selected());
    }

    @Test
    void policyReadsTheNameInAnyCase() {
        Commands commands = commands(new RecordingUi(), new Conversation(SYSTEM));
        commands.run("/policy allow-all");

        commands.run("/policy EFFECT");

        assertInstanceOf(EffectPolicy.class, selected.selected());
    }

    @Test
    void policySaysWhatItChose() {
        RecordingUi ui = new RecordingUi();

        commands(ui, new Conversation(SYSTEM)).run("/policy allow-all");

        assertTrue(String.join("\n", ui.answers).contains("allow-all"), ui.answers.toString());
    }

    @Test
    void policyRefusesAnUnknownNameAndChangesNothing() {
        RecordingUi ui = new RecordingUi();
        Commands commands = commands(ui, new Conversation(SYSTEM));

        commands.run("/policy loose");

        assertEquals("Unknown policy: loose. Use allow-all or effect.", ui.errors.get(0));
        assertInstanceOf(EffectPolicy.class, selected.selected());
        assertTrue(ui.answers.isEmpty(), ui.answers.toString());
    }

    @Test
    void policyEffectWarnsWhenTheInterfaceCannotAsk() {
        RecordingUi ui = new RecordingUi();
        ui.canAsk = false;

        commands(ui, new Conversation(SYSTEM)).run("/policy effect");

        String shown = ui.answers.get(ui.answers.size() - 1);
        assertTrue(shown.contains("cannot ask a question"), shown);
        assertTrue(shown.contains("refuses every call outside this project"), shown);
    }

    @Test
    void policyAllowAllDoesNotWarnWhenTheInterfaceCannotAsk() {
        RecordingUi ui = new RecordingUi();
        ui.canAsk = false;

        commands(ui, new Conversation(SYSTEM)).run("/policy allow-all");

        String shown = ui.answers.get(ui.answers.size() - 1);
        assertEquals("konacode now uses `allow-all`.", shown);
    }

    @Test
    void helpNamesPolicy() {
        RecordingUi ui = new RecordingUi();

        commands(ui, new Conversation(SYSTEM)).run("/help");

        assertTrue(String.join("\n", ui.answers).contains("/policy"), ui.answers.toString());
    }
}
