package dev.konacode.agent;

import dev.konacode.llm.Message;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The history of one session. The agent loop keeps no other state.
 *
 * <p>This is a class and not an interface. The pair {@link #messages()} and
 * {@link #restart(List)} covers every change to the history, so a caller reads all of it,
 * transforms it, and writes all of it back. Persistence, compaction and trimming all work that
 * way, and none of them needs a second implementation.
 */
public final class Conversation {

    private final List<Message> messages = new ArrayList<>();

    public Conversation(Message... initial) {
        Collections.addAll(messages, initial);
    }

    public void add(Message message) {
        messages.add(Objects.requireNonNull(message, "message"));
    }

    public List<Message> messages() {
        return List.copyOf(messages);
    }

    /**
     * Removes every message, then adds the given ones.
     *
     * <p>The caller decides what to keep, so this class holds no rule about the system message.
     * The command {@code /clear} keeps the system message. The command {@code /compact} keeps the
     * system message and a summary.
     */
    public void restart(List<Message> messages) {
        this.messages.clear();
        this.messages.addAll(List.copyOf(messages));
    }
}
