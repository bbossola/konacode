package dev.konacode.agent;

import dev.konacode.llm.Message;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Appends forever and never trims. The whole history goes to the model on every turn. */
public final class AppendOnlyConversation implements Conversation {

    private final List<Message> messages = new ArrayList<>();

    public AppendOnlyConversation(Message... initial) {
        Collections.addAll(messages, initial);
    }

    @Override
    public void add(Message message) {
        messages.add(Objects.requireNonNull(message, "message"));
    }

    @Override
    public List<Message> messages() {
        return List.copyOf(messages);
    }
}
