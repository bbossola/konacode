package dev.konacode.tools;

import java.nio.file.Path;
import java.util.Objects;

/**
 * A standing decision the user gave during this session.
 *
 * <p>konacode compares two permissions for equality, and it never examines one. A record gives
 * that equality. Two kinds are never equal, so a permission for a folder can never cover a
 * command. The interface is sealed, so a third kind is a compile error at {@link #inWords()} and
 * at no other place.
 */
public sealed interface Permission {

    /** The words the question shows on the "always" line. */
    String inWords();

    /** Every call the named tool makes on a path directly in the named folder. */
    record InFolder(String toolName, Path folder) implements Permission {

        public InFolder {
            Objects.requireNonNull(toolName, "toolName");
            Objects.requireNonNull(folder, "folder");
            // Path.equals compares the spelling, so two spellings of one folder must be one value.
            folder = folder.normalize();
        }

        @Override
        public String inWords() {
            return toolName + " in " + folder;
        }
    }

    /** One command line, character for character. */
    record ExactCommand(String toolName, String command) implements Permission {

        public ExactCommand {
            Objects.requireNonNull(toolName, "toolName");
            Objects.requireNonNull(command, "command");
        }

        @Override
        public String inWords() {
            return toolName + " exactly: " + command;
        }
    }
}
