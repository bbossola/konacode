package dev.konacode.llm.openai;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiConfigTest {

    @Test
    void fillsInDefaultsForEverythingButTheKey() {
        OpenAiConfig config = OpenAiConfig.fromEnvironment(Map.of("OPENAI_API_KEY", "sk-test"));

        assertEquals("sk-test", config.apiKey());
        assertEquals(OpenAiConfig.DEFAULT_MODEL, config.model());
        assertEquals(OpenAiConfig.DEFAULT_BASE_URL, config.baseUrl());
    }

    @Test
    void readsTheModelAndBaseUrlOverrides() {
        OpenAiConfig config = OpenAiConfig.fromEnvironment(Map.of(
                "OPENAI_API_KEY", "ollama",
                "KONACODE_MODEL", "qwen2.5-coder:32b",
                "KONACODE_BASE_URL", "http://localhost:11434/v1"));

        assertEquals("qwen2.5-coder:32b", config.model());
        assertEquals("http://localhost:11434/v1", config.baseUrl());
    }

    @Test
    void acceptsAnyNonBlankKeySoLocalModelsWork() {
        // Validating the key's shape (an "sk-" prefix, say) would look like sensible input
        // validation and would break every Ollama user. Presence only.
        OpenAiConfig config = OpenAiConfig.fromEnvironment(Map.of("OPENAI_API_KEY", "ollama"));

        assertEquals("ollama", config.apiKey());
    }

    @Test
    void rejectsAMissingOrBlankKey() {
        assertThrows(IllegalArgumentException.class, () -> OpenAiConfig.fromEnvironment(Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> OpenAiConfig.fromEnvironment(Map.of("OPENAI_API_KEY", "   ")));
    }

    @Test
    void buildsTheEndpointWithoutLosingTheApiVersionSegment() {
        OpenAiConfig withSlash = OpenAiConfig.fromEnvironment(Map.of(
                "OPENAI_API_KEY", "k", "KONACODE_BASE_URL", "https://example.test/v1/"));
        OpenAiConfig withoutSlash = OpenAiConfig.fromEnvironment(Map.of(
                "OPENAI_API_KEY", "k", "KONACODE_BASE_URL", "https://example.test/v1"));

        // URI.resolve would drop the /v1 segment here, which is why the URI is built by hand.
        assertEquals("https://example.test/v1/chat/completions",
                withSlash.chatCompletionsUri().toString());
        assertEquals("https://example.test/v1/chat/completions",
                withoutSlash.chatCompletionsUri().toString());
    }

    @Test
    void errorMessageNamesTheVariableTheUserMustSet() {
        IllegalArgumentException thrown = assertThrows(
                IllegalArgumentException.class, () -> OpenAiConfig.fromEnvironment(Map.of()));

        assertTrue(thrown.getMessage().contains("OPENAI_API_KEY"), thrown.getMessage());
    }

    @Test
    void trimsSurroundingWhitespaceSoAKeyReadFromAFileStillWorks() {
        // "sk-test\n".isBlank() is false, so validation passes and the newline reaches
        // HttpRequest.header, which rejects it with an unchecked exception.
        OpenAiConfig config = OpenAiConfig.fromEnvironment(Map.of(
                "OPENAI_API_KEY", "sk-test\n",
                "KONACODE_MODEL", " gpt-5-mini ",
                "KONACODE_BASE_URL", " https://example.test/v1 "));

        assertEquals("sk-test", config.apiKey());
        assertEquals("gpt-5-mini", config.model());
        assertEquals("https://example.test/v1", config.baseUrl());
    }
}
