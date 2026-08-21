package dev.konacode.cli.markdown;

import dev.konacode.cli.Ansi;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Wrap {

    private static final Pattern CODE = Pattern.compile("\u001B\\[[0-9;]*m");

    private Wrap() {
    }

    static List<String> lines(String text, int width) {
        if (text.isEmpty()) {
            return List.of("");
        }

        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        int used = 0;

        for (String word : text.split(" ")) {
            int size = Ansi.visibleLength(word);

            if (size > width) {
                if (used > 0) {
                    lines.add(line.toString());
                    line = new StringBuilder(openCodes(lines));
                    used = 0;
                }
                for (String piece : split(word, width)) {
                    lines.add(piece);
                }
                line = new StringBuilder(openCodes(lines));
                used = 0;
                continue;
            }

            int extra = used == 0 ? size : size + 1;
            if (used + extra > width) {
                lines.add(line.toString());
                String open = openCodes(lines);
                line = new StringBuilder(open).append(word);
                used = size;
            } else {
                if (used > 0) {
                    line.append(' ');
                }
                line.append(word);
                used += extra;
            }
        }

        if (used > 0 || lines.isEmpty()) {
            lines.add(line.toString());
        }
        return lines;
    }

    private static List<String> split(String word, int width) {
        List<String> pieces = new ArrayList<>();
        String plain = Ansi.strip(word);
        for (int at = 0; at < plain.length(); at += width) {
            pieces.add(plain.substring(at, Math.min(plain.length(), at + width)));
        }
        return pieces;
    }

    /**
     * Finds the style that is still open at the end of the lines written so far. A break inside a
     * styled phrase would otherwise drop the style on every line after the first.
     */
    private static String openCodes(List<String> lines) {
        String open = "";
        for (String line : lines) {
            Matcher matcher = CODE.matcher(line);
            while (matcher.find()) {
                open = matcher.group().equals(Ansi.RESET) ? "" : matcher.group();
            }
        }
        return open;
    }
}
