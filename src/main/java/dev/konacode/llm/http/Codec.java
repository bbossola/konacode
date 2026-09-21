package dev.konacode.llm.http;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.konacode.llm.Message;
import dev.konacode.llm.Message.AssistantMessage;
import dev.konacode.llm.ToolSpec;

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
}
