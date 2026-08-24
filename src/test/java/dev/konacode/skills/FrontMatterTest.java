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

        assertEquals("Write the subject in the imperative.", parsed.body());
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

    @Test
    void reportsAHeaderThatNeverCloses() {
        String text = "---\nname: commit\ndescription: Use it.\nbody with no marker\n";

        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> FrontMatter.parse(text));
        assertTrue(e.getMessage().contains("closing"), e.getMessage());
    }

    @Test
    void reportsAMissingName() {
        String text = "---\ndescription: Use it.\n---\nbody\n";

        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> FrontMatter.parse(text));
        assertTrue(e.getMessage().contains("name"), e.getMessage());
    }

    @Test
    void skipsANestedBlockUnderAKeyWithNoValue() {
        String text = """
                ---
                name: security-audit
                description: Use it.
                metadata:
                  short-description: Audit the dependencies.
                  version: 1.0.2
                ---
                The body.
                """;

        FrontMatter parsed = FrontMatter.parse(text);

        assertEquals("security-audit", parsed.name());
        assertEquals("Use it.", parsed.description());
    }

    @Test
    void reportsAValueThatContinuesOnASecondLine() {
        String text = "---\nname: commit\ndescription: Use when writing\n  a commit message.\n---\nbody\n";

        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> FrontMatter.parse(text));
        assertTrue(e.getMessage().contains("one line"), e.getMessage());
    }

    @Test
    void reportsAQuotedValue() {
        String text = "---\nname: commit\ndescription: \"Use it.\"\n---\nbody\n";

        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> FrontMatter.parse(text));
        assertTrue(e.getMessage().contains("quot"), e.getMessage());
    }

    @Test
    void reportsAFoldedValue() {
        String text = "---\nname: commit\ndescription: >\n  Use it.\n---\nbody\n";

        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> FrontMatter.parse(text));
        assertTrue(e.getMessage().contains("folded"), e.getMessage());
    }

    @Test
    void reportsAValueFoldedWithAPipe() {
        String text = "---\nname: commit\ndescription: |\n  Use it.\n---\nbody\n";

        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> FrontMatter.parse(text));
        assertTrue(e.getMessage().contains("folded"), e.getMessage());
    }

    @Test
    void reportsASingleQuotedValue() {
        String text = "---\nname: commit\ndescription: 'Use it.'\n---\nbody\n";

        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> FrontMatter.parse(text));
        assertTrue(e.getMessage().contains("quot"), e.getMessage());
    }

    @Test
    void reportsAContinuationLineThatHoldsAColon() {
        String text = "---\nname: commit\ndescription: See the guide at\n  https://example.com/x\n---\nbody\n";

        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> FrontMatter.parse(text));
        assertTrue(e.getMessage().contains("one line"), e.getMessage());
    }

    @Test
    void allowsABlankLineInsideTheHeader() {
        String text = "---\nname: commit\n\ndescription: Use it.\n---\nbody\n";

        assertEquals("Use it.", FrontMatter.parse(text).description());
    }

    @Test
    void reportsAnEmptyValue() {
        String text = "---\nname:\ndescription: Use it.\n---\nbody\n";

        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> FrontMatter.parse(text));
        assertTrue(e.getMessage().contains("empty"), e.getMessage());
    }

    @Test
    void readsAFileWithAWindowsLineEnding() {
        String text = "---\r\nname: commit\r\ndescription: Use it.\r\n---\r\nThe body.\r\n";

        FrontMatter parsed = FrontMatter.parse(text);

        assertEquals("commit", parsed.name());
        assertEquals("Use it.", parsed.description());
        assertEquals("The body.", parsed.body());
    }

    @Test
    void reportsAFileWithNoTextBelowTheHeader() {
        String text = "---\nname: commit\ndescription: Use it.\n---\n";

        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> FrontMatter.parse(text));
        assertTrue(e.getMessage().contains("no text"), e.getMessage());
    }

    @Test
    void keepsTheIndentOfTheFirstLineOfTheBody() {
        String text = "---\nname: a\ndescription: b\n---\n\n    def build():\n        return 1\n";

        assertEquals("    def build():\n        return 1", FrontMatter.parse(text).body());
    }

    @Test
    void keepsAMarkerInsideTheBody() {
        String text = "---\nname: commit\ndescription: Use it.\n---\nOne.\n---\nTwo.\n";

        assertEquals("One.\n---\nTwo.", FrontMatter.parse(text).body());
    }

    @Test
    void takesTheLastValueWhenAKeyRepeats() {
        String text = "---\nname: commit\ndescription: First.\ndescription: Second.\n---\nbody\n";

        assertEquals("Second.", FrontMatter.parse(text).description());
    }
}
