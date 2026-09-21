package dev.konacode.llm.http;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/**
 * The settings of one transport: as whom, which model, where, and for how long.
 *
 * <p>It reads no environment variable. A provider reads them, so this record names no provider
 * and no default.
 *
 * <p>The judge model sits beside the model, because the judge speaks to the same endpoint with the
 * same credential and only the model name differs.
 */
public record ClientConfig(Credential credential, String model, String judgeModel, String baseUrl, Duration timeout) {

    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(2);

    public ClientConfig {
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
    public ClientConfig forJudge() {
        return new ClientConfig(credential, judgeModel, judgeModel, baseUrl, timeout);
    }

    /**
     * The endpoint for one path. Built by string concatenation and not by {@link URI#resolve},
     * which treats the trailing {@code /v1} as a file and drops it.
     */
    public URI uri(String path) {
        return URI.create(baseUrl + path);
    }
}
