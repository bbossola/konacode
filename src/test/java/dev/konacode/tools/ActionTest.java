package dev.konacode.tools;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionTest {

    @Test
    void anActionWithNoPermissionOffersNothingStanding() {
        Action action = Action.once(Effect.RUNS, "rm *.log");

        assertEquals(Effect.RUNS, action.effect());
        assertEquals("rm *.log", action.operand());
        assertTrue(action.permission().isEmpty());
    }

    @Test
    void anActionCarriesThePermissionItWasGiven() {
        Permission permission = new Permission.InFolder("read_file", Path.of("/etc"));

        Action action = Action.of(Effect.READS_OUTSIDE, "/etc/hosts", permission);

        assertEquals(Optional.of(permission), action.permission());
    }

    @Test
    void anActionRefusesANullPermission() {
        assertThrows(NullPointerException.class,
                () -> new Action(Effect.RUNS, "ls", null));
    }

    @Test
    void anActionRefusesANullOperand() {
        assertThrows(NullPointerException.class,
                () -> Action.once(Effect.RUNS, null));
    }
}
