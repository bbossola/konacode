package dev.konacode.agent;

import dev.konacode.llm.LlmClient;
import dev.konacode.llm.LlmException;
import dev.konacode.llm.Message;
import dev.konacode.llm.Message.AssistantMessage;
import dev.konacode.llm.Message.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Replaces the conversation with the system message and a summary the model wrote.
 *
 * <p>It lives here because {@link Cancellation#arm} is package-private: only this package decides
 * where an interrupt is safe. It sends no tool, so the model cannot act while it summarizes. It
 * lets an {@link LlmException} through, so the caller reads a typed failure, and the conversation
 * stays as it was.
 */
public final class Compaction {

    /** Prompt text, and not a comment. A test pins it. */
    static final String PROMPT = """
            Summarize this conversation for yourself, so that you can continue the work from the summary alone.
            Answer with text only. Do not call a tool.
            Include what the user asked for, what is done, and what is not done.
            Include every file you changed, and what you changed in it.
            Include the facts a next step needs: paths, names, and decisions.
            Leave out the contents of files and the output of commands.
            """;

    /** The user turn that carries the summary. The pair below is the shape {@code /skill} uses. */
    static final String FRAME = "This is the summary of the conversation so far. Continue from it.\n\n";

    static final String ACKNOWLEDGEMENT = "Understood.";

    private final LlmClient client;
    private final Message systemMessage;
    private final Conversation conversation;
    private final Cancellation cancellation;

    public Compaction(LlmClient client, Message systemMessage, Conversation conversation, Cancellation cancellation) {
        this.client = Objects.requireNonNull(client, "client");
        this.systemMessage = Objects.requireNonNull(systemMessage, "systemMessage");
        this.conversation = Objects.requireNonNull(conversation, "conversation");
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
    }

    /**
     * Asks the model for the summary, and restarts the conversation with it.
     *
     * @return the summary, or empty when the conversation holds the system message only
     * @throws LlmException when the provider fails, or when the reply holds no summary
     */
    public Optional<String> compact() {
        List<Message> history = conversation.messages();
        if (history.size() <= 1) {
            return Optional.empty();
        }
        List<Message> request = new ArrayList<>(history);
        request.add(new UserMessage(PROMPT));
        AssistantMessage reply = chat(request);
        if (reply.hasToolCalls() || reply.text().isBlank()) {
            throw new LlmException("The model gave no summary.");
        }
        String summary = reply.text().strip();
        conversation.restart(List.of(systemMessage, new UserMessage(FRAME + summary), new AssistantMessage(ACKNOWLEDGEMENT, List.of())));
        return Optional.of(summary);
    }

    /** Arms the interrupt for the length of the provider call and no longer, the way {@code Agent.chat} does. */
    private AssistantMessage chat(List<Message> request) {
        cancellation.arm();
        try {
            return client.chat(request, List.of());
        } finally {
            cancellation.disarm();
        }
    }
}
