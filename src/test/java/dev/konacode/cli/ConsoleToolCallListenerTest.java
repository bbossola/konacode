package dev.konacode.cli;

import dev.konacode.tools.ToolResult;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConsoleToolCallListenerTest {

    @Test
    void printsOneLinePerToolCall() {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        ConsoleToolCallListener listener =
                new ConsoleToolCallListener(new PrintStream(captured, true, StandardCharsets.UTF_8));

        listener.onToolCall("read_file", "{\"path\":\"pom.xml\"}");
        listener.onToolResult("read_file", ToolResult.ok("irrelevant"));

        assertEquals("tool: read_file({\"path\":\"pom.xml\"})" + System.lineSeparator(),
                captured.toString(StandardCharsets.UTF_8));
    }
}
