package dev.konacode.llm.openai;

import dev.konacode.llm.openai.Credential.CodexToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodexAuthTest {

    @TempDir
    Path home;

    private static final Instant NOW = Instant.parse("2026-09-21T12:00:00Z");

    /** A JWT with the given payload. The signature is not checked, so any third part serves. */
    private static String jwt(String payloadJson) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String header = encoder.encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = encoder.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".signature";
    }

    private static String tokenExpiringAt(Instant exp) {
        return jwt("{\"exp\":" + exp.getEpochSecond() + ",\"sub\":\"user\"}");
    }

    private Path write(String json) throws IOException {
        Path file = home.resolve("auth.json");
        Files.writeString(file, json);
        return file;
    }

    private static String authJson(String mode, String accessToken, String accountId) {
        return """
                {
                  "auth_mode": %s,
                  "OPENAI_API_KEY": null,
                  "tokens": {
                    "id_token": "x.y.z",
                    "access_token": %s,
                    "refresh_token": "rt-1",
                    "account_id": %s
                  },
                  "last_refresh": "2026-09-20T10:00:00Z"
                }
                """.formatted(mode, accessToken, accountId);
    }

    private static String quoted(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    @Test
    void readsTheTokenAndTheAccountId() throws IOException {
        String token = tokenExpiringAt(NOW.plusSeconds(3600));
        Path file = write(authJson("\"chatgpt\"", quoted(token), "\"acct_1\""));

        CodexToken read = CodexAuth.read(file, NOW);

        assertEquals(new CodexToken(token, "acct_1"), read);
    }

    @Test
    void acceptsAFileWithNoAuthModeBecauseTheCliInfersChatgptFromTheTokens() throws IOException {
        String token = tokenExpiringAt(NOW.plusSeconds(3600));
        Path file = write("{\"tokens\":{\"access_token\":\"" + token + "\",\"account_id\":\"acct_1\"}}");

        assertEquals(new CodexToken(token, "acct_1"), CodexAuth.read(file, NOW));
    }

    @Test
    void refusesAMissingFileAndNamesTheCommandToRun() {
        Path missing = home.resolve("nowhere").resolve("auth.json");

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> CodexAuth.read(missing, NOW));

        assertTrue(thrown.getMessage().contains(missing.toString()), thrown.getMessage());
        assertTrue(thrown.getMessage().endsWith("Run `codex login`, then start konacode again."), thrown.getMessage());
    }

    @Test
    void refusesAFileThatIsNotJson() throws IOException {
        Path file = write("not json");

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> CodexAuth.read(file, NOW));

        assertTrue(thrown.getMessage().contains("not JSON"), thrown.getMessage());
    }

    @Test
    void refusesAnApiKeyLogin() throws IOException {
        Path file = write(authJson("\"apikey\"", quoted(tokenExpiringAt(NOW.plusSeconds(60))), "\"acct_1\""));

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> CodexAuth.read(file, NOW));

        assertTrue(thrown.getMessage().contains("apikey"), thrown.getMessage());
    }

    @Test
    void refusesAMissingAccessToken() throws IOException {
        Path file = write(authJson("\"chatgpt\"", "null", "\"acct_1\""));

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> CodexAuth.read(file, NOW));

        assertTrue(thrown.getMessage().contains("access token"), thrown.getMessage());
    }

    @Test
    void refusesAMissingAccountId() throws IOException {
        Path file = write(authJson("\"chatgpt\"", quoted(tokenExpiringAt(NOW.plusSeconds(60))), "null"));

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> CodexAuth.read(file, NOW));

        assertTrue(thrown.getMessage().contains("account id"), thrown.getMessage());
    }

    @Test
    void refusesAnExpiredToken() throws IOException {
        Path file = write(authJson("\"chatgpt\"", quoted(tokenExpiringAt(NOW.minusSeconds(1))), "\"acct_1\""));

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> CodexAuth.read(file, NOW));

        assertTrue(thrown.getMessage().contains("expired"), thrown.getMessage());
        assertTrue(thrown.getMessage().endsWith("Run `codex login`, then start konacode again."), thrown.getMessage());
    }

    @Test
    void aTokenThatExpiresExactlyNowIsExpired() throws IOException {
        Path file = write(authJson("\"chatgpt\"", quoted(tokenExpiringAt(NOW)), "\"acct_1\""));

        assertThrows(IllegalArgumentException.class, () -> CodexAuth.read(file, NOW));
    }

    @Test
    void aTokenWithNoExpClaimIsNotChecked() throws IOException {
        String token = jwt("{\"sub\":\"user\"}");
        Path file = write(authJson("\"chatgpt\"", quoted(token), "\"acct_1\""));

        assertEquals(new CodexToken(token, "acct_1"), CodexAuth.read(file, NOW));
    }

    @Test
    void aTokenThatIsNotAJwtIsNotChecked() throws IOException {
        Path file = write(authJson("\"chatgpt\"", "\"opaque-token\"", "\"acct_1\""));

        assertEquals(new CodexToken("opaque-token", "acct_1"), CodexAuth.read(file, NOW));
    }

    @Test
    void expiryReadsTheExpClaim() {
        assertEquals(Optional.of(NOW), CodexAuth.expiry(tokenExpiringAt(NOW)));
        assertEquals(Optional.empty(), CodexAuth.expiry("opaque"));
        assertEquals(Optional.empty(), CodexAuth.expiry("a.!!!.c"));
    }

    @Test
    void theFileIsUnderCodexHomeWhenSetAndUnderTheHomeFolderOtherwise() {
        assertEquals(home.resolve(".codex").resolve("auth.json"), CodexAuth.file(Map.of(), home));
        assertEquals(Path.of("/opt/codex/auth.json"), CodexAuth.file(Map.of("CODEX_HOME", "/opt/codex"), home));
        assertEquals(home.resolve(".codex").resolve("auth.json"), CodexAuth.file(Map.of("CODEX_HOME", "  "), home));
    }
}
