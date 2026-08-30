package dev.konacode.tools;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionTest {

    @Test
    void anActionNamesTheToolThatStatedIt() {
        Action action = Action.once("read_file", Effect.READS_OUTSIDE, "/etc/hosts");

        assertEquals("read_file", action.toolName());
        assertEquals("/etc/hosts", action.toolOperand());
        assertTrue(action.standingPermission().isEmpty());
    }

    @Test
    void anActionWithNoPermissionOffersNothingStanding() {
        Action action = Action.once("run_command", Effect.RUNS, "rm *.log");

        assertEquals(Effect.RUNS, action.effect());
        assertEquals("rm *.log", action.toolOperand());
        assertTrue(action.standingPermission().isEmpty());
    }

    @Test
    void anActionCarriesThePermissionItWasGiven() {
        Permission permission = new Permission.InFolder("read_file", Path.of("/etc"));

        Action action = Action.of("read_file", Effect.READS_OUTSIDE, "/etc/hosts", permission);

        assertEquals(Optional.of(permission), action.standingPermission());
    }

    @Test
    void anActionRefusesANullPermission() {
        assertThrows(NullPointerException.class,
                () -> new Action("run_command", Effect.RUNS, "ls", null));
    }

    @Test
    void anActionRefusesANullOperand() {
        assertThrows(NullPointerException.class,
                () -> Action.once("run_command", Effect.RUNS, null));
    }
}
