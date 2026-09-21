package dev.konacode.llm.openai;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/**
 * The settings of one transport: as whom, which model, where, and for how long.
 *
 * <p>It reads no environment variable. {@link OpenAi} reads them, so this record names no provider
 * and no default.
 *
 * <p>The judge model sits beside the model, because the judge speaks to the same endpoint with the
 * same credential and only the model name differs.
 */
public record OpenAiConfig(Credential credential, String model, String judgeModel, String baseUrl, Duration timeout) {

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
