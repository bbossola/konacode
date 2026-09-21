package dev.konacode.llm.http;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ClientConfigTest {

    private static final Credential KEY = new Credential() {
        @Override
        public Map<String, String> headers() {
            return Map.of("Authorization", "Bearer sk-test");
        }

        @Override
        public String hint(int status) {
            return "";
        }
    };

    private static ClientConfig config(String model, String judgeModel, String baseUrl) {
        return new ClientConfig(KEY, model, judgeModel, baseUrl, Duration.ofSeconds(1));
    }

    @Test
    void theJudgeTalksToTheSameEndpointWithTheSameCredential() {
        ClientConfig config = config("gpt-5", "gpt-5-mini", "https://example.test/v1");

        ClientConfig judge = config.forJudge();

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
        ClientConfig config = config(" gpt-5-mini ", " gpt-5 ", " https://example.test/v1 ");

        assertEquals("gpt-5-mini", config.model());
        assertEquals("gpt-5", config.judgeModel());
        assertEquals("https://example.test/v1", config.baseUrl());
    }

    @Test
    void refusesABlankModelJudgeModelOrBaseUrl() {
        assertThrows(IllegalArgumentException.class, () -> config(" ", "m", "https://example.test/v1"));
        assertThrows(IllegalArgumentException.class, () -> config("m", " ", "https://example.test/v1"));
        assertThrows(IllegalArgumentException.class, () -> config("m", "m", " "));
        assertThrows(NullPointerException.class, () -> new ClientConfig(null, "m", "m", "https://example.test/v1", Duration.ofSeconds(1)));
    }
}
