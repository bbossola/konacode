package dev.konacode.skills;

import java.util.List;

/**
 * The header of a {@code SKILL.md}, and the body that follows it.
 *
 * <p>The header is YAML, and konacode has no YAML parser. A skill file uses two keys with
 * single-line values, so this reads those two and nothing else. A value that YAML would quote,
 * fold or continue on a second line is not supported, and {@link #parse} reports it.
 */
record FrontMatter(String name, String description, String body) {

    private static final String MARKER = "---";

    static FrontMatter parse(String text) {
        List<String> lines = text.lines().toList();
        if (lines.isEmpty() || !lines.get(0).strip().equals(MARKER)) {
            throw new IllegalArgumentException("The file must start with a " + MARKER + " marker.");
        }

        // konacode finds the closing marker first, so a file with no closing marker reports the
        // marker, and not the first line of the body that is not a key.
        int close = 1;
        while (close < lines.size() && !lines.get(close).strip().equals(MARKER)) {
            close++;
        }
        if (close >= lines.size()) {
            throw new IllegalArgumentException("The header has no closing " + MARKER + " marker.");
        }

        String name = null;
        String description = null;
        boolean insideABlock = false;
        for (int index = 1; index < close; index++) {
            String line = lines.get(index);
            if (line.isBlank()) {
                continue;
            }
            if (Character.isWhitespace(line.charAt(0))) {
                // A key with no value opens a block, and konacode reads no key inside one. A key
                // that has a value cannot continue, so an indented line below it is a wrapped
                // value, and a wrapped value loses its tail.
                if (insideABlock) {
                    continue;
                }
                throw new IllegalArgumentException("The header line \"" + line.strip()
                        + "\" is indented. Write each key and its value on one line.");
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                throw new IllegalArgumentException("The header line \"" + line.strip()
                        + "\" is not a key and a value. Write each key on one line.");
            }
            String key = line.substring(0, colon).strip();
            String value = line.substring(colon + 1).strip();
            insideABlock = value.isEmpty();
            if (key.equals("name")) {
                name = readable(key, value);
            } else if (key.equals("description")) {
                description = readable(key, value);
            }
        }

        if (name == null) {
            throw new IllegalArgumentException("The header has no name.");
        }
        if (description == null) {
            throw new IllegalArgumentException("The header has no description.");
        }

        String body = trimmed(String.join("\n", lines.subList(close + 1, lines.size())));
        if (body.isEmpty()) {
            throw new IllegalArgumentException("The file has no text below the header.");
        }
        return new FrontMatter(name, description, body);
    }

    /** Removes a blank line above the body, and keeps the indent of the first line that follows. */
    private static String trimmed(String body) {
        return body.replaceFirst("^(?:[ \\t]*\\R)+", "").stripTrailing();
    }

    private static String readable(String key, String value) {
        if (value.isEmpty()) {
            throw new IllegalArgumentException("The value of " + key + " is empty.");
        }
        if (value.startsWith("\"") || value.startsWith("'")) {
            throw new IllegalArgumentException(
                    "The value of " + key + " is quoted. Write it without a quotation mark.");
        }
        if (value.startsWith(">") || value.startsWith("|")) {
            throw new IllegalArgumentException(
                    "The value of " + key + " is folded over several lines. Write it on one line.");
        }
        return value;
    }
}
