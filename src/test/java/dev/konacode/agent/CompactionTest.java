package dev.konacode.agent;

import dev.konacode.llm.LlmException;
import dev.konacode.llm.Message;
import dev.konacode.llm.Message.AssistantMessage;
import dev.konacode.llm.Message.SystemMessage;
import dev.konacode.llm.Message.ToolMessage;
import dev.konacode.llm.Message.UserMessage;
import dev.konacode.llm.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompactionTest {

    private static final SystemMessage SYSTEM = new SystemMessage("You are konacode.");

    /** A conversation with one turn that called a tool, so the history holds every kind of message. */
    private static Conversation conversationWithOneTurn() {
        Conversation conversation = new Conversation(SYSTEM);
        conversation.add(new UserMessage("read pom.xml"));
        conversation.add(new AssistantMessage("", List.of(new ToolCall("c1", "read_file", "{\"path\":\"pom.xml\"}"))));
        conversation.add(new ToolMessage("c1", "<project/>"));
        conversation.add(new AssistantMessage("It is a Maven project.", List.of()));
        return conversation;
    }

    @Test
    void sendsTheWholeHistoryThenThePromptAndNoTool() {
        Conversation conversation = conversationWithOneTurn();
        List<Message> before = conversation.messages();
        FakeLlmClient client = new FakeLlmClient().replyText("The user read pom.xml.");

        new Compaction(client, SYSTEM, conversation, new Cancellation()).compact();

        List<Message> sent = client.receivedHistories().get(0);
        assertEquals(before, sent.subList(0, before.size()));
        assertEquals(new UserMessage(Compaction.PROMPT), sent.get(sent.size() - 1));
        assertEquals(List.of(), client.receivedTools().get(0));
    }

    @Test
    void restartsTheConversationWithTheSystemMessageTheFramedSummaryAndTheAcknowledgement() {
        Conversation conversation = conversationWithOneTurn();
        FakeLlmClient client = new FakeLlmClient().replyText("The user read pom.xml.\n");

        Optional<String> summary = new Compaction(client, SYSTEM, conversation, new Cancellation()).compact();

        assertEquals(Optional.of("The user read pom.xml."), summary);
        List<Message> expected = List.of(SYSTEM, new UserMessage(Compaction.FRAME + "The user read pom.xml."), new AssistantMessage(Compaction.ACKNOWLEDGEMENT, List.of()));
        assertEquals(expected, conversation.messages());
    }

    @Test
    void aFailureReachesTheCallerAndLeavesTheConversationAsItWas() {
        Conversation conversation = conversationWithOneTurn();
        List<Message> before = conversation.messages();
        FakeLlmClient client = new FakeLlmClient().failWith(new LlmException("HTTP 500"));
        Compaction compaction = new Compaction(client, SYSTEM, conversation, new Cancellation());

        LlmException thrown = assertThrows(LlmException.class, compaction::compact);

        assertEquals("HTTP 500", thrown.getMessage());
        assertEquals(before, conversation.messages());
    }

    @Test
    void aConversationWithTheSystemMessageOnlyGetsNoRequest() {
        Conversation conversation = new Conversation(SYSTEM);
        FakeLlmClient client = new FakeLlmClient().replyText("never sent");

        Optional<String> summary = new Compaction(client, SYSTEM, conversation, new Cancellation()).compact();

        assertEquals(Optional.empty(), summary);
        assertEquals(List.of(), client.receivedHistories());
        assertEquals(List.of(SYSTEM), conversation.messages());
    }

    @Test
    void aReplyWithNoTextIsAFailure() {
        Conversation conversation = conversationWithOneTurn();
        List<Message> before = conversation.messages();
        FakeLlmClient client = new FakeLlmClient().replyText("   ");
        Compaction compaction = new Compaction(client, SYSTEM, conversation, new Cancellation());

        LlmException thrown = assertThrows(LlmException.class, compaction::compact);

        assertTrue(thrown.getMessage().contains("no summary"), thrown.getMessage());
        assertEquals(before, conversation.messages());
    }

    @Test
    void aReplyWithAToolCallIsAFailure() {
        Conversation conversation = conversationWithOneTurn();
        List<Message> before = conversation.messages();
        FakeLlmClient client = new FakeLlmClient().reply(new AssistantMessage("summary", List.of(new ToolCall("c2", "read_file", "{}"))));
        Compaction compaction = new Compaction(client, SYSTEM, conversation, new Cancellation());

        assertThrows(LlmException.class, compaction::compact);

        assertEquals(before, conversation.messages());
    }

    @Test
    void armsTheInterruptAroundTheCallAndLeavesNoneBehind() {
        Cancellation cancellation = new Cancellation();
        boolean[] wasInterrupted = {false};
        FakeLlmClient client = new FakeLlmClient()
                .beforeReply(() -> {
                    cancellation.request();
                    wasInterrupted[0] = Thread.currentThread().isInterrupted();
                })
                .replyText("a summary");

        new Compaction(client, SYSTEM, conversationWithOneTurn(), cancellation).compact();

        assertTrue(wasInterrupted[0], "the thread inside chat must be interrupted");
        assertFalse(Thread.interrupted(), "disarm must clear the interrupt status");
    }

    @Test
    void leavesNoInterruptBehindWhenTheCallFails() {
        Cancellation cancellation = new Cancellation();
        FakeLlmClient client = new FakeLlmClient().beforeReply(cancellation::request).failWith(new LlmException("interrupted"));
        Compaction compaction = new Compaction(client, SYSTEM, conversationWithOneTurn(), cancellation);

        assertThrows(LlmException.class, compaction::compact);

        assertFalse(Thread.interrupted(), "disarm must clear the interrupt status on the throw path too");
    }

    @Test
    void thePromptAsksForTheFilesAndRefusesATool() {
        assertTrue(Compaction.PROMPT.contains("every file you changed"), Compaction.PROMPT);
        assertTrue(Compaction.PROMPT.contains("Do not call a tool."), Compaction.PROMPT);
        assertTrue(Compaction.PROMPT.contains("Leave out the contents of files"), Compaction.PROMPT);
    }
}
