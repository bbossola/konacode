package dev.konacode.cli;

import dev.konacode.agent.Conversation;
import dev.konacode.llm.Message;
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
        int space = trimmed.indexOf(' ');
        String command = space < 0 ? trimmed : trimmed.substring(0, space);
        String argument = space < 0 ? "" : trimmed.substring(space + 1).trim();

        // Only /skill takes an argument. A word after any other command keeps today's answer.
        if (!argument.isEmpty() && !command.equals("/skill")) {
            return unknown(line);
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

    private boolean unknown(String line) {
        ui.showError("Unknown command: " + line + ". Type /help for the list.");
        return true;
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
        }
    }

    private void list() {
        List<Skill> all = skills.all();
        if (all.isEmpty()) {
            ui.showAnswer("No skill is available. Put one in `~/.konacode/skills/`.");
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
