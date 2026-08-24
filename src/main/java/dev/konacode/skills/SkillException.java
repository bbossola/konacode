package dev.konacode.skills;

/**
 * konacode found the folder and could not read the skill. The user sees the message and fixes the
 * file. This is not a tool failure and it never reaches the model.
 */
public class SkillException extends RuntimeException {

    public SkillException(String message) {
        super(message);
    }
}
