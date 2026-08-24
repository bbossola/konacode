package dev.konacode.skills;

/**
 * konacode cannot give the user the skill they named. The name is not a folder name, or the folder
 * holds no readable {@code SKILL.md}. The user reads the message and corrects the name or the file.
 * This is not a tool failure, and it never reaches the model.
 */
public class SkillException extends RuntimeException {

    public SkillException(String message) {
        super(message);
    }
}
