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

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpenAiClientTest {

    private static OpenAiClient clientWith(String apiKey, String baseUrl) {
        return new OpenAiClient(
                new OpenAiConfig(apiKey, "gpt-5-mini", "gpt-5-mini", baseUrl, Duration.ofSeconds(1)), Trace.NONE);
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

    /** A backoff that does not sleep, so a test of the retry costs no time. */
    private static final OpenAiClient.Backoff NO_WAIT = attempt -> {
    };

    private static Supplier<AssistantMessage> failsThenAnswers(int failures, AtomicInteger sends) {
        return () -> {
            if (sends.incrementAndGet() <= failures) {
                throw new TransientFailure("HTTP 503: busy", "The provider answered HTTP 503.");
            }
            return plain("Done.");
        };
    }

    @Test
    void retriesATransientFailureAndThenSucceeds() {
        AtomicInteger sends = new AtomicInteger();

        AssistantMessage reply = OpenAiClient.sendUntilDelivered(failsThenAnswers(1, sends), NO_WAIT, Trace.NONE);

        assertEquals("Done.", reply.text());
        assertEquals(2, sends.get());
    }

    @Test
    void doesNotRetryAPermanentFailure() {
        AtomicInteger sends = new AtomicInteger();
        Supplier<AssistantMessage> send = () -> {
            sends.incrementAndGet();
            throw new LlmException("HTTP 401: bad key");
        };

        assertThrows(LlmException.class, () -> OpenAiClient.sendUntilDelivered(send, NO_WAIT, Trace.NONE));

        assertEquals(1, sends.get(), "a key konacode cannot fix must not be asked about twice");
    }

    @Test
    void givesUpAfterTheThirdAttemptAndReportsTheLastFailure() {
        AtomicInteger sends = new AtomicInteger();

        LlmException thrown = assertThrows(LlmException.class,
                () -> OpenAiClient.sendUntilDelivered(failsThenAnswers(9, sends), NO_WAIT, Trace.NONE));

        assertEquals(3, sends.get());
        assertTrue(thrown.getMessage().contains("503"), thrown.getMessage());
    }

    @Test
    void reportsEveryTransportRetry() {
        List<TraceEvent> events = new ArrayList<>();

        OpenAiClient.sendUntilDelivered(failsThenAnswers(1, new AtomicInteger()), NO_WAIT, events::add);

        List<String> reasons = events.stream()
                .filter(RetryRequested.class::isInstance)
                .map(event -> ((RetryRequested) event).reason())
                .toList();
        assertEquals(1, reasons.size(), events.toString());
        assertTrue(reasons.get(0).contains("503"), reasons.get(0));
    }

    @Test
    void stopsTheRetryWhenTheThreadIsInterrupted() {
        AtomicInteger sends = new AtomicInteger();
        try {
            Thread.currentThread().interrupt();

            assertThrows(LlmException.class,
                    () -> OpenAiClient.sendUntilDelivered(failsThenAnswers(9, sends), NO_WAIT, Trace.NONE));

            assertEquals(1, sends.get(), "esc must end the retry, and not wait for the budget");
        } finally {
            Thread.interrupted();
        }
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
        return new OpenAiConfig(API_KEY, "gpt-5-mini", "gpt-5-mini", "https://example.test/v1", Duration.ofSeconds(1));
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
                events::add, NO_WAIT);
    }

    private static HttpResponse<String> answer(int status, String body) {
        @SuppressWarnings("unchecked")
        HttpResponse<String> stub = mock(HttpResponse.class);
        when(stub.statusCode()).thenReturn(status);
        when(stub.body()).thenReturn(body);
        return stub;
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

    @Test
    void retriesA503AndSucceeds() throws Exception {
        // Both stubs are built before the send is stubbed: a when() inside a thenReturn() would
        // leave the outer stubbing unfinished.
        HttpResponse<String> busy = answer(503, "{\"error\":\"busy\"}");
        HttpResponse<String> ok = answer(200, "{\"choices\":[{\"message\":{\"content\":\"hi\"}}]}");
        when(http.send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(busy, ok);
        OpenAiClient client = clientWithMockedHttp(new ArrayList<>());

        assertEquals("hi", client.chat(List.of(), List.of()).text());

        verify(http, times(2)).send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
    }

    @Test
    void retriesARequestThatDidNotArrive() throws Exception {
        HttpResponse<String> ok = answer(200, "{\"choices\":[{\"message\":{\"content\":\"hi\"}}]}");
        when(http.send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenThrow(new IOException("connection reset"))
                .thenReturn(ok);
        OpenAiClient client = clientWithMockedHttp(new ArrayList<>());

        assertEquals("hi", client.chat(List.of(), List.of()).text());

        verify(http, times(2)).send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
    }

    @Test
    void doesNotRetryA401() throws Exception {
        stubHttpToReturn(401, "{\"error\":\"bad key\"}");
        OpenAiClient client = clientWithMockedHttp(new ArrayList<>());

        assertThrows(LlmException.class, () -> client.chat(List.of(), List.of()));

        verify(http, times(1)).send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
    }
}
