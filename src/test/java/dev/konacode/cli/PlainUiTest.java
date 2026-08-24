package dev.konacode.cli;

import dev.konacode.trace.TraceEvent.ToolCalled;
import dev.konacode.trace.TraceEvent.ToolFinished;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlainUiTest {

    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(captured, true, StandardCharsets.UTF_8);

    private PlainUi ui(String input) {
        return new PlainUi(new BufferedReader(new StringReader(input)), out);
    }

    private String written() {
        return captured.toString(StandardCharsets.UTF_8);
    }

    @Test
    void readsALineAndPrintsThePrompt() {
        assertEquals(Optional.of("hello"), ui("hello\n").readLine());
        assertTrue(written().contains("You"), written());
    }

    @Test
    void returnsEmptyAtTheEndOfInput() {
        assertEquals(Optional.empty(), ui("").readLine());
    }

    @Test
    void printsTheBanner() {
        ui("").welcome();

        assertTrue(written().contains("Chat with konacode"), written());
    }

    @Test
    void printsTheAnswerAfterTheName() {
        ui("").showAnswer("two files here");

        assertTrue(Ansi.strip(written()).contains("konacode: two files here"), written());
    }

    @Test
    void printsOneLineForEachToolCall() {
        ui("").emit(new ToolCalled(1, "read_file", "{\"path\":\"pom.xml\"}"));

        assertEquals("tool: read_file({\"path\":\"pom.xml\"})" + System.lineSeparator(), written());
    }

    @Test
    void printsNothingForAToolResult() {
        ui("").emit(new ToolFinished(1, "read_file", true, "content", 5));

        assertEquals("", written());
    }

    @Test
    void printsNothingWhenTheAgentStartsWork() {
        ui("").thinking();

        assertEquals("", written());
    }

    @Test
    void doesNotRenderMarkdown() {
        ui("").showAnswer("# not a heading");

        assertTrue(Ansi.strip(written()).contains("# not a heading"), written());
    }
}
