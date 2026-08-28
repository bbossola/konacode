package dev.konacode.tools;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class PermissionTest {

    @Test
    void twoPermissionsForOneFolderAreEqual() {
        assertEquals(new Permission.InFolder("read_file", Path.of("/etc")),
                new Permission.InFolder("read_file", Path.of("/etc")));
    }

    @Test
    void twoSpellingsOfOneFolderAreOnePermission() {
        assertEquals(new Permission.InFolder("read_file", Path.of("/etc")),
                new Permission.InFolder("read_file", Path.of("/etc/ssl/..")));
    }

    @Test
    void anotherToolIsAnotherPermission() {
        assertNotEquals(new Permission.InFolder("read_file", Path.of("/etc")),
                new Permission.InFolder("delete_file", Path.of("/etc")));
    }

    @Test
    void anotherFolderIsAnotherPermission() {
        assertNotEquals(new Permission.InFolder("read_file", Path.of("/etc")),
                new Permission.InFolder("read_file", Path.of("/var")));
    }

    @Test
    void aFolderNeverEqualsACommand() {
        assertNotEquals(new Permission.InFolder("run_command", Path.of("/etc")),
                new Permission.ExactCommand("run_command", "/etc"));
    }

    @Test
    void twoSpellingsOfOneCommandAreTwoPermissions() {
        assertNotEquals(new Permission.ExactCommand("run_command", "mvn test"),
                new Permission.ExactCommand("run_command", "mvn  test"));
    }

    @Test
    void aFolderPermissionNamesTheToolAndTheFolder() {
        assertEquals("read_file in /etc",
                new Permission.InFolder("read_file", Path.of("/etc")).inWords());
    }

    @Test
    void aCommandPermissionNamesTheToolAndTheLine() {
        assertEquals("run_command exactly: mvn test",
                new Permission.ExactCommand("run_command", "mvn test").inWords());
    }
}
