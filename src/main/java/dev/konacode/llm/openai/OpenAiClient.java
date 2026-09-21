package dev.konacode.llm.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.konacode.llm.LlmClient;
import dev.konacode.llm.LlmException;
import dev.konacode.llm.Message;
import dev.konacode.llm.Message.AssistantMessage;
import dev.konacode.llm.ToolSpec;
import dev.konacode.llm.openai.Credential.ApiKey;
import dev.konacode.llm.openai.Credential.CodexToken;
import dev.konacode.trace.Trace;
import dev.konacode.trace.TraceEvent.ReplyReceived;
import dev.konacode.trace.TraceEvent.RequestSent;
import dev.konacode.trace.TraceEvent.RetryRequested;
import dev.konacode.trace.TraceEvent.TokensUsed;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Transport. Owns HTTP status handling and nothing else — the translation lives in the
 * {@link Codec}.
 */
public final class OpenAiClient implements LlmClient {

    private static final int ERROR_BODY_LIMIT = 500;

    /** The statuses that say "later", and not "no". Every other one is the answer of the provider. */
    private static final Set<Integer> TRANSIENT_STATUSES = Set.of(429, 502, 503, 504);

    static final int MAX_ATTEMPTS = 3;
    private static final Duration FIRST_WAIT = Duration.ofMillis(500);

    private final OpenAiConfig config;
    private final HttpClient http;
    private final Codec codec;
    private final Trace trace;
    private final Backoff backoff;

    /** The wait before one more attempt. A test gives one that does not sleep. */
    @FunctionalInterface
    interface Backoff {
        /** Waits before {@code attempt}, which counts from two. */
        void pauseBefore(int attempt);
    }

    public OpenAiClient(OpenAiConfig config, Trace trace) {
        this(config,
                HttpClient.newBuilder().connectTimeout(config.timeout()).build(),
                new ChatCompletionsCodec(new ObjectMapper()),
                trace);
    }

    public OpenAiClient(OpenAiConfig config, HttpClient http, Codec codec,
                        Trace trace) {
        this(config, http, codec, trace, OpenAiClient::sleepBefore);
    }

    OpenAiClient(OpenAiConfig config, HttpClient http, Codec codec, Trace trace,
                 Backoff backoff) {
        this.config = config;
        this.http = http;
        this.codec = codec;
        this.trace = trace;
        this.backoff = backoff;
    }

    @Override
    public AssistantMessage chat(List<Message> history, List<ToolSpec> tools) {
        ObjectNode body = codec.encodeRequest(config.model(), history, tools);
        ReplyValidator validator = ReplyValidator.create(config.model(), tools);

        return sendUntilAccepted(validator,
                () -> sendUntilDelivered(() -> sendOnce(body, history.size(), tools.size()), backoff, trace),
                trace);
    }

    /**
     * Sends until the validator accepts a reply. The body is encoded once and re-sent unchanged,
     * so a retry is a fresh sample of the same request rather than a subtly different one.
     *
     * <p>Package-private and static so the retry behaviour can be tested with a scripted sender,
     * and without a network. Termination is guaranteed by the validator, which accepts
     * unconditionally once its budget is spent.
     */
    static AssistantMessage sendUntilAccepted(
            ReplyValidator validator, Supplier<AssistantMessage> send, Trace trace) {
        AssistantMessage reply = send.get();
        while (!validator.accepts(reply)) {
            trace.emit(new RetryRequested("The reply carried a tool call written as prose."));
            reply = send.get();
        }
        return reply;
    }

