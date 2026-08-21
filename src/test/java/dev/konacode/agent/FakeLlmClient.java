package dev.konacode.agent;

import dev.konacode.llm.LlmClient;
import dev.konacode.llm.LlmException;
import dev.konacode.llm.Message;
import dev.konacode.llm.Message.AssistantMessage;
import dev.konacode.llm.ToolSpec;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Returns scripted replies in order and records what it was asked. Hand-written rather than
 * mocked: the script is more readable than stubbing, and it captures the history for assertions.
 */
final class FakeLlmClient implements LlmClient {

    private final Deque<AssistantMessage> script = new ArrayDeque<>();
    private final List<List<Message>> receivedHistories = new ArrayList<>();
    private RuntimeException failure;

    FakeLlmClient reply(AssistantMessage message) {
        script.add(message);
        return this;
    }

    FakeLlmClient replyText(String text) {
        return reply(new AssistantMessage(text, List.of()));
    }

    /** Makes every call throw, for exercising transport-failure handling. */
    FakeLlmClient failWith(RuntimeException exception) {
        this.failure = exception;
        return this;
    }

    List<List<Message>> receivedHistories() {
        return receivedHistories;
    }

    @Override
    public AssistantMessage chat(List<Message> history, List<ToolSpec> tools) {
        receivedHistories.add(List.copyOf(history));
        if (failure != null) {
            throw failure;
        }
        AssistantMessage next = script.poll();
        if (next == null) {
            throw new LlmException("FakeLlmClient ran out of scripted replies.");
        }
        return next;
    }
}
