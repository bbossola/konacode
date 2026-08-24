package dev.konacode.skills;

import java.nio.file.Path;

/**
 * One skill, without its body. konacode reads one header for each folder, and it reads a body
 * only when the user loads a skill.
 *
 * @param name the folder name, which is what the user types after {@code /skill}
 * @param description the description from the header, shown in the list
 * @param folder the absolute folder, which the model needs to read a reference file
 */
public record Skill(String name, String description, Path folder) {
}
