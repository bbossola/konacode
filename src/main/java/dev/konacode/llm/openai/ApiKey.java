package dev.konacode.llm.openai;

import dev.konacode.llm.http.Credential;

import java.util.Map;

/**
 * A key from {@code OPENAI_API_KEY}. Checked for presence and nothing else: a local server that
 * ignores the key still needs one, and a shape check such as an {@code sk-} prefix would break
 * every one of them.
 */
public record ApiKey(String key) implements Credential {

    public ApiKey {
        key = key == null ? null : key.trim();
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("The API key is blank.");
        }
    }

    @Override
    public Map<String, String> headers() {
        return Map.of("Authorization", "Bearer " + key);
    }

    @Override
    public String hint(int status) {
        return "";
    }
}
