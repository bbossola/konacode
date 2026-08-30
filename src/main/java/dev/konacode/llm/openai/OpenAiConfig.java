package dev.konacode.llm.openai;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

/**
 * Provider settings.
 *
 * <p>The API key is checked for presence and nothing else. Any OpenAI-compatible endpoint works
 * through {@code KONACODE_BASE_URL} — a local Ollama server, for instance — and those ignore the
 * key entirely. A shape check such as an {@code sk-} prefix would break every one of them.
 *
 * <p>The judge model sits beside the model, because the judge speaks to the same endpoint with the
 * same key and only the model name differs.
 */
public record OpenAiConfig(String apiKey, String model, String judgeModel, String baseUrl, Duration timeout) {

    public static final String DEFAULT_MODEL = "gpt-5-mini";
    public static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";
    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(2);

    public OpenAiConfig {
        apiKey = apiKey == null ? null : apiKey.trim();
        model = model == null ? null : model.trim();
        judgeModel = judgeModel == null ? null : judgeModel.trim();
        baseUrl = baseUrl == null ? null : baseUrl.trim();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("OPENAI_API_KEY is not set.");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("Model must not be blank.");
        }
        if (judgeModel == null || judgeModel.isBlank()) {
            throw new IllegalArgumentException("Judge model must not be blank.");
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("Base URL must not be blank.");
        }
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
    }

    public static OpenAiConfig fromEnvironment(Map<String, String> environment) {
        String model = environment.getOrDefault("KONACODE_MODEL", DEFAULT_MODEL);
        return new OpenAiConfig(
                environment.get("OPENAI_API_KEY"),
                model,
                environment.getOrDefault("KONACODE_JUDGE_MODEL", model),
                environment.getOrDefault("KONACODE_BASE_URL", DEFAULT_BASE_URL),
                DEFAULT_TIMEOUT);
    }

    /** The same key, base URL and timeout, with the model the judge uses. */
    public OpenAiConfig forJudge() {
        return new OpenAiConfig(apiKey, judgeModel, judgeModel, baseUrl, timeout);
    }

    /**
     * Built by string concatenation rather than {@link URI#resolve}, which would treat the
     * trailing {@code /v1} as a file rather than a directory and silently drop it.
     */
    public URI chatCompletionsUri() {
        return URI.create(baseUrl + "/chat/completions");
    }
}
