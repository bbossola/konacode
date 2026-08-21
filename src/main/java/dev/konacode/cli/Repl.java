package dev.konacode.cli;

import dev.konacode.agent.Agent;

final class Repl {

    private final Agent agent;
    private final Ui ui;
    private final Commands commands;

    Repl(Agent agent, Ui ui, Commands commands) {
        this.agent = agent;
        this.ui = ui;
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
            ui.showAnswer(agent.respond(text));
        }
    }
}
