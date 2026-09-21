package dev.konacode.llm.openai;

/**
 * What konacode sends to prove who it is. Sealed, so a third kind is a compile error at the switch
 * that writes the headers.
 */
public sealed interface Credential {

    /**
     * A key from {@code OPENAI_API_KEY}. Checked for presence and nothing else: a local server that
     * ignores the key still needs one, and a shape check such as an {@code sk-} prefix would break
     * every one of them.
     */
    record ApiKey(String key) implements Credential {

        public ApiKey {
            key = key == null ? null : key.trim();
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("OPENAI_API_KEY is not set.");
            }
        }
    }

    /** The token that {@code codex login} wrote, and the account it belongs to. */
    record CodexToken(String accessToken, String accountId) implements Credential {

        public CodexToken {
            if (accessToken == null || accessToken.isBlank()) {
                throw new IllegalArgumentException("The Codex access token is blank.");
            }
            if (accountId == null || accountId.isBlank()) {
                throw new IllegalArgumentException("The Codex account id is blank.");
            }
        }
    }
}
