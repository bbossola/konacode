package dev.konacode.agent;

import dev.konacode.llm.Message;
import dev.konacode.llm.Message.SystemMessage;
import dev.konacode.llm.Message.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConversationTest {

    @Test
    void keepsTheMessagesInTheOrderTheyArrive() {
        Conversation conversation = new Conversation(new SystemMessage("s"));
        conversation.add(new UserMessage("first"));
        conversation.add(new UserMessage("second"));

        assertEquals(3, conversation.messages().size());
        assertEquals(new UserMessage("second"), conversation.messages().get(2));
    }

    @Test
    void returnsACopyThatTheCallerCannotChange() {
        Conversation conversation = new Conversation(new SystemMessage("s"));

        assertThrows(UnsupportedOperationException.class,
                () -> conversation.messages().add(new UserMessage("x")));
    }

    @Test
    void restartRemovesEveryMessageAndAddsTheGivenOnes() {
        Conversation conversation = new Conversation(new SystemMessage("s"));
        conversation.add(new UserMessage("forget me"));

        conversation.restart(List.of(new SystemMessage("s")));

        assertEquals(List.of(new SystemMessage("s")), conversation.messages());
    }

    @Test
    void restartCopiesTheGivenListSoALaterChangeCannotReachIt() {
        Conversation conversation = new Conversation();
        List<Message> given = new ArrayList<>();
        given.add(new SystemMessage("s"));

        conversation.restart(given);
        given.clear();

        assertEquals(1, conversation.messages().size());
    }

    @Test
    void refusesANullMessage() {
        Conversation conversation = new Conversation();

        assertThrows(NullPointerException.class, () -> conversation.add(null));
    }
}
