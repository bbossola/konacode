package dev.konacode.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceTest {

    @TempDir
    Path root;

    @Test
    void resolvesRelativePathsAgainstTheRoot() {
        Workspace workspace = new Workspace(root);

        assertEquals(root.resolve("src/Main.java"), workspace.resolve("src/Main.java"));
    }

    @Test
    void leavesAbsolutePathsAlone() {
        Workspace workspace = new Workspace(root);
        Path absolute = root.resolve("elsewhere.txt").toAbsolutePath();

        assertEquals(absolute, workspace.resolve(absolute.toString()));
    }

    @Test
    void expandsTildeToTheHomeDirectory() {
        Workspace workspace = new Workspace(root);
        Path home = Path.of(System.getProperty("user.home"));

        assertEquals(home.resolve("notes.txt"), workspace.resolve("~/notes.txt"));
        assertEquals(home, workspace.resolve("~"));
    }

    @Test
    void normalizesTraversalAndTrimsSurroundingWhitespace() {
        Workspace workspace = new Workspace(root);

        assertEquals(root.resolve("b.txt"), workspace.resolve("  a/../b.txt  "));
    }

    @Test
    void rejectsAnEmptyPath() {
        Workspace workspace = new Workspace(root);

        assertThrows(IllegalArgumentException.class, () -> workspace.resolve("   "));
    }

    @Test
    void readsUtf8UpToTheCap() throws IOException {
        Workspace workspace = new Workspace(root);
        Path file = root.resolve("small.txt");
        Files.writeString(file, "hello world", StandardCharsets.UTF_8);

        assertEquals("hello world", workspace.readUtf8Capped(file, 100));
    }

    @Test
    void replacesRatherThanFailsWhenTheCapSplitsAMultiByteCharacter() throws IOException {
        Workspace workspace = new Workspace(root);
        Path file = root.resolve("accented.txt");
        // "aéé" is five bytes: a(1) + é(2) + é(2). A four-byte cap lands inside the second é.
        Files.writeString(file, "aéé", StandardCharsets.UTF_8);

        String read = workspace.readUtf8Capped(file, 4);

        assertTrue(read.startsWith("aé"), "expected the intact prefix, got: " + read);
        assertEquals(3, read.length(), "expected a replacement character rather than a failure");
    }

    @Test
    void writesAtomicallyAndCreatesMissingParentDirectories() throws IOException {
        Workspace workspace = new Workspace(root);
        Path file = root.resolve("nested/deeply/out.txt");

        workspace.writeAtomic(file, "written");

        assertEquals("written", Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void listsDirectoryEntriesInSortedOrder() throws IOException {
        Workspace workspace = new Workspace(root);
        Files.createFile(root.resolve("zebra.txt"));
        Files.createFile(root.resolve("apple.txt"));
        Files.createDirectory(root.resolve("middle"));

        List<String> names = workspace.listSorted(root).stream()
                .map(path -> path.getFileName().toString())
                .toList();

        assertEquals(List.of("apple.txt", "middle", "zebra.txt"), names);
    }
}
