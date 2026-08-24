package dev.konacode.skills;

import dev.konacode.tools.StopCheck;
import dev.konacode.tools.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillRegistryTest {

    @TempDir
    Path root;

    private SkillRegistry registry() {
        return new SkillRegistry(new Workspace(root), StopCheck.NEVER);
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
    void skipsAFolderItCannotRead() throws IOException {
        writeSkill("good", file("good", "Use this."));
        writeSkill("broken", "there is no header here");
        Files.createDirectories(root.resolve("empty"));

        List<Skill> found = registry().all();

        assertEquals(List.of("good"), found.stream().map(Skill::name).toList());
    }

    @Test
    void readsAFileThatStartsWithAByteOrderMark() throws IOException {
        writeSkill("commit-message", "﻿" + file("commit-message", "Use for a commit."));

        assertEquals(1, registry().all().size());
    }

    @Test
    void returnsAnEmptyListWhenTheFolderIsMissing() {
        SkillRegistry registry =
                new SkillRegistry(new Workspace(root.resolve("absent")), StopCheck.NEVER);

        assertTrue(registry.all().isEmpty());
    }
}
