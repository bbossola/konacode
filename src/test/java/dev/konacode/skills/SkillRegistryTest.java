package dev.konacode.skills;

import dev.konacode.tools.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillRegistryTest {

    @TempDir
    Path root;

    private SkillRegistry registry() {
        return new SkillRegistry(new Workspace(root));
    }

    private void writeSkill(String folder, String text) throws IOException {
        Path directory = Files.createDirectories(root.resolve(folder));
        Files.writeString(directory.resolve("SKILL.md"), text);
    }

    private static String file(String name, String description) {
        return "---\nname: " + name + "\ndescription: " + description + "\n---\nThe body.\n";
    }

    @Test
    void listsEverySkillByFolderName() throws IOException {
        writeSkill("commit-message", file("commit-message", "Use for a commit."));
        writeSkill("review", file("review", "Use for a review."));

        List<Skill> found = registry().all();

        assertEquals(List.of("commit-message", "review"), found.stream().map(Skill::name).toList());
        assertEquals("Use for a commit.", found.get(0).description());
    }

    @Test
    void takesTheNameFromTheFolderAndNotFromTheHeader() throws IOException {
        writeSkill("commit-message", file("a-different-name", "Use for a commit."));

        assertEquals(List.of("commit-message"), registry().all().stream().map(Skill::name).toList());
    }

    @Test
    void skipsAFolderItCannotRead() throws IOException {
        writeSkill("good", file("good", "Use this."));
        writeSkill("broken", "there is no header here");
        Files.createDirectories(root.resolve("empty"));

        List<Skill> found = registry().all();

        assertEquals(List.of("good"), found.stream().map(Skill::name).toList());
    }

    @Test
    void ignoresAPlainFileAtTheRoot() throws IOException {
        writeSkill("good", file("good", "Use this."));
        Files.writeString(root.resolve("README.md"), "Not a skill.");

        assertEquals(List.of("good"), registry().all().stream().map(Skill::name).toList());
    }

    @Test
    void readsAFileThatStartsWithAByteOrderMark() throws IOException {
        writeSkill("commit-message", "﻿" + file("commit-message", "Use for a commit."));

        List<Skill> found = registry().all();

        assertEquals(1, found.size());
        assertEquals("commit-message", found.get(0).name());
        assertEquals("Use for a commit.", found.get(0).description());
    }

    @Test
    void returnsAnEmptyListWhenTheFolderIsMissing() {
        SkillRegistry registry = new SkillRegistry(new Workspace(root.resolve("absent")));

        assertTrue(registry.all().isEmpty());
    }

    @Test
    void findsOneSkillByName() throws IOException {
        writeSkill("commit-message", file("commit-message", "Use for a commit."));

        Optional<Skill> found = registry().lookup("commit-message");

        assertTrue(found.isPresent());
        assertEquals(root.resolve("commit-message"), found.get().folder());
        assertEquals("Use for a commit.", found.get().description());
    }

    @Test
    void returnsEmptyForAnUnknownName() {
        assertFalse(registry().lookup("absent").isPresent());
    }

    @Test
    void reportsAFolderWithNoSkillFile() throws IOException {
        Files.createDirectories(root.resolve("empty"));

        SkillException e = assertThrows(SkillException.class, () -> registry().lookup("empty"));
        assertTrue(e.getMessage().startsWith("No SKILL.md in"), e.getMessage());
    }

    @Test
    void reportsABrokenHeaderAndNamesTheFile() throws IOException {
        writeSkill("broken", "---\nname: broken\n---\nbody\n");

        SkillException e = assertThrows(SkillException.class, () -> registry().lookup("broken"));
        assertTrue(e.getMessage().contains("description"), e.getMessage());
        assertTrue(e.getMessage().contains("SKILL.md"), e.getMessage());
    }

    @Test
    void readsTheBodyAndNotTheHeader() throws IOException {
        writeSkill("commit-message", file("commit-message", "Use for a commit."));

        String body = registry().body(registry().lookup("commit-message").orElseThrow());

        assertEquals("The body.", body.strip());
    }

    @Test
    void readsTheBodyOfAFileThatStartsWithAByteOrderMark() throws IOException {
        writeSkill("commit-message", "﻿" + file("commit-message", "Use for a commit."));

        String body = registry().body(registry().lookup("commit-message").orElseThrow());

        assertEquals("The body.", body.strip());
    }

    @Test
    void refusesANameThatLeavesTheSkillsFolder() {
        assertThrows(SkillException.class, () -> registry().lookup("../secrets"));
    }

    @Test
    void refusesANameThatHoldsASeparator() {
        assertThrows(SkillException.class, () -> registry().lookup("a/b"));
    }

    @Test
    void refusesAnEmptyName() {
        assertThrows(SkillException.class, () -> registry().lookup(""));
    }

    @Test
    void refusesTheParentFolder() {
        assertThrows(SkillException.class, () -> registry().lookup(".."));
    }

    @Test
    void refusesTheCurrentFolder() {
        assertThrows(SkillException.class, () -> registry().lookup("."));
    }

    @Test
    void refusesANameThatHoldsABackslash() {
        assertThrows(SkillException.class, () -> registry().lookup("a\\b"));
    }

    @Test
    void bodyReportsAFileThatIsGone() throws IOException {
        writeSkill("commit-message", file("commit-message", "Use for a commit."));
        Skill skill = registry().lookup("commit-message").orElseThrow();
        Files.delete(skill.folder().resolve("SKILL.md"));

        assertThrows(SkillException.class, () -> registry().body(skill));
    }
}
