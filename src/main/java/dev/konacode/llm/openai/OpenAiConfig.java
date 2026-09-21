package dev.konacode.llm.openai;

import dev.konacode.llm.openai.Credential.ApiKey;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Provider settings.
 *
 * <p>{@code KONACODE_AUTH} picks the credential, and the credential picks the defaults. A key
 * speaks to {@code https://api.openai.com/v1}, or to any OpenAI-compatible endpoint through
 * {@code KONACODE_BASE_URL}. A Codex token speaks to the Codex backend.
 *
 * <p>The judge model sits beside the model, because the judge speaks to the same endpoint with the
 * same credential and only the model name differs.
 */
public record OpenAiConfig(Credential credential, String model, String judgeModel, String baseUrl, Duration timeout) {

    public static final String DEFAULT_MODEL = "gpt-5-mini";
    public static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";
    /** On the bundled list of the CLI, and one of the two models that take the plain Responses shape. */
    public static final String DEFAULT_CODEX_MODEL = "gpt-5.5";
    public static final String DEFAULT_CODEX_BASE_URL = "https://chatgpt.com/backend-api/codex";
    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(2);

    public OpenAiConfig {
        Objects.requireNonNull(credential, "credential");
        model = model == null ? null : model.trim();
        judgeModel = judgeModel == null ? null : judgeModel.trim();
        baseUrl = baseUrl == null ? null : baseUrl.trim();
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

    public static OpenAiConfig fromEnvironment(Map<String, String> environment, Path codexAuthFile) {
        return fromEnvironment(environment, codexAuthFile, Instant.now());
    }

    /** {@code now} is a parameter, so a test can hold a token that expires at a known time. */
    static OpenAiConfig fromEnvironment(Map<String, String> environment, Path codexAuthFile, Instant now) {
        String auth = environment.getOrDefault("KONACODE_AUTH", "key");
        Credential credential;
        String defaultModel;
        String defaultBaseUrl;
        switch (auth.trim().toLowerCase(Locale.ROOT)) {
            case "key" -> {
                credential = new ApiKey(environment.get("OPENAI_API_KEY"));
                defaultModel = DEFAULT_MODEL;
                defaultBaseUrl = DEFAULT_BASE_URL;
            }
            case "codex" -> {
                credential = CodexAuth.read(codexAuthFile, now);
                defaultModel = DEFAULT_CODEX_MODEL;
                defaultBaseUrl = DEFAULT_CODEX_BASE_URL;
            }
            default -> throw new IllegalArgumentException("KONACODE_AUTH must be key or codex, but was: " + auth);
        }
        String model = environment.getOrDefault("KONACODE_MODEL", defaultModel);
        return new OpenAiConfig(credential, model, environment.getOrDefault("KONACODE_JUDGE_MODEL", model),
                environment.getOrDefault("KONACODE_BASE_URL", defaultBaseUrl), DEFAULT_TIMEOUT);
    }

    /** The same credential, base URL and timeout, with the model the judge uses. */
    public OpenAiConfig forJudge() {
        return new OpenAiConfig(credential, judgeModel, judgeModel, baseUrl, timeout);
    }

    /**
     * The endpoint for one path. Built by string concatenation and not by {@link URI#resolve},
     * which treats the trailing {@code /v1} as a file and drops it.
     */
    public URI uri(String path) {
        return URI.create(baseUrl + path);
    }
}
