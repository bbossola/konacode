package dev.konacode.llm.openai;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CredentialTest {

    @Test
    void aKeyGivesTheAuthorizationHeaderAndNoHint() {
        ApiKey key = new ApiKey("sk-test");

        assertEquals(Map.of("Authorization", "Bearer sk-test"), key.headers());
        assertEquals("", key.hint(401));
        assertEquals("", key.hint(500));
    }

    @Test
    void aKeyIsTrimmedAndABlankKeyIsRefused() {
        assertEquals("sk-test", new ApiKey(" sk-test\n").key());
        assertThrows(IllegalArgumentException.class, () -> new ApiKey("  "));
        assertThrows(IllegalArgumentException.class, () -> new ApiKey(null));
    }

    @Test
    void aCodexTokenGivesTheAccountIdAndNamesKonacode() {
        CodexToken token = new CodexToken("tok", "acct_1");

        assertEquals(Map.of("Authorization", "Bearer tok", "ChatGPT-Account-ID", "acct_1", "originator", "konacode", "User-Agent", "konacode"), token.headers());
    }

    @Test
    void aCodexTokenNamesTheCommandToRunOnA401Only() {
        CodexToken token = new CodexToken("tok", "acct_1");

        assertEquals(CodexAuth.RUN_LOGIN, token.hint(401));
        assertEquals("", token.hint(403));
        assertEquals("", token.hint(500));
    }

    @Test
    void aCodexTokenRefusesABlankTokenOrAccountId() {
        assertThrows(IllegalArgumentException.class, () -> new CodexToken(" ", "acct_1"));
        assertThrows(IllegalArgumentException.class, () -> new CodexToken("tok", " "));
    }
}
