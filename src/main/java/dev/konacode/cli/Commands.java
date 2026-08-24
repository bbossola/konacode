package dev.konacode.cli;

import dev.konacode.agent.Conversation;
import dev.konacode.llm.Message;
import dev.konacode.tools.Tool;
import dev.konacode.tools.ToolRegistry;
import dev.konacode.trace.Level;

import java.util.List;
import java.util.Optional;

final class Commands {

    private final Conversation conversation;
    private final Message systemMessage;
    private final ToolRegistry registry;
    private final Ui ui;
    private final Level fileLevel;

    Commands(Conversation conversation, Message systemMessage, ToolRegistry registry, Ui ui,
             Level fileLevel) {
        this.conversation = conversation;
        this.systemMessage = systemMessage;
        this.registry = registry;
        this.ui = ui;
        this.fileLevel = fileLevel;
    }

    boolean handles(String line) {
        return line.startsWith("/");
    }

    /** Returns false when the session must end. */
    boolean run(String line) {
        String[] parts = line.trim().split("\\s+", 2);
        String name = parts[0];
        String argument = parts.length > 1 ? parts[1].trim() : "";
        switch (name) {
            case "/help" -> help();
            case "/tools" -> tools();
            case "/clear" -> clear();
            case "/trace" -> trace(argument);
            case "/exit" -> {
                return false;
            }
            default -> ui.showError("Unknown command: " + line + ". Type /help for the list.");
        }
        return true;
    }

    private void help() {
        ui.showAnswer("""
                ```
                esc      stop the turn
                /help    show this list
                /tools   show the tools the model can call
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

    private void clear() {
        conversation.restart(List.of(systemMessage));
        ui.showAnswer("The conversation is empty.");
    }
}
