package dev.konacode.policy;

import dev.konacode.tools.Permission;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DecisionTest {

    @Test
    void withNoteChangesTheNoteAndCopiesEveryOtherField() {
        Decision.Ask ask = (Decision.Ask) Decision.ask("run_command", "run a command", "mvn -q test",
                Optional.of(new Permission.ExactCommand("run_command", "mvn -q test")));

        Decision.Ask noted = ask.withNote("The judge did not answer, so konacode asks.");

        assertEquals("The judge did not answer, so konacode asks.", noted.note());
        assertEquals("", ask.note());
        assertEquals(ask.toolName(), noted.toolName());
        assertEquals(ask.toolIntent(), noted.toolIntent());
        assertEquals(ask.toolOperand(), noted.toolOperand());
        assertEquals(ask.standingPermission(), noted.standingPermission());
    }
}
