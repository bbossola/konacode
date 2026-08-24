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

        String name = null;
        String description = null;
        int index = 1;
        while (index < lines.size() && !lines.get(index).strip().equals(MARKER)) {
            String line = lines.get(index);
            index++;
            if (line.isBlank()) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                throw new IllegalArgumentException("The header line \"" + line.strip()
                        + "\" is not a key and a value. Write each key on one line.");
            }
            String key = line.substring(0, colon).strip();
            String value = line.substring(colon + 1).strip();
            if (key.equals("name")) {
                name = readable(key, value);
            } else if (key.equals("description")) {
                description = readable(key, value);
            }
        }

        if (index >= lines.size()) {
            throw new IllegalArgumentException("The header has no closing " + MARKER + " marker.");
        }
        if (name == null) {
            throw new IllegalArgumentException("The header has no name.");
        }
        if (description == null) {
            throw new IllegalArgumentException("The header has no description.");
        }

        String body = String.join("\n", lines.subList(index + 1, lines.size()));
        return new FrontMatter(name, description, body);
    }

    /**
     * konacode reads a value that is on one line and is not quoted. A quoted value and a folded
     * value are YAML features. konacode reports them rather than read them wrongly.
     */
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