    /**
     * Sends until the request arrives, or until the budget ends. A transient failure buys another
     * attempt; every other failure ends the turn at once, because the model cannot fix a 401.
     *
     * <p>This budget is not the budget of the validator. That one repairs the protocol, and this
     * one repairs the transport, so a garbled reply on a poor network spends neither twice.
     */
    static AssistantMessage sendUntilDelivered(Supplier<AssistantMessage> send, Backoff backoff, Trace trace) {
        for (int attempt = 1; ; attempt++) {
            try {
                return send.get();
            } catch (TransientFailure e) {
                if (attempt == MAX_ATTEMPTS) {
                    throw e;
                }
                // The interrupt is how esc reaches a turn, so it ends the retry before the wait.
                if (Thread.currentThread().isInterrupted()) {
                    throw new LlmException("Request was interrupted.", e);
                }
                trace.emit(new RetryRequested(e.retryReason()));
            }
            backoff.pauseBefore(attempt + 1);
        }
    }

    private static void sleepBefore(int attempt) {
        try {
            Thread.sleep(FIRST_WAIT.toMillis() << (attempt - 2));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("Request was interrupted.", e);
        }
    }

    private AssistantMessage sendOnce(ObjectNode body, int messageCount, int toolCount) {
        URI uri;
        HttpRequest request;
        try {
            uri = config.uri(codec.path());
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(config.timeout())
                    .header("Content-Type", "application/json")
                    .header("Accept", codec.accept())
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
            authorize(builder);
            request = builder.build();
        } catch (IllegalArgumentException e) {
            // A malformed base URL, or a key carrying a control character - a trailing newline
            // survives isBlank() - would otherwise escape as an unchecked exception and kill the
            // session, since the agent loop catches only LlmException.
            throw new LlmException("Could not build the request: " + e.getMessage(), e);
        }

        // The body and never the headers. The credential is a header, so it cannot reach a sink.
        trace.emit(new RequestSent(uri.toString(), config.model(), messageCount, toolCount, body.toString()));

        long started = System.nanoTime();
        HttpResponse<String> response;
        try {
            response = http.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new TransientFailure("Request to " + uri + " failed: " + e.getMessage(), "The request did not reach the provider.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("Request was interrupted.", e);
        }
        trace.emit(new ReplyReceived(response.statusCode(),
                (System.nanoTime() - started) / 1_000_000, response.body()));

        if (TRANSIENT_STATUSES.contains(response.statusCode())) {
            throw new TransientFailure("HTTP " + response.statusCode() + ": " + truncate(response.body()),
                    "The provider answered HTTP " + response.statusCode() + ".");
        }

        if (response.statusCode() / 100 != 2) {
            throw new LlmException("HTTP " + response.statusCode() + ": " + truncate(response.body()) + loginHint(response.statusCode()));
        }

        codec.decodeUsage(response.body()).ifPresent(usage ->
                trace.emit(new TokensUsed(usage.prompt(), usage.completion(), usage.total())));

        return codec.decodeResponse(response.body());
    }

    /**
     * konacode names itself in {@code originator} and {@code User-Agent}. It never writes the name of
     * the Codex CLI: if the server refuses a client that is not Codex, that is the answer of the
     * provider, and konacode stops.
     */
    private void authorize(HttpRequest.Builder builder) {
        switch (config.credential()) {
            case ApiKey key -> builder.header("Authorization", "Bearer " + key.key());
            case CodexToken token -> builder
                    .header("Authorization", "Bearer " + token.accessToken())
                    .header("ChatGPT-Account-ID", token.accountId())
                    .header("originator", "konacode")
                    .header("User-Agent", "konacode");
        }
    }

    /** A 401 on a Codex token has one repair, and the user reads it here and not in a log. */
    private String loginHint(int status) {
        if (status != 401) {
            return "";
        }
        return switch (config.credential()) {
            case ApiKey ignored -> "";
            case CodexToken ignored -> CodexAuth.RUN_LOGIN;
        };
    }

    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        String collapsed = body.replaceAll("\\s+", " ").trim();
        return collapsed.length() > ERROR_BODY_LIMIT
                ? collapsed.substring(0, ERROR_BODY_LIMIT) + "…"
                : collapsed;
    }
}
