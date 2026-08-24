package dev.konacode.skills;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontMatterTest {

    private static final String GOOD = """
            ---
            name: commit-message
            description: Use when writing a commit message.
            ---

            Write the subject in the imperative.
            """;

    @Test
    void readsBothKeys() {
        FrontMatter parsed = FrontMatter.parse(GOOD);

        assertEquals("commit-message", parsed.name());
        assertEquals("Use when writing a commit message.", parsed.description());
    }

    @Test
    void returnsTheBodyThatFollowsTheSecondMarker() {
        FrontMatter parsed = FrontMatter.parse(GOOD);

        assertEquals("Write the subject in the imperative.", parsed.body().strip());
    }

    @Test
    void keepsAColonInsideAValue() {
        String text = """
                ---
                name: commit
                description: Use this: it helps.
                ---
                body
                """;

        assertEquals("Use this: it helps.", FrontMatter.parse(text).description());
    }

    @Test
    void reportsAMissingKeyByName() {
        String text = """
                ---
                name: commit
                ---
                body
                """;

        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> FrontMatter.parse(text));
        assertTrue(e.getMessage().contains("description"), e.getMessage());
    }

    @Test
    void reportsAMissingMarker() {
        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> FrontMatter.parse("no marker"));
        assertTrue(e.getMessage().contains("---"), e.getMessage());
    }
}
