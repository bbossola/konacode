package dev.konacode.llm.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.konacode.llm.LlmException;
import dev.konacode.llm.Message.AssistantMessage;
import dev.konacode.llm.Message.UserMessage;
import dev.konacode.llm.ToolSpec;
import dev.konacode.tools.Schemas;
import dev.konacode.trace.Trace;
import dev.konacode.trace.TraceEvent;
import dev.konacode.trace.TraceEvent.ReplyReceived;
import dev.konacode.trace.TraceEvent.RequestSent;
import dev.konacode.trace.TraceEvent.RetryRequested;
import dev.konacode.trace.TraceEvent.TokensUsed;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
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

    /**
     * {@code sendOnce} is private, and only {@code chat} reaches it. These tests mock
     * {@link HttpClient} so no socket opens, per {@code CLAUDE.md}: use Mockito for a type this
     * project does not own.
     */
    @Mock
    HttpClient http;

    @Mock
    HttpResponse<String> response;

    private static final String API_KEY = "sk-secret-do-not-log";

    private static OpenAiConfig configWithTheKey() {
        return new OpenAiConfig(API_KEY, "gpt-5-mini", "https://example.test/v1",
                Duration.ofSeconds(1));
    }

    private void stubHttpToReturn(int status, String body) throws Exception {
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        when(http.send(any(HttpRequest.class),
                ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(response);
    }

    private OpenAiClient clientWithMockedHttp(List<TraceEvent> events) {
        return new OpenAiClient(configWithTheKey(), http, new ChatCompletionsCodec(new ObjectMapper()),
                events::add);
    }

    @Test
    void requestSentCarriesTheBodyAndNoEventCarriesTheApiKey() throws Exception {
        stubHttpToReturn(200, """
                {"choices":[{"message":{"content":"hi"}}],
                 "usage":{"prompt_tokens":5,"completion_tokens":7,"total_tokens":12}}""");
        List<TraceEvent> events = new ArrayList<>();
        OpenAiClient client = clientWithMockedHttp(events);

        AssistantMessage reply = client.chat(List.of(new UserMessage("what is here?")), List.of());

        assertEquals("hi", reply.text());
        RequestSent requestSent = events.stream()
                .filter(RequestSent.class::isInstance)
                .map(RequestSent.class::cast)
                .findFirst()
                .orElseThrow();
        assertTrue(requestSent.bodyJson().contains("what is here?"), requestSent.bodyJson());
        assertFalse(events.isEmpty());
        for (TraceEvent event : events) {
            assertFalse(event.toString().contains(API_KEY), event.toString());
        }
    }

    @Test
    void replyReceivedIsEmittedForANon2xxStatusBeforeTheExceptionIsThrown() throws Exception {
        stubHttpToReturn(500, "{\"error\":\"boom\"}");
        List<TraceEvent> events = new ArrayList<>();
        OpenAiClient client = clientWithMockedHttp(events);

        assertThrows(LlmException.class, () -> client.chat(List.of(), List.of()));

        ReplyReceived replyReceived = events.stream()
                .filter(ReplyReceived.class::isInstance)
                .map(ReplyReceived.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals(500, replyReceived.status());
    }

    @Test
    void tokensUsedCarriesTheCountsTheReplyReported() throws Exception {
        stubHttpToReturn(200, """
                {"choices":[{"message":{"content":"hi"}}],
                 "usage":{"prompt_tokens":5,"completion_tokens":7,"total_tokens":12}}""");
        List<TraceEvent> events = new ArrayList<>();
        OpenAiClient client = clientWithMockedHttp(events);

        client.chat(List.of(), List.of());

        TokensUsed tokensUsed = events.stream()
                .filter(TokensUsed.class::isInstance)
                .map(TokensUsed.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals(5, tokensUsed.prompt());
        assertEquals(7, tokensUsed.completion());
        assertEquals(12, tokensUsed.total());
    }

    @Test
    void tokensUsedIsAbsentWhenTheReplyReportsNone() throws Exception {
        stubHttpToReturn(200, "{\"choices\":[{\"message\":{\"content\":\"hi\"}}]}");
        List<TraceEvent> events = new ArrayList<>();
        OpenAiClient client = clientWithMockedHttp(events);

        client.chat(List.of(), List.of());

        assertTrue(events.stream().noneMatch(TokensUsed.class::isInstance));
    }
}
