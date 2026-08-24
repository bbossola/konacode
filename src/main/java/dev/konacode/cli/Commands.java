package dev.konacode.cli;

import dev.konacode.agent.Conversation;
import dev.konacode.llm.Message;
import dev.konacode.llm.Message.AssistantMessage;
import dev.konacode.llm.Message.UserMessage;
import dev.konacode.skills.Skill;
import dev.konacode.skills.SkillException;
import dev.konacode.skills.SkillRegistry;
import dev.konacode.tools.Tool;
import dev.konacode.tools.ToolRegistry;
import dev.konacode.trace.Level;

import java.util.List;
import java.util.Optional;
import java.util.Set;

final class Commands {

    /** The commands that read a word after the name. Every other one refuses it. */
    private static final Set<String> TAKES_AN_ARGUMENT = Set.of("/skill", "/trace");

    private final Conversation conversation;
    private final Message systemMessage;
    private final ToolRegistry registry;
    private final SkillRegistry skills;
    private final Ui ui;
    private final Level fileLevel;

    Commands(Conversation conversation, Message systemMessage, ToolRegistry registry,
             SkillRegistry skills, Ui ui, Level fileLevel) {
        this.conversation = conversation;
        this.systemMessage = systemMessage;
        this.registry = registry;
        this.skills = skills;
        this.ui = ui;
        this.fileLevel = fileLevel;
    }

    boolean handles(String line) {
        return line.startsWith("/");
    }

    /** Returns false when the session must end. */
    boolean run(String line) {
        String[] parts = line.trim().split("\\s+", 2);
        String command = parts[0];
        String argument = parts.length > 1 ? parts[1].trim() : "";

        // A word after a command that takes none stays an unknown command, as it was before.
        if (!argument.isEmpty() && !TAKES_AN_ARGUMENT.contains(command)) {
            unknown(line);
            return true;
        }

        switch (command) {
            case "/help" -> help();
            case "/tools" -> tools();
            case "/skill" -> skill(argument);
            case "/clear" -> clear();
            case "/trace" -> trace(argument);
            case "/exit" -> {
                return false;
            }
            default -> unknown(line);
        }
        return true;
    }

    private void unknown(String line) {
        ui.showError("Unknown command: " + line + ". Type /help for the list.");
    }

    private void help() {
        ui.showAnswer("""
                ```
                esc      stop the turn
                /help    show this list
                /tools   show the tools the model can call
                /skill   show the skills, or load one by name
                /trace   show or set how much the screen reports
                /clear   forget the conversation and start again
                /exit    end the session
                ```""");
    }

    private void trace(String argument) {
        if (argument.isEmpty()) {
            ui.showAnswer("The screen shows `" + ui.liveTrace().label()
                    + "`. The file records `" + fileLevel.label() + "`.");
            return;
        }
        Optional<Level> level = Level.parse(argument);
        if (level.isEmpty()) {
            ui.showError("Unknown level: " + argument + ". Use off, basic or full.");
            return;
        }
        ui.liveTrace(level.get());
        ui.showAnswer("The screen now shows `" + level.get().label() + "`.");
    }

    private void tools() {
        StringBuilder text = new StringBuilder();
        for (Tool tool : registry.all()) {
            text.append("- `").append(tool.name()).append("` — ")
                .append(tool.description().replace("\n", " ")).append("\n");
        }
        ui.showAnswer(text.toString().stripTrailing());
    }

    private void skill(String name) {
        try {
            if (name.isEmpty()) {
                list();
                return;
            }
            Skill skill = skills.lookup(name).orElse(null);
            if (skill == null) {
                ui.showError("Unknown skill: " + name + ". Type /skill for the list.");
                return;
            }
            conversation.add(new UserMessage(load(skill, skills.body(skill))));
            conversation.add(new AssistantMessage(loaded(skill), List.of()));
            ui.showAnswer(loaded(skill));
        } catch (SkillException e) {
            ui.showError(e.getMessage());
        }
    }

    private static String loaded(Skill skill) {
        return "The skill `" + skill.name() + "` is loaded.";
    }

    /**
     * The text the model reads. This is prompt text and not a comment. It names the folder, because
     * a reference file of a skill sits inside it, and it names the tool that reads one.
     */
    private static String load(Skill skill, String body) {
        return loaded(skill) + " Its folder is `" + skill.folder()
                + "`. Use read_file to read a file inside that folder.\n\n" + body;
    }

    private void list() {
        List<Skill> all = skills.all();
        if (all.isEmpty()) {
            ui.showAnswer("No skill is available. Put one in `" + skills.root() + "`.");
            return;
        }
        StringBuilder text = new StringBuilder();
        for (Skill skill : all) {
            text.append("- `").append(skill.name()).append("` — ")
                .append(skill.description()).append("\n");
        }
        ui.showAnswer(text.toString().stripTrailing());
    }

    private void clear() {
        conversation.restart(List.of(systemMessage));
        ui.showAnswer("The conversation is empty.");
    }
}
