package dev.konacode.llm.openai;

import dev.konacode.llm.http.ClientConfig;
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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiTest {

    @TempDir
    Path home;

    private static final Instant NOW = Instant.parse("2026-09-21T12:00:00Z");

    private OpenAi.Provider provider(Map<String, String> environment) {
        return OpenAi.fromEnvironment(environment, home, NOW);
    }

    private ClientConfig config(Map<String, String> environment) {
        return provider(environment).config();
    }

    /** A Codex login under the temporary home, with a token that expires one hour after NOW. */
    private void codexLogin() throws IOException {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String payload = encoder.encodeToString(("{\"exp\":" + NOW.plusSeconds(3600).getEpochSecond() + "}").getBytes(StandardCharsets.UTF_8));
        Path folder = Files.createDirectories(home.resolve(".codex"));
        Files.writeString(folder.resolve("auth.json"), "{\"auth_mode\":\"chatgpt\",\"tokens\":{\"access_token\":\"h." + payload + ".s\",\"account_id\":\"acct_1\"}}");
    }

    @Test
    void fillsInDefaultsForEverythingButTheKey() {
        ClientConfig config = config(Map.of("OPENAI_API_KEY", "sk-test"));

        assertEquals(new ApiKey("sk-test"), config.credential());
        assertEquals(OpenAi.DEFAULT_MODEL, config.model());
        assertEquals(OpenAi.DEFAULT_BASE_URL, config.baseUrl());
        assertEquals(ClientConfig.DEFAULT_TIMEOUT, config.timeout());
    }

    @Test
    void readsTheModelAndBaseUrlOverrides() {
        ClientConfig config = config(Map.of("OPENAI_API_KEY", "ollama", "KONACODE_MODEL", "qwen2.5-coder:32b", "KONACODE_BASE_URL", "http://localhost:11434/v1"));

        assertEquals("qwen2.5-coder:32b", config.model());
        assertEquals("http://localhost:11434/v1", config.baseUrl());
    }

    @Test
    void theJudgeModelDefaultsToTheMainModel() {
        assertEquals("gpt-5", config(Map.of("OPENAI_API_KEY", "k", "KONACODE_MODEL", "gpt-5")).forJudge().model());
    }

    @Test
    void theJudgeModelCanBeSetOnItsOwn() {
        ClientConfig config = config(Map.of("OPENAI_API_KEY", "k", "KONACODE_MODEL", "gpt-5", "KONACODE_JUDGE_MODEL", "gpt-5-mini"));

        assertEquals("gpt-5-mini", config.forJudge().model());
        assertEquals("gpt-5", config.model());
    }

    @Test
    void bothModelsFallBackToTheSameBuiltInDefault() {
        ClientConfig config = config(Map.of("OPENAI_API_KEY", "k"));

        assertEquals(OpenAi.DEFAULT_MODEL, config.model());
        assertEquals(OpenAi.DEFAULT_MODEL, config.forJudge().model());
    }

    @Test
    void acceptsAnyNonBlankKeySoLocalModelsWork() {
        // Validating the key's shape (an "sk-" prefix, say) would look like sensible input
        // validation and would break every Ollama user. Presence only.
        assertEquals(new ApiKey("ollama"), config(Map.of("OPENAI_API_KEY", "ollama")).credential());
    }

    @Test
    void rejectsAMissingOrBlankKeyAndNamesTheVariable() {
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class, () -> config(Map.of()));
        assertThrows(IllegalArgumentException.class, () -> config(Map.of("OPENAI_API_KEY", "   ")));

        assertTrue(missing.getMessage().contains("OPENAI_API_KEY"), missing.getMessage());
    }

    @Test
    void trimsSurroundingWhitespaceSoAKeyReadFromAFileStillWorks() {
        // "sk-test\n".isBlank() is false, so validation passes and the newline reaches
        // HttpRequest.header, which rejects it with an unchecked exception.
        ClientConfig config = config(Map.of("OPENAI_API_KEY", "sk-test\n", "KONACODE_MODEL", " gpt-5-mini ", "KONACODE_BASE_URL", " https://example.test/v1 "));

        assertEquals(new ApiKey("sk-test"), config.credential());
        assertEquals("gpt-5-mini", config.model());
        assertEquals("https://example.test/v1", config.baseUrl());
    }

    @Test
    void theWordKeyIsTheDefaultAuth() {
        assertEquals(config(Map.of("OPENAI_API_KEY", "sk-test")), config(Map.of("OPENAI_API_KEY", "sk-test", "KONACODE_AUTH", "key")));
    }

    @Test
    void aKeySpeaksChatCompletions() {
        assertInstanceOf(ChatCompletionsCodec.class, provider(Map.of("OPENAI_API_KEY", "sk-test")).codec());
    }

    @Test
    void codexReadsTheAuthFileUnderHomeAndTakesTheCodexDefaults() throws IOException {
        codexLogin();

        ClientConfig config = config(Map.of("KONACODE_AUTH", "codex"));

        assertEquals("acct_1", ((CodexToken) config.credential()).accountId());
        assertEquals(OpenAi.DEFAULT_CODEX_MODEL, config.model());
        assertEquals(OpenAi.DEFAULT_CODEX_MODEL, config.forJudge().model());
        assertEquals(OpenAi.DEFAULT_CODEX_BASE_URL, config.baseUrl());
        assertEquals("https://chatgpt.com/backend-api/codex/responses", config.uri("/responses").toString());
    }

    @Test
    void codexSpeaksTheResponsesApi() throws IOException {
        codexLogin();

        assertInstanceOf(ResponsesCodec.class, provider(Map.of("KONACODE_AUTH", "codex")).codec());
    }

    @Test
    void codexKeepsTheModelAndBaseUrlOverrides() throws IOException {
        codexLogin();

        ClientConfig config = config(Map.of("KONACODE_AUTH", "codex", "KONACODE_MODEL", "gpt-5.4", "KONACODE_BASE_URL", "https://example.test/codex/"));

        assertEquals("gpt-5.4", config.model());
        assertEquals("https://example.test/codex", config.baseUrl());
    }

    @Test
    void codexIgnoresAKeyThatIsAlsoSet() throws IOException {
        codexLogin();

        assertInstanceOf(CodexToken.class, config(Map.of("KONACODE_AUTH", "codex", "OPENAI_API_KEY", "sk-test")).credential());
    }

    @Test
    void codexHonoursCodexHome() throws IOException {
        Path elsewhere = Files.createDirectories(home.resolve("elsewhere"));
        Files.writeString(elsewhere.resolve("auth.json"), "{\"auth_mode\":\"chatgpt\",\"tokens\":{\"access_token\":\"opaque\",\"account_id\":\"acct_2\"}}");

        ClientConfig config = config(Map.of("KONACODE_AUTH", "codex", "CODEX_HOME", elsewhere.toString()));

        assertEquals("acct_2", ((CodexToken) config.credential()).accountId());
    }

    @Test
    void codexWithNoLoginFailsWithTheCodexSentence() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> config(Map.of("KONACODE_AUTH", "codex")));

        assertTrue(thrown.getMessage().endsWith("Run `codex login`, then start konacode again."), thrown.getMessage());
    }

    @Test
    void anUnknownAuthWordFailsAndNamesTheTwoWords() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> config(Map.of("OPENAI_API_KEY", "sk-test", "KONACODE_AUTH", "oauth")));

        assertTrue(thrown.getMessage().contains("KONACODE_AUTH"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("key or codex"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("oauth"), thrown.getMessage());
    }

    @Test
    void theAuthWordIgnoresCaseAndSpaces() throws IOException {
        codexLogin();

        assertInstanceOf(CodexToken.class, config(Map.of("KONACODE_AUTH", " Codex ")).credential());
    }
}
