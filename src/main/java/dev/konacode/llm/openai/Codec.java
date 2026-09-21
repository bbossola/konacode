package dev.konacode.llm.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.konacode.llm.Message;
import dev.konacode.llm.Message.AssistantMessage;
import dev.konacode.llm.ToolSpec;
import dev.konacode.llm.openai.Credential.ApiKey;
import dev.konacode.llm.openai.Credential.CodexToken;

import java.util.List;
import java.util.Optional;

/**
 * The wire format of one endpoint: the path, the {@code Accept} header, and the translation both
 * ways. A codec holds no HTTP, so a test reads it against a recorded fixture.
 */
public interface Codec {

    /** The path after the base URL. */
    String path();

    /** The value of the {@code Accept} header. */
    String accept();

    ObjectNode encodeRequest(String model, List<Message> history, List<ToolSpec> tools);

    AssistantMessage decodeResponse(String body);

    /** The token counts of a reply, when the provider reported them. It never throws. */
    Optional<Usage> decodeUsage(String body);

    /**
     * The credential decides the wire format. A key speaks Chat Completions, and a Codex token
     * speaks the Responses API of the Codex backend. The switch is exhaustive, so a third
     * credential must name its codec here.
     */
    static Codec forCredential(Credential credential, ObjectMapper mapper) {
        return switch (credential) {
            case ApiKey ignored -> new ChatCompletionsCodec(mapper);
            case CodexToken ignored -> new ResponsesCodec(mapper);
        };
    }
}
