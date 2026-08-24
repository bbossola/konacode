package dev.konacode.skills;

import dev.konacode.tools.StopCheck;
import dev.konacode.tools.Workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The skills on disk. It reads the folder on every call, so a new skill appears without a
 * restart, and a missing folder gives an empty list instead of a failure at startup.
 */
public final class SkillRegistry {

    static final int MAX_BYTES = 100 * 1024;
    static final String FILE_NAME = "SKILL.md";

    private final Workspace workspace;
    private final StopCheck stop;

    public SkillRegistry(Workspace workspace, StopCheck stop) {
        this.workspace = workspace;
        this.stop = stop;
    }

    /** Folder order, sorted. A folder that konacode cannot read is skipped. */
    public List<Skill> all() {
        List<Skill> skills = new ArrayList<>();
        List<Path> folders;
        try {
            folders = workspace.listSorted(workspace.root(), stop);
        } catch (IOException e) {
            return List.of();
        }
        for (Path folder : folders) {
            if (!Files.isDirectory(folder)) {
                continue;
            }
            try {
                FrontMatter header = read(folder);
                skills.add(new Skill(folder.getFileName().toString(), header.description(), folder));
            } catch (IOException | IllegalArgumentException e) {
                // One broken skill must not hide the others in the list.
            }
        }
        return List.copyOf(skills);
    }

    private FrontMatter read(Path folder) throws IOException {
        return FrontMatter.parse(
                withoutByteOrderMark(workspace.readUtf8Capped(folder.resolve(FILE_NAME), MAX_BYTES, stop)));
    }

    /**
     * An editor may write a byte order mark before the first {@code ---}. {@code String.strip}
     * keeps U+FEFF, so the parser would report a file that has a correct header.
     */
    private static String withoutByteOrderMark(String text) {
        return text.startsWith("﻿") ? text.substring(1) : text;
    }
}
