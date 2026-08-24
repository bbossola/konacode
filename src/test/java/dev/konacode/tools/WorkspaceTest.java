package dev.konacode.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class WorkspaceTest {

    @TempDir
    Path root;

    @TempDir
    Path outside;

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
    void overwritesAnExistingFile() throws IOException {
        Workspace workspace = new Workspace(root);
        Path file = root.resolve("existing.txt");
        Files.writeString(file, "before", StandardCharsets.UTF_8);

        workspace.writeAtomic(file, "after");

        assertEquals("after", Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void preservesAnExistingFilesPermissionsWhenOverwriting() throws IOException {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "POSIX permissions are not supported on this filesystem");

        Workspace workspace = new Workspace(root);
        Path script = root.resolve("build.sh");
        Files.writeString(script, "#!/bin/sh\necho old\n", StandardCharsets.UTF_8);
        Set<PosixFilePermission> executable = PosixFilePermissions.fromString("rwxr-xr-x");
        Files.setPosixFilePermissions(script, executable);

        workspace.writeAtomic(script, "#!/bin/sh\necho new\n");

        // A temp-file-and-move that ignores this strips the executable bit from every script
        // the edit_file tool touches, silently.
        assertEquals(executable, Files.getPosixFilePermissions(script));
    }

    @Test
    void createsNewFilesWithOrdinaryPermissionsNotRestrictiveTempFileOnes() throws IOException {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "POSIX permissions are not supported on this filesystem");

        Workspace workspace = new Workspace(root);
        Path viaWorkspace = root.resolve("viaWorkspace.txt");
        Path viaOrdinaryWrite = root.resolve("viaOrdinaryWrite.txt");

        workspace.writeAtomic(viaWorkspace, "x");
        Files.writeString(viaOrdinaryWrite, "x", StandardCharsets.UTF_8);

        // Compared against an ordinary write rather than a hardcoded mode, so the assertion
        // holds under any umask.
        assertEquals(Files.getPosixFilePermissions(viaOrdinaryWrite),
                Files.getPosixFilePermissions(viaWorkspace));
    }

    @Test
    void listsDirectoryEntriesInCollatorOrderNotAsciiOrder() throws IOException {
        Workspace workspace = new Workspace(root);
        Files.createFile(root.resolve("Banana.txt"));
        Files.createFile(root.resolve("apple.txt"));
        Files.createFile(root.resolve("cherry.txt"));

        List<String> names = workspace.listSorted(root).stream()
                .map(path -> path.getFileName().toString())
                .toList();

        // String::compareTo would give [Banana.txt, apple.txt, cherry.txt] — uppercase sorts
        // first in ASCII. Collator orders the way a human reads a file listing.
        assertEquals(List.of("apple.txt", "Banana.txt", "cherry.txt"), names);
    }

    @Test
    void readsAnEditableFileWholeWithoutTruncating() throws IOException {
        Workspace workspace = new Workspace(root);
        Path file = root.resolve("whole.txt");
        String content = "line\n".repeat(1_000);
        Files.writeString(file, content, StandardCharsets.UTF_8);

        assertEquals(content, workspace.readUtf8ForEditing(file, 1_000_000));
    }

    @Test
    void refusesToReadAnOversizedFileForEditingRatherThanTruncatingIt() throws IOException {
        Workspace workspace = new Workspace(root);
        Path file = root.resolve("big.txt");
        Files.writeString(file, "x".repeat(200), StandardCharsets.UTF_8);

        // Truncating here would make edit_file rewrite the file as its own first N bytes.
        IOException thrown = assertThrows(IOException.class,
                () -> workspace.readUtf8ForEditing(file, 100));
        assertTrue(thrown.getMessage().contains("edit limit"), thrown.getMessage());
    }

    @Test
    void refusesToReadAFileThatIsNotValidUtf8ForEditing() throws IOException {
        Workspace workspace = new Workspace(root);
        Path file = root.resolve("binary.dat");
        Files.write(file, new byte[]{(byte) 0xC3, (byte) 0x28, (byte) 0xA9});

        // Lenient decoding would substitute U+FFFD, and the edit would write that back over
        // the original bytes.
        assertThrows(IOException.class, () -> workspace.readUtf8ForEditing(file, 1_000_000));
    }

    @Test
    void deletesAFile() throws IOException {
        Path file = root.resolve("gone.txt");
        Files.writeString(file, "bye");

        new Workspace(root).delete(file);

        assertFalse(Files.exists(file));
    }

    @Test
    void deleteReportsAMissingFile() {
        Workspace workspace = new Workspace(root);
        Path missing = root.resolve("absent.txt");

        assertThrows(IOException.class, () -> workspace.delete(missing));
    }

    @Test
    void aPathUnderTheRootIsInside() {
        Workspace workspace = new Workspace(root);

        assertTrue(workspace.insideRoot(root.resolve("src/Main.java")));
        assertTrue(workspace.readable(root.resolve("src/Main.java")));
    }

    @Test
    void aPathAboveTheRootIsOutside() {
        Workspace workspace = new Workspace(root);

        assertFalse(workspace.insideRoot(outside.resolve("elsewhere.txt")));
        assertFalse(workspace.readable(outside.resolve("elsewhere.txt")));
    }

    @Test
    void aReadableFolderIsReadableAndNotInside() throws IOException {
        Path skills = Files.createDirectories(outside.resolve("skills"));
        Workspace workspace = new Workspace(root, List.of(skills));

        assertTrue(workspace.readable(skills.resolve("one/SKILL.md")));
        assertFalse(workspace.insideRoot(skills.resolve("one/SKILL.md")));
    }

    @Test
    void aLinkThatLeavesTheRootIsOutside() throws IOException {
        Path target = Files.createDirectories(outside.resolve("target"));
        Path link = Files.createSymbolicLink(root.resolve("escape"), target);
        Workspace workspace = new Workspace(root);

        try {
            assertFalse(workspace.insideRoot(root.resolve("escape/secret.txt")));
        } finally {
            // JUnit declines to follow a link out of the temporary folder, and says so on every
            // run. The test made the link, so the test removes it.
            Files.delete(link);
        }
    }

    @Test
    void aBrokenLinkIsOutside() throws IOException {
        Path link = Files.createSymbolicLink(root.resolve("escape"), outside.resolve("missing"));
        Workspace workspace = new Workspace(root);

        try {
            assertFalse(workspace.insideRoot(link));
            assertFalse(workspace.readable(link.resolve("child.txt")));
        } finally {
            Files.delete(link);
        }
    }

    @Test
    void aRootReachedThroughALinkIsStillTheRoot() throws IOException {
        Path real = Files.createDirectories(outside.resolve("realroot"));
        Path alias = Files.createSymbolicLink(root.resolve("alias"), real);
        Files.writeString(real.resolve("a.txt"), "x");
        Workspace workspace = new Workspace(alias);

        try {
            assertTrue(workspace.insideRoot(alias.resolve("a.txt")));
            assertTrue(workspace.insideRoot(real.resolve("a.txt")));
        } finally {
            Files.delete(alias);
        }
    }

    @Test
    void aFileThatDoesNotExistYetIsStillJudged() {
        Workspace workspace = new Workspace(root);

        assertTrue(workspace.insideRoot(root.resolve("new/deep/file.txt")));
        assertFalse(workspace.insideRoot(outside.resolve("new/deep/file.txt")));
    }

    @Test
    void theRootItselfIsInside() {
        assertTrue(new Workspace(root).insideRoot(root));
    }

    @Test
    void tryResolveReturnsTheResolvedPath() {
        Workspace workspace = new Workspace(root);

        assertEquals(Optional.of(root.resolve("src/Main.java")), workspace.tryResolve("src/Main.java"));
    }

    @Test
    void tryResolveIsEmptyForAnEmptyPath() {
        Workspace workspace = new Workspace(root);

        assertEquals(Optional.empty(), workspace.tryResolve(""));
    }

    @Test
    void aWriteIsJudgedWhereTheEntrySits() throws IOException {
        Path inside = Files.writeString(root.resolve("real.txt"), "hello");
        Path link = Files.createSymbolicLink(outside.resolve("link.txt"), inside);
        Workspace workspace = new Workspace(root);

        try {
            assertTrue(workspace.insideRoot(link), "the target is inside");
            assertFalse(workspace.writable(link), "the entry the write replaces is outside");
        } finally {
            Files.delete(link);
        }
    }

    @Test
    void aLinkInsideTheRootIsWritable() throws IOException {
        Path target = Files.writeString(outside.resolve("target.txt"), "x");
        Path link = Files.createSymbolicLink(root.resolve("link.txt"), target);
        Workspace workspace = new Workspace(root);

        try {
            assertFalse(workspace.insideRoot(link), "the target is outside");
            assertTrue(workspace.writable(link), "delete removes the link, which is inside");
        } finally {
            Files.delete(link);
        }
    }

    @Test
    void aWriteUnderALinkedFolderIsJudgedInTheRealFolder() throws IOException {
        Path elsewhere = Files.createDirectories(outside.resolve("elsewhere"));
        Path folder = Files.createSymbolicLink(root.resolve("linked"), elsewhere);
        Workspace workspace = new Workspace(root);

        try {
            assertFalse(workspace.writable(folder.resolve("new.txt")));
        } finally {
            Files.delete(folder);
        }
    }
}
