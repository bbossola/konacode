package dev.konacode.llm.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.konacode.llm.http.ClientConfig;
import dev.konacode.llm.http.Codec;
import dev.konacode.llm.http.Credential;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;

/**
 * What OpenAI serves, read from the environment. {@code KONACODE_AUTH} picks the credential, and
 * the credential picks the defaults and the wire format: a key speaks Chat Completions at
 * {@code https://api.openai.com/v1}, and a Codex token speaks the Responses API of the Codex
 * backend.
 */
public final class OpenAi {

    public static final String DEFAULT_MODEL = "gpt-5-mini";
    public static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";
    /** On the bundled list of the CLI, and one of the two models that take the plain Responses shape. */
    public static final String DEFAULT_CODEX_MODEL = "gpt-5.5";
    public static final String DEFAULT_CODEX_BASE_URL = "https://chatgpt.com/backend-api/codex";

    /** The config and the codec for one process. The credential decides both. */
    public record Provider(ClientConfig config, Codec codec) {
    }

    private OpenAi() {
    }

    public static Provider fromEnvironment(Map<String, String> environment, Path home) {
        return fromEnvironment(environment, home, Instant.now());
    }

    /** {@code now} is a parameter, so a test can hold a token that expires at a known time. */
    static Provider fromEnvironment(Map<String, String> environment, Path home, Instant now) {
        ObjectMapper mapper = new ObjectMapper();
        String auth = environment.getOrDefault("KONACODE_AUTH", "key");
        Credential credential;
        Codec codec;
        String defaultModel;
        String defaultBaseUrl;
        switch (auth.trim().toLowerCase(Locale.ROOT)) {
            case "key" -> {
                String key = environment.get("OPENAI_API_KEY");
                if (key == null || key.isBlank()) {
                    throw new IllegalArgumentException("OPENAI_API_KEY is not set.");
                }
                credential = new ApiKey(key);
                codec = new ChatCompletionsCodec(mapper);
                defaultModel = DEFAULT_MODEL;
                defaultBaseUrl = DEFAULT_BASE_URL;
            }
            case "codex" -> {
                credential = CodexAuth.read(CodexAuth.file(environment, home), now);
                codec = new ResponsesCodec(mapper);
                defaultModel = DEFAULT_CODEX_MODEL;
                defaultBaseUrl = DEFAULT_CODEX_BASE_URL;
            }
            default -> throw new IllegalArgumentException("KONACODE_AUTH must be key or codex, but was: " + auth);
        }
        String model = environment.getOrDefault("KONACODE_MODEL", defaultModel);
        ClientConfig config = new ClientConfig(credential, model, environment.getOrDefault("KONACODE_JUDGE_MODEL", model),
                environment.getOrDefault("KONACODE_BASE_URL", defaultBaseUrl), ClientConfig.DEFAULT_TIMEOUT);
        return new Provider(config, codec);
    }
}
