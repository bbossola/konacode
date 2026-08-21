package dev.konacode.llm.openai;

import dev.konacode.llm.LlmException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

class OpenAiClientTest {

    private static OpenAiClient clientWith(String apiKey, String baseUrl) {
        return new OpenAiClient(new OpenAiConfig(apiKey, "gpt-5-mini", baseUrl, Duration.ofSeconds(1)));
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
}
