package dev.konacode.cli.markdown;

import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;

import java.util.List;

/**
 * Turns markdown into text with ANSI codes.
 *
 * <p>This one method is the whole surface. Mordant renders markdown to a terminal and would
 * replace everything behind it, but konacode cannot use Mordant today. See FOLLOWUP.md.
 */
public final class Markdown {

    private static final Parser PARSER = Parser.builder()
            .extensions(List.of(TablesExtension.create(), StrikethroughExtension.create()))
            .build();

    private Markdown() {
    }

    public static String render(String markdown, int width) {
        Node document = PARSER.parse(markdown);
        AnsiRenderer renderer = new AnsiRenderer(width);
        document.accept(renderer);
        return renderer.text();
    }
}
