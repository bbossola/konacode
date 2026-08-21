package dev.konacode.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolResultTest {

    @Test
    void okRendersItsTextUnchanged() {
        assertEquals("hello", ToolResult.ok("hello").render());
    }

    @Test
    void errRendersWithTheErrorPrefixTheModelReads() {
        assertEquals("<error> not found", ToolResult.err("not found").render());
    }
}
