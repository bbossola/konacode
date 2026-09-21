package dev.konacode.llm.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

/**
 * Reads the file that {@code codex login} writes, once, at start.
 *
 * <p>It holds no refresh, no write and no network. The refresh token rotates, and the CLI writes
 * the new one back at once, so a refresh here must write the whole file back and must not race a
 * Codex process. That is a later change. Until then, a stale token names the one command that
 * repairs it.
 */
public final class CodexAuth {

    /** The sentence every refusal ends with, and the hint a 401 during a turn adds. */
    static final String RUN_LOGIN = " Run `codex login`, then start konacode again.";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CodexAuth() {
    }

    /** {@code $CODEX_HOME/auth.json}, or {@code ~/.codex/auth.json}, the way the CLI resolves it. */
    public static Path file(Map<String, String> environment, Path home) {
        String codexHome = environment.get("CODEX_HOME");
        Path folder = codexHome == null || codexHome.isBlank() ? home.resolve(".codex") : Path.of(codexHome.trim());
        return folder.resolve("auth.json");
    }

    /**
     * The token and the account id in the file.
     *
     * @throws IllegalArgumentException with one line, when the file is missing, is not a ChatGPT
     *         login, lacks a field, or holds a token whose {@code exp} claim is at or before {@code now}
     */
    public static CodexToken read(Path authFile, Instant now) {
        JsonNode root = parse(authFile);
        // The CLI infers chatgpt when the mode is absent, so this refuses a present other mode only.
        String mode = root.path("auth_mode").asText("chatgpt");
        if (!mode.equals("chatgpt")) {
            throw new IllegalArgumentException(authFile + " holds no ChatGPT login: auth_mode is " + mode + "." + RUN_LOGIN);
        }
        String token = root.path("tokens").path("access_token").asText("").strip();
        if (token.isBlank()) {
            throw new IllegalArgumentException(authFile + " holds no access token." + RUN_LOGIN);
        }
        String account = root.path("tokens").path("account_id").asText("");
        if (account.isBlank()) {
            throw new IllegalArgumentException(authFile + " holds no account id." + RUN_LOGIN);
        }
        Optional<Instant> expiry = expiry(token);
        if (expiry.isPresent() && !expiry.get().isAfter(now)) {
            throw new IllegalArgumentException("The Codex access token expired at " + expiry.get() + "." + RUN_LOGIN);
        }
        return new CodexToken(token, account);
    }

    private static JsonNode parse(Path authFile) {
        String text;
        try {
            text = Files.readString(authFile);
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read " + authFile + "." + RUN_LOGIN, e);
        }
        try {
            return MAPPER.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(authFile + " is not JSON." + RUN_LOGIN, e);
        }
    }

    /** The {@code exp} claim of a JWT. Empty when the token is not a JWT, or has no {@code exp}. */
    static Optional<Instant> expiry(String token) {
        String[] parts = token.split("\\.");
        if (parts.length < 2) {
            return Optional.empty();
        }
        try {
            JsonNode exp = MAPPER.readTree(Base64.getUrlDecoder().decode(parts[1])).path("exp");
            return exp.isNumber() ? Optional.of(Instant.ofEpochSecond(exp.asLong())) : Optional.empty();
        } catch (IllegalArgumentException | IOException e) {
            return Optional.empty();
        }
    }
}
