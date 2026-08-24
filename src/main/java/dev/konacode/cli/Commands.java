package dev.konacode.cli;

import dev.konacode.agent.Conversation;
import dev.konacode.llm.Message;
import dev.konacode.llm.Message.AssistantMessage;
import dev.konacode.llm.Message.UserMessage;
import dev.konacode.skills.Skill;
import dev.konacode.skills.SkillRegistry;
import dev.konacode.tools.Tool;
import dev.konacode.tools.ToolRegistry;

import java.util.List;

final class Commands {

    private final Conversation conversation;
    private final Message systemMessage;
    private final ToolRegistry registry;
    private final SkillRegistry skills;
    private final Ui ui;

    Commands(Conversation conversation, Message systemMessage, ToolRegistry registry,
             SkillRegistry skills, Ui ui) {
        this.conversation = conversation;
        this.systemMessage = systemMessage;
        this.registry = registry;
        this.skills = skills;
        this.ui = ui;
    }

    boolean handles(String line) {
        return line.startsWith("/");
    }

    /** Returns false when the session must end. */
    boolean run(String line) {
        String trimmed = line.trim();
        String[] parts = trimmed.split("\\s+", 2);
        String command = parts[0];
        String argument = parts.length > 1 ? parts[1] : "";

        // A word after a command that takes none stays an unknown command, as it was before.
        if (!argument.isEmpty() && !command.equals("/skill")) {
            unknown(line);
            return true;
        }

        switch (command) {
            case "/help" -> help();
            case "/tools" -> tools();
            case "/skill" -> skill(argument);
            case "/clear" -> clear();
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
                /clear   forget the conversation and start again
                /exit    end the session
                ```""");
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
    }

    private static String loaded(Skill skill) {
        return "The skill `" + skill.name() + "` is loaded.";
    }

    /**
     * The text the model reads. This is prompt text and not a comment: it gives the folder, because
     * a path in the body of a skill is relative to it, and it names the tool that reads one.
     */
    private static String load(Skill skill, String body) {
        return "The skill `" + skill.name() + "` is now active. Its folder is "
                + skill.folder() + ". A path in the text below is relative to that folder. "
                + "Use read_file to read it.\n\n" + body;
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
