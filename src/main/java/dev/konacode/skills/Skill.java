package dev.konacode.skills;

import java.nio.file.Path;

/**
 * One skill, without its body. The list of skills costs one header for each folder, and the body
 * is read only when the user loads it.
 *
 * @param name the folder name, which is what the user types after {@code /skill}
 * @param description the description from the header, shown in the list
 * @param folder the absolute folder, which the model needs to read a reference file
 */
public record Skill(String name, String description, Path folder) {
}
