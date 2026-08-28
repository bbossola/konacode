package dev.konacode.cli;

import dev.konacode.agent.Agent;
import dev.konacode.agent.Cancellation;

final class Repl {

    private final Agent agent;
    private final Ui ui;
    private final Cancellation cancellation;
    private final Commands commands;

    Repl(Agent agent, Ui ui, Cancellation cancellation, Commands commands) {
        this.agent = agent;
        this.ui = ui;
        this.cancellation = cancellation;
        this.commands = commands;
    }

    void run() {
        ui.welcome();
        for (var line = ui.readLine(); line.isPresent(); line = ui.readLine()) {
            String text = line.get().trim();
            if (text.isEmpty()) {
                continue;
            }
            if (commands.handles(text)) {
                if (!commands.run(text)) {
                    return;
                }
                continue;
            }
            ui.thinking();
            // The user can press a key at the prompt, and that key must not stop the next turn.
            cancellation.clear();
            ui.showAnswer(agent.respond(text));
        }
    }
}
