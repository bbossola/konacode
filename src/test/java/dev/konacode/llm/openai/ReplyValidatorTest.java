package dev.konacode.llm.openai;

import dev.konacode.llm.Message.AssistantMessage;
import dev.konacode.llm.ToolCall;
import dev.konacode.llm.ToolSpec;
import dev.konacode.tools.Schemas;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplyValidatorTest {

    private static final List<ToolSpec> ADVERTISED = List.of(
            new ToolSpec("list_files", "List files.", Schemas.object().build()),
            new ToolSpec("read_file", "Read a file.", Schemas.object().build()));

    private static ReplyValidator validator() {
        return ReplyValidator.create("qwen3-coder", ADVERTISED);
    }

    private static AssistantMessage text(String body) {
        return new AssistantMessage(body, List.of());
    }

    // --- refuses -----------------------------------------------------------------------

    @Test
    void refusesNarrationFollowedByATrailingFunctionTag() {
        // Exactly what qwen3-coder produced. An earlier draft of the rule required the whole
        // text to be the blob, which would have caught nothing: the narration comes first.
        AssistantMessage reply = text("""
                I'll help you add a second line to sample.txt. First, I need to check if the \
                file exists and then I'll edit it.

                <function=list_files>
                </function>
                </tool_call>""");

        assertFalse(validator().accepts(reply));
    }

    @Test
    void refusesABareFunctionTagWithNothingElse() {
        assertFalse(validator().accepts(text("<function=read_file>\n</function>")));
    }

    @Test
    void refusesATrailingToolCallBlock() {
        AssistantMessage reply = text("""
                Let me look at that file.

                <tool_call>
                {"name": "read_file", "arguments": {"path": "pom.xml"}}
                </tool_call>""");

        assertFalse(validator().accepts(reply));
    }

    // --- accepts -----------------------------------------------------------------------

    @Test
    void acceptsAReplyThatCarriesRealToolCallsEvenIfItsTextMentionsOne() {
        AssistantMessage reply = new AssistantMessage(
                "Calling <function=list_files> now.",
                List.of(new ToolCall("c1", "list_files", "{}")));

        assertTrue(validator().accepts(reply));
    }

    @Test
    void acceptsAMentionThatTheModelKeepsTalkingAfter() {
        // konacode reads its own repo, so the model quotes these formats legitimately.
        AssistantMessage reply = text(
                "The codec turns <function=list_files> style text into a tool_calls array, "
                        + "which is what the provider actually expects.");

        assertTrue(validator().accepts(reply));
    }

    @Test
    void acceptsAConstructInsideAFencedCodeBlock() {
        AssistantMessage reply = text("""
                Some models emit this instead:

                ```
                <function=list_files>
                </function>
                ```""");

        assertTrue(validator().accepts(reply));
    }

    @Test
    void acceptsATrailingConstructNamingAToolWeDidNotAdvertise() {
        assertTrue(validator().accepts(text("<function=deploy>\n</function>")));
    }

    @Test
    void acceptsOrdinaryProse() {
        assertTrue(validator().accepts(text("There are two files here: pom.xml and README.md.")));
    }

    @Test
    void acceptsBlankText() {
        assertTrue(validator().accepts(text("   ")));
    }

    // --- budget ------------------------------------------------------------------------

    @Test
    void acceptsTheSecondGarbledReplyBecauseTheBudgetIsSpent() {
        ReplyValidator validator = validator();
        AssistantMessage garbled = text("<function=list_files>\n</function>");

        assertFalse(validator.accepts(garbled), "first should be refused");
        assertTrue(validator.accepts(garbled), "second should be accepted - budget spent");
    }

    @Test
    void neverRefusesWhenTheBudgetIsZero() {
        ReplyValidator validator = new ReplyValidator(ADVERTISED, 0);

        assertTrue(validator.accepts(text("<function=list_files>\n</function>")));
    }

    @Test
    void rejectsANegativeBudget() {
        assertThrows(IllegalArgumentException.class, () -> new ReplyValidator(ADVERTISED, -1));
    }
}
