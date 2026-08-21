package dev.konacode.cli;

import dev.konacode.agent.Conversation;
import dev.konacode.llm.Message;
import dev.konacode.tools.Tool;
import dev.konacode.tools.ToolRegistry;

import java.util.List;

final class Commands {

    private final Conversation conversation;
    private final Message systemMessage;
    private final ToolRegistry registry;
    private final Ui ui;

    Commands(Conversation conversation, Message systemMessage, ToolRegistry registry, Ui ui) {
        this.conversation = conversation;
        this.systemMessage = systemMessage;
        this.registry = registry;
        this.ui = ui;
    }

    boolean handles(String line) {
        return line.startsWith("/");
    }

    /** Returns false when the session must end. */
    boolean run(String line) {
        switch (line) {
            case "/help" -> help();
            case "/tools" -> tools();
            case "/clear" -> clear();
            case "/exit" -> {
                return false;
            }
            default -> ui.showError("Unknown command: " + line + ". Type /help for the list.");
        }
        return true;
    }

    private void help() {
        ui.showAnswer("""
                - `/help` — show this list
                - `/tools` — show the tools the model can call
                - `/clear` — forget the conversation and start again
                - `/exit` — end the session""");
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
