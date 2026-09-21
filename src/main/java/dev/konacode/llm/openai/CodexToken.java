package dev.konacode.llm.openai;

import java.util.Map;

/**
 * The token that {@code codex login} wrote, and the account it belongs to.
 *
 * <p>konacode names itself in {@code originator} and {@code User-Agent}. It never writes the name
 * of the Codex CLI. If the server refuses a client that is not Codex, that is the answer of the
 * provider, and konacode stops.
 */
public record CodexToken(String accessToken, String accountId) implements Credential {

    public CodexToken {
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalArgumentException("The Codex access token is blank.");
        }
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException("The Codex account id is blank.");
        }
    }

    @Override
    public Map<String, String> headers() {
        return Map.of("Authorization", "Bearer " + accessToken, "ChatGPT-Account-ID", accountId, "originator", "konacode", "User-Agent", "konacode");
    }

    /** A 401 on a Codex token has one repair, and the user reads it in the error and not in a log. */
    @Override
    public String hint(int status) {
        return status == 401 ? CodexAuth.RUN_LOGIN : "";
    }
}
