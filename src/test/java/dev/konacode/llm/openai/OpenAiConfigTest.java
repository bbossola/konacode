package dev.konacode.llm.openai;

import dev.konacode.llm.openai.Credential.ApiKey;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OpenAiConfigTest {

    private static final ApiKey KEY = new ApiKey("sk-test");

    private static OpenAiConfig config(String model, String judgeModel, String baseUrl) {
        return new OpenAiConfig(KEY, model, judgeModel, baseUrl, Duration.ofSeconds(1));
    }

    @Test
    void theJudgeTalksToTheSameEndpointWithTheSameCredential() {
        OpenAiConfig config = config("gpt-5", "gpt-5-mini", "https://example.test/v1");

        OpenAiConfig judge = config.forJudge();

        assertEquals("gpt-5-mini", judge.model());
        assertEquals("gpt-5-mini", judge.judgeModel());
        assertEquals(config.credential(), judge.credential());
        assertEquals(config.baseUrl(), judge.baseUrl());
        assertEquals(config.timeout(), judge.timeout());
    }

    @Test
    void buildsTheEndpointWithoutLosingTheApiVersionSegment() {
        // URI.resolve would drop the /v1 segment here, which is why the URI is built by hand.
        assertEquals("https://example.test/v1/chat/completions", config("m", "m", "https://example.test/v1/").uri("/chat/completions").toString());
        assertEquals("https://example.test/v1/chat/completions", config("m", "m", "https://example.test/v1").uri("/chat/completions").toString());
    }

    @Test
    void trimsEveryValue() {
        OpenAiConfig config = config(" gpt-5-mini ", " gpt-5 ", " https://example.test/v1 ");

        assertEquals("gpt-5-mini", config.model());
        assertEquals("gpt-5", config.judgeModel());
        assertEquals("https://example.test/v1", config.baseUrl());
    }

    @Test
    void refusesABlankModelJudgeModelOrBaseUrl() {
        assertThrows(IllegalArgumentException.class, () -> config(" ", "m", "https://example.test/v1"));
        assertThrows(IllegalArgumentException.class, () -> config("m", " ", "https://example.test/v1"));
        assertThrows(IllegalArgumentException.class, () -> config("m", "m", " "));
        assertThrows(NullPointerException.class, () -> new OpenAiConfig(null, "m", "m", "https://example.test/v1", Duration.ofSeconds(1)));
    }
}
