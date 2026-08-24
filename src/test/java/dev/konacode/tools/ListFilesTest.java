package dev.konacode.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ListFilesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path root;

    private ListFiles tool;

    @BeforeEach
    void setUp() {
        tool = new ListFiles(new Workspace(root), StopCheck.NEVER);
    }

    private static JsonNode args(String path) {
        return MAPPER.createObjectNode().put("path", path);
    }

    private static List<String> lines(ToolResult result) {
        return List.of(assertInstanceOf(ToolResult.Ok.class, result).text().split("\n"));
    }

    @Test
    void listsEntriesSortedWithASlashOnDirectories() throws IOException {
        Files.createFile(root.resolve("zebra.txt"));
        Files.createDirectory(root.resolve("apple"));

        List<String> lines = lines(tool.execute(args(".")));

        assertTrue(lines.get(0).startsWith("directory "), lines.get(0));
        assertEquals(List.of("apple/", "zebra.txt"), lines.subList(1, lines.size()));
    }

    @Test
    void defaultsToTheWorkspaceRootWhenNoPathIsGiven() throws IOException {
        Files.createFile(root.resolve("only.txt"));

        List<String> lines = lines(tool.execute(MAPPER.createObjectNode()));

        assertEquals("only.txt", lines.get(1));
    }

    @Test
    void marksSymbolicLinksWithAnAtSign() throws IOException {
        Files.createFile(root.resolve("target.txt"));
        Files.createSymbolicLink(root.resolve("alias.txt"), root.resolve("target.txt"));

        List<String> lines = lines(tool.execute(args(".")));

        assertTrue(lines.contains("alias.txt@"), lines.toString());
    }

    @Test
    void reportsAnEmptyDirectoryExplicitly() {
        List<String> lines = lines(tool.execute(args(".")));

        assertEquals("<empty>", lines.get(1));
    }

    @Test
    void capsTheListingAndSaysHowManyWereOmitted() throws IOException {
        for (int i = 0; i < ListFiles.ENTRY_LIMIT + 3; i++) {
            Files.createFile(root.resolve("file-%04d.txt".formatted(i)));
        }

        List<String> lines = lines(tool.execute(args(".")));

        assertEquals(ListFiles.ENTRY_LIMIT + 2, lines.size());
        assertEquals("… 3 more", lines.get(lines.size() - 1));
    }

    @Test
    void reportsAFileRatherThanListingIt() throws IOException {
        Files.createFile(root.resolve("single.txt"));

        ToolResult result = tool.execute(args("single.txt"));

        assertTrue(assertInstanceOf(ToolResult.Ok.class, result).text().startsWith("file "));
    }

    @Test
    void reportsAMissingPathAsAnError() {
        assertInstanceOf(ToolResult.Err.class, tool.execute(args("nowhere")));
    }

    @Test
    void rendersSymlinksToDirectoriesDistinctlyFromSymlinksToFiles() throws IOException {
        Files.createDirectory(root.resolve("realdir"));
        Files.createFile(root.resolve("realfile"));
        Files.createSymbolicLink(root.resolve("link_to_dir"), root.resolve("realdir"));
        Files.createSymbolicLink(root.resolve("link_to_file"), root.resolve("realfile"));

        List<String> lines = lines(tool.execute(args(".")));

        // Files.isDirectory follows the link deliberately. That yields three distinct renderings
        // rather than two: "/" tells the model the entry can be passed to list_files, and "@"
        // tells it the entry is a link. LinkOption.NOFOLLOW_LINKS would collapse link_to_dir and
        // link_to_file into the same output, discarding the actionable half of the signal.
        assertTrue(lines.contains("realdir/"), lines.toString());
        assertTrue(lines.contains("link_to_dir/@"), lines.toString());
        assertTrue(lines.contains("link_to_file@"), lines.toString());
    }

    @Test
    void stopsBetweenEntriesAndReportsThatNothingChanged() throws IOException {
        Files.createFile(root.resolve("a.txt"));
        Files.createFile(root.resolve("b.txt"));
        ListFiles stopping = new ListFiles(new Workspace(root), () -> true);

        ToolResult result = stopping.execute(args("."));

        ToolResult.Err error = assertInstanceOf(ToolResult.Err.class, result);
        assertTrue(error.message().startsWith("Stopped by the user"), error.message());
        assertTrue(error.message().contains("Nothing was changed."), error.message());
    }
}
