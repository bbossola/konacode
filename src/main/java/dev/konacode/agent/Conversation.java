package dev.konacode.agent;

import dev.konacode.llm.Message;

import java.util.List;

/**
 * The conversation history. An interface so trimming, token budgets, or persistence can be
 * introduced later without the agent loop noticing.
 */
public interface Conversation {

    void add(Message message);

    List<Message> messages();
}
