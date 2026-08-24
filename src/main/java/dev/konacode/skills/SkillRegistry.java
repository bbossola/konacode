package dev.konacode.skills;

import dev.konacode.tools.Workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The skills on disk. It reads the folder on every call, so a new skill appears without a
 * restart, and a missing folder gives an empty list instead of a failure at startup.
 *
 * <p>This holds no {@code StopCheck}. The command that reads a skill runs at the prompt and not
 * inside a turn, so there is no turn to stop.
 */
public final class SkillRegistry {

    private static final int MAX_BYTES = 100_000;
    private static final String FILE_NAME = "SKILL.md";

    private final Workspace workspace;

    public SkillRegistry(Workspace workspace) {
        this.workspace = workspace;
    }

    /** The folder that holds the skills. A message names it, so it must be the real one. */
    public Path root() {
        return workspace.root();
    }

    /** Folder order, sorted. A folder that konacode cannot read is skipped. */
    public List<Skill> all() {
        List<Skill> skills = new ArrayList<>();
        List<Path> folders;
        try {
            folders = workspace.listSorted(workspace.root());
        } catch (IOException e) {
            return List.of();
        }
        for (Path folder : folders) {
            if (!Files.isDirectory(folder)) {
                continue;
            }
            try {
                FrontMatter header = read(folder.resolve(FILE_NAME));
                skills.add(new Skill(folder.getFileName().toString(), header.description(), folder));
            } catch (IOException | IllegalArgumentException e) {
                // One unreadable file must not make every other skill unavailable.
            }
        }
        return List.copyOf(skills);
    }

    /**
     * @return empty when no folder has that name
     * @throws SkillException when the name is not a plain folder name, or when the folder exists
     *     and konacode cannot read the skill
     */
    public Optional<Skill> lookup(String name) {
        Path folder = folderFor(name);
        if (!Files.isDirectory(folder)) {
            return Optional.empty();
        }
        Path real = realPath(folder);
        return Optional.of(new Skill(real.getFileName().toString(),
                readOrReport(real).description(), real));
    }

    /**
     * The text below the header.
     *
     * @throws SkillException when konacode can no longer read the file
     */
    public String body(Skill skill) {
        return readOrReport(skill.folder()).body();
    }

    /**
     * A skill is one folder directly inside the skills folder. konacode refuses any other name, so
     * that {@code /skill ../../etc} cannot read a file outside the skills folder.
     */
    private Path folderFor(String name) {
        if (name.isEmpty() || name.contains("/") || name.contains("\\")
                || name.equals("..") || name.equals(".")) {
            throw new SkillException("A skill name is one folder name: " + name);
        }
        try {
            return workspace.root().resolve(name);
        } catch (InvalidPathException e) {
            throw new SkillException("A skill name is one folder name: " + name);
        }
    }

    private static Path realPath(Path folder) {
        try {
            return folder.toRealPath();
        } catch (IOException e) {
            throw new SkillException("Could not read " + folder + ".", e);
        }
    }

    private FrontMatter readOrReport(Path folder) {
        Path file = folder.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) {
            throw new SkillException("No " + FILE_NAME + " in " + folder + ".");
        }
        try {
            return read(file);
        } catch (IOException | IllegalArgumentException e) {
            throw new SkillException("Could not read " + file + ": " + e.getMessage(), e);
        }
    }

    private FrontMatter read(Path file) throws IOException {
        return FrontMatter.parse(withoutByteOrderMark(workspace.readUtf8Capped(file, MAX_BYTES)));
    }

    /**
     * An editor may write a byte order mark before the first {@code ---}. {@code String.strip}
     * keeps U+FEFF, so the parser would report a file that has a correct header.
     */
    private static String withoutByteOrderMark(String text) {
        return text.startsWith("﻿") ? text.substring(1) : text;
    }
}
