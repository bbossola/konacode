package dev.konacode.llm.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.konacode.llm.LlmClient;
import dev.konacode.llm.LlmException;
import dev.konacode.llm.Message;
import dev.konacode.llm.Message.AssistantMessage;
import dev.konacode.llm.ToolSpec;
import dev.konacode.trace.Trace;
import dev.konacode.trace.TraceEvent.ReplyReceived;
import dev.konacode.trace.TraceEvent.RequestSent;
import dev.konacode.trace.TraceEvent.RetryRequested;
import dev.konacode.trace.TraceEvent.TokensUsed;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Supplier;

/**
 * Transport. Owns HTTP status handling and nothing else — the translation lives in
 * {@link ChatCompletionsCodec}.
 */
public final class OpenAiClient implements LlmClient {

    private static final int ERROR_BODY_LIMIT = 500;

    private final OpenAiConfig config;
    private final HttpClient http;
    private final ChatCompletionsCodec codec;
    private final Trace trace;

    public OpenAiClient(OpenAiConfig config, Trace trace) {
        this(config,
                HttpClient.newBuilder().connectTimeout(config.timeout()).build(),
                new ChatCompletionsCodec(new ObjectMapper()),
                trace);
    }

    public OpenAiClient(OpenAiConfig config, HttpClient http, ChatCompletionsCodec codec,
                        Trace trace) {
        this.config = config;
        this.http = http;
        this.codec = codec;
        this.trace = trace;
    }

    @Override
    public AssistantMessage chat(List<Message> history, List<ToolSpec> tools) {
        ObjectNode body = codec.encodeRequest(config.model(), history, tools);
        ReplyValidator validator = ReplyValidator.create(config.model(), tools);

        return sendUntilAccepted(validator,
                () -> sendOnce(body, history.size(), tools.size()), trace);
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

    private AssistantMessage sendOnce(ObjectNode body, int messageCount, int toolCount) {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(config.chatCompletionsUri())
                    .timeout(config.timeout())
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + config.apiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                    .build();
        } catch (IllegalArgumentException e) {
            // A malformed base URL, or a key carrying a control character - a trailing newline
            // survives isBlank() - would otherwise escape as an unchecked exception and kill the
            // session, since the agent loop catches only LlmException.
            throw new LlmException("Could not build the request: " + e.getMessage(), e);
        }

        // The body and never the headers. The API key is a header, so it cannot reach a sink.
        trace.emit(new RequestSent(config.chatCompletionsUri().toString(), config.model(),
                messageCount, toolCount, body.toString()));

        long started = System.nanoTime();
        HttpResponse<String> response;
        try {
            response = http.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new LlmException(
                    "Request to " + config.chatCompletionsUri() + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("Request was interrupted.", e);
        }
        trace.emit(new ReplyReceived(response.statusCode(),
                (System.nanoTime() - started) / 1_000_000, response.body()));

        if (response.statusCode() / 100 != 2) {
            throw new LlmException(
                    "HTTP " + response.statusCode() + ": " + truncate(response.body()));
        }

        codec.decodeUsage(response.body()).ifPresent(usage ->
                trace.emit(new TokensUsed(usage.prompt(), usage.completion(), usage.total())));

        return codec.decodeResponse(response.body());
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
