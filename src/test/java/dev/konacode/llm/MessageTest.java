package dev.konacode.llm;

import dev.konacode.llm.Message.AssistantMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageTest {

    @Test
    void assistantMessageNormalizesNullsSoCallersNeverCheckThem() {
        AssistantMessage message = new AssistantMessage(null, null);

        assertEquals("", message.text());
        assertEquals(List.of(), message.toolCalls());
        assertFalse(message.hasToolCalls());
    }

    @Test
    void assistantMessageReportsWhenItCarriesToolCalls() {
        AssistantMessage message = new AssistantMessage(
                "", List.of(new ToolCall("call_1", "read_file", "{\"path\":\"a.txt\"}")));

        assertTrue(message.hasToolCalls());
    }

    @Test
    void assistantMessageDefensivelyCopiesItsToolCalls() {
        List<ToolCall> mutable = new ArrayList<>();
        mutable.add(new ToolCall("call_1", "read_file", "{}"));

        AssistantMessage message = new AssistantMessage("", mutable);
        mutable.clear();

        assertEquals(1, message.toolCalls().size());
        assertThrows(UnsupportedOperationException.class,
                () -> message.toolCalls().add(new ToolCall("call_2", "x", "{}")));
    }
}
