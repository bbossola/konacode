package dev.konacode.llm.openai;

import dev.konacode.llm.LlmException;
import dev.konacode.llm.Message.AssistantMessage;
import dev.konacode.llm.ToolSpec;
import dev.konacode.tools.Schemas;
import dev.konacode.trace.Trace;
import dev.konacode.trace.TraceEvent;
import dev.konacode.trace.TraceEvent.RetryRequested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OpenAiClientTest {

    private static OpenAiClient clientWith(String apiKey, String baseUrl) {
        return new OpenAiClient(
                new OpenAiConfig(apiKey, "gpt-5-mini", baseUrl, Duration.ofSeconds(1)), Trace.NONE);
    }

    @Test
    void translatesAMalformedBaseUrlIntoAnLlmException() {
        // URI.create fails before any connection is attempted, so this touches no network.
        OpenAiClient client = clientWith("sk-test", "https://my host/v1");

        assertThrows(LlmException.class, () -> client.chat(List.of(), List.of()));
    }

    @Test
    void translatesAKeyCarryingAControlCharacterIntoAnLlmException() {
        // Trimming handles a trailing newline; an embedded one still reaches
        // HttpRequest.header, which rejects it with an unchecked IllegalArgumentException.
        OpenAiClient client = clientWith("sk-abc\ndef", "https://example.test/v1");

        assertThrows(LlmException.class, () -> client.chat(List.of(), List.of()));
    }

    private static AssistantMessage garbled() {
        return new AssistantMessage("<function=list_files>\n</function>", List.of());
    }

    private static AssistantMessage plain(String text) {
        return new AssistantMessage(text, List.of());
    }

    private static ReplyValidator validator() {
        return ReplyValidator.create("qwen3-coder",
                List.of(new ToolSpec("list_files", "List files.", Schemas.object().build())));
    }

    /** Hands out scripted replies and counts how many were asked for. */
    private static final class ScriptedSender implements Supplier<AssistantMessage> {
        private final Deque<AssistantMessage> script = new ArrayDeque<>();
        private int sends;

        ScriptedSender(AssistantMessage... replies) {
            Collections.addAll(script, replies);
        }

        @Override
        public AssistantMessage get() {
            sends++;
            if (script.isEmpty()) {
                throw new AssertionError("asked for more replies than were scripted");
            }
            return script.poll();
        }
    }

    @Test
    void sendsOnceWhenTheFirstReplyIsAccepted() {
        ScriptedSender sender = new ScriptedSender(plain("Two files here."));

        AssistantMessage reply = OpenAiClient.sendUntilAccepted(validator(), sender, Trace.NONE);

        assertEquals("Two files here.", reply.text());
        assertEquals(1, sender.sends);
    }

    @Test
    void asksAgainWhenTheFirstReplyIsAGarbledToolCall() {
        ScriptedSender sender = new ScriptedSender(garbled(), plain("Two files here."));

        AssistantMessage reply = OpenAiClient.sendUntilAccepted(validator(), sender, Trace.NONE);

        assertEquals("Two files here.", reply.text());
        assertEquals(2, sender.sends);
    }

    @Test
    void returnsTheSecondGarbledReplyAsItCameRatherThanRetryingForever() {
        ScriptedSender sender = new ScriptedSender(garbled(), garbled());

        AssistantMessage reply = OpenAiClient.sendUntilAccepted(validator(), sender, Trace.NONE);

        assertEquals(garbled().text(), reply.text());
        assertEquals(2, sender.sends);
    }

    @Test
    void reportsEveryRetry() {
        List<TraceEvent> events = new ArrayList<>();
        ScriptedSender sender = new ScriptedSender(garbled(), plain("Two files here."));

        OpenAiClient.sendUntilAccepted(validator(), sender, events::add);

        assertEquals(1, events.size(), events.toString());
        assertInstanceOf(RetryRequested.class, events.get(0));
    }

    @Test
    void reportsNoRetryWhenTheFirstReplyIsAccepted() {
        List<TraceEvent> events = new ArrayList<>();

        OpenAiClient.sendUntilAccepted(validator(), new ScriptedSender(plain("Done.")),
                events::add);

        assertEquals(List.of(), events);
    }
}
