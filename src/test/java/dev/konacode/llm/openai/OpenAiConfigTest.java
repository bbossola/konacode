package dev.konacode.llm.openai;

import dev.konacode.llm.openai.Credential.ApiKey;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiConfigTest {

    @TempDir
    Path home;

    private static final Instant NOW = Instant.parse("2026-09-21T12:00:00Z");

    /** No file lives here, so a test with a key never touches the disk. */
    private static final Path NO_FILE = Path.of("/nonexistent/auth.json");

    private static OpenAiConfig config(Map<String, String> environment) {
        return OpenAiConfig.fromEnvironment(environment, NO_FILE, NOW);
    }

    /** A Codex login whose token expires one hour after NOW. */
    private Path codexLogin() throws IOException {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String payload = encoder.encodeToString(("{\"exp\":" + NOW.plusSeconds(3600).getEpochSecond() + "}").getBytes(StandardCharsets.UTF_8));
        String token = "h." + payload + ".s";
        Path file = home.resolve("auth.json");
        Files.writeString(file, "{\"auth_mode\":\"chatgpt\",\"tokens\":{\"access_token\":\"" + token + "\",\"account_id\":\"acct_1\"}}");
        return file;
    }

    @Test
    void fillsInDefaultsForEverythingButTheKey() {
        OpenAiConfig config = config(Map.of("OPENAI_API_KEY", "sk-test"));

        assertEquals(new ApiKey("sk-test"), config.credential());
        assertEquals(OpenAiConfig.DEFAULT_MODEL, config.model());
        assertEquals(OpenAiConfig.DEFAULT_BASE_URL, config.baseUrl());
    }

    @Test
    void readsTheModelAndBaseUrlOverrides() {
        OpenAiConfig config = config(Map.of(
                "OPENAI_API_KEY", "ollama",
                "KONACODE_MODEL", "qwen2.5-coder:32b",
                "KONACODE_BASE_URL", "http://localhost:11434/v1"));

        assertEquals("qwen2.5-coder:32b", config.model());
        assertEquals("http://localhost:11434/v1", config.baseUrl());
    }

    @Test
    void theJudgeModelDefaultsToTheMainModel() {
        OpenAiConfig config = config(Map.of("OPENAI_API_KEY", "k", "KONACODE_MODEL", "gpt-5"));

        assertEquals("gpt-5", config.forJudge().model());
    }

    @Test
    void theJudgeModelCanBeSetOnItsOwn() {
        OpenAiConfig config = config(Map.of("OPENAI_API_KEY", "k", "KONACODE_MODEL", "gpt-5", "KONACODE_JUDGE_MODEL", "gpt-5-mini"));

        assertEquals("gpt-5-mini", config.forJudge().model());
        assertEquals("gpt-5", config.model());
    }

    @Test
    void theJudgeTalksToTheSameEndpointWithTheSameCredential() {
        OpenAiConfig config = config(Map.of(
                "OPENAI_API_KEY", "sk-test",
                "KONACODE_MODEL", "gpt-5",
                "KONACODE_JUDGE_MODEL", "gpt-5-mini",
                "KONACODE_BASE_URL", "https://example.test/v1"));

        OpenAiConfig judge = config.forJudge();

        assertEquals(config.credential(), judge.credential());
        assertEquals(config.baseUrl(), judge.baseUrl());
        assertEquals(config.timeout(), judge.timeout());
    }

    @Test
    void bothModelsFallBackToTheSameBuiltInDefault() {
        OpenAiConfig config = config(Map.of("OPENAI_API_KEY", "k"));

        assertEquals(OpenAiConfig.DEFAULT_MODEL, config.model());
        assertEquals(OpenAiConfig.DEFAULT_MODEL, config.forJudge().model());
    }

    @Test
    void acceptsAnyNonBlankKeySoLocalModelsWork() {
        // Validating the key's shape (an "sk-" prefix, say) would look like sensible input
        // validation and would break every Ollama user. Presence only.
        OpenAiConfig config = config(Map.of("OPENAI_API_KEY", "ollama"));

        assertEquals(new ApiKey("ollama"), config.credential());
    }

    @Test
    void rejectsAMissingOrBlankKey() {
        assertThrows(IllegalArgumentException.class, () -> config(Map.of()));
        assertThrows(IllegalArgumentException.class, () -> config(Map.of("OPENAI_API_KEY", "   ")));
    }

    @Test
    void buildsTheEndpointWithoutLosingTheApiVersionSegment() {
        OpenAiConfig withSlash = config(Map.of("OPENAI_API_KEY", "k", "KONACODE_BASE_URL", "https://example.test/v1/"));
        OpenAiConfig withoutSlash = config(Map.of("OPENAI_API_KEY", "k", "KONACODE_BASE_URL", "https://example.test/v1"));

        // URI.resolve would drop the /v1 segment here, which is why the URI is built by hand.
        assertEquals("https://example.test/v1/chat/completions", withSlash.uri("/chat/completions").toString());
        assertEquals("https://example.test/v1/chat/completions", withoutSlash.uri("/chat/completions").toString());
    }

    @Test
    void errorMessageNamesTheVariableTheUserMustSet() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> config(Map.of()));

        assertTrue(thrown.getMessage().contains("OPENAI_API_KEY"), thrown.getMessage());
    }

    @Test
    void trimsSurroundingWhitespaceSoAKeyReadFromAFileStillWorks() {
        // "sk-test\n".isBlank() is false, so validation passes and the newline reaches
        // HttpRequest.header, which rejects it with an unchecked exception.
        OpenAiConfig config = config(Map.of(
                "OPENAI_API_KEY", "sk-test\n",
                "KONACODE_MODEL", " gpt-5-mini ",
                "KONACODE_BASE_URL", " https://example.test/v1 "));

        assertEquals(new ApiKey("sk-test"), config.credential());
        assertEquals("gpt-5-mini", config.model());
        assertEquals("https://example.test/v1", config.baseUrl());
    }

    @Test
    void theWordKeyIsTheDefaultAuth() {
        OpenAiConfig implicit = config(Map.of("OPENAI_API_KEY", "sk-test"));
        OpenAiConfig explicit = config(Map.of("OPENAI_API_KEY", "sk-test", "KONACODE_AUTH", "key"));

        assertEquals(implicit, explicit);
    }

    @Test
    void codexReadsTheAuthFileAndTakesTheCodexDefaults() throws IOException {
        OpenAiConfig config = OpenAiConfig.fromEnvironment(Map.of("KONACODE_AUTH", "codex"), codexLogin(), NOW);

        assertEquals("acct_1", ((CodexToken) config.credential()).accountId());
        assertEquals(OpenAiConfig.DEFAULT_CODEX_MODEL, config.model());
        assertEquals(OpenAiConfig.DEFAULT_CODEX_MODEL, config.forJudge().model());
        assertEquals(OpenAiConfig.DEFAULT_CODEX_BASE_URL, config.baseUrl());
        assertEquals("https://chatgpt.com/backend-api/codex/responses", config.uri("/responses").toString());
    }

    @Test
    void codexKeepsTheModelAndBaseUrlOverrides() throws IOException {
        Map<String, String> environment = Map.of("KONACODE_AUTH", "codex", "KONACODE_MODEL", "gpt-5.4", "KONACODE_BASE_URL", "https://example.test/codex/");

        OpenAiConfig config = OpenAiConfig.fromEnvironment(environment, codexLogin(), NOW);

        assertEquals("gpt-5.4", config.model());
        assertEquals("https://example.test/codex", config.baseUrl());
    }

    @Test
    void codexIgnoresAKeyThatIsAlsoSet() throws IOException {
        Map<String, String> environment = Map.of("KONACODE_AUTH", "codex", "OPENAI_API_KEY", "sk-test");

        OpenAiConfig config = OpenAiConfig.fromEnvironment(environment, codexLogin(), NOW);

        assertTrue(config.credential() instanceof CodexToken, config.credential().toString());
    }

    @Test
    void codexWithNoLoginFailsWithTheCodexSentence() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> OpenAiConfig.fromEnvironment(Map.of("KONACODE_AUTH", "codex"), NO_FILE, NOW));

        assertTrue(thrown.getMessage().endsWith("Run `codex login`, then start konacode again."), thrown.getMessage());
    }

    @Test
    void anUnknownAuthWordFailsAndNamesTheTwoWords() {
        Map<String, String> environment = Map.of("OPENAI_API_KEY", "sk-test", "KONACODE_AUTH", "oauth");

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> config(environment));

        assertTrue(thrown.getMessage().contains("KONACODE_AUTH"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("key or codex"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("oauth"), thrown.getMessage());
    }

    @Test
    void theAuthWordIgnoresCaseAndSpaces() throws IOException {
        OpenAiConfig config = OpenAiConfig.fromEnvironment(Map.of("KONACODE_AUTH", " Codex "), codexLogin(), NOW);

        assertTrue(config.credential() instanceof CodexToken, config.credential().toString());
    }
}
