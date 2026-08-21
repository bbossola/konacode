package dev.konacode.cli.markdown;

import dev.konacode.cli.Ansi;
import org.commonmark.ext.gfm.strikethrough.Strikethrough;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.CustomBlock;
import org.commonmark.node.CustomNode;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.ListBlock;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.node.ThematicBreak;

import java.util.ArrayList;
import java.util.List;

final class AnsiRenderer extends AbstractVisitor {

    private static final String[] HEADING = {
            Ansi.CYAN, Ansi.CYAN, Ansi.BLUE, Ansi.BLUE, Ansi.MAGENTA, Ansi.MAGENTA};

    private final StringBuilder out = new StringBuilder();
    private final int width;
    private StringBuilder inline;
    private int indent;

    AnsiRenderer(int width) {
        this.width = Math.max(20, width);
    }

    String text() {
        return out.toString().stripTrailing();
    }

    @Override
    public void visit(Heading heading) {
        String colour = HEADING[Math.min(heading.getLevel(), 6) - 1];
        emit(Ansi.style(collect(heading), Ansi.BOLD, colour), "", "");
        blank();
    }

    @Override
    public void visit(Paragraph paragraph) {
        emit(collect(paragraph), "", "");
        blank();
    }

    @Override
    public void visit(BlockQuote quote) {
        String bar = Ansi.style("| ", Ansi.DIM);
        indent += 2;
        for (Node child = quote.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof Paragraph) {
                emit(Ansi.style(collect(child), Ansi.DIM), bar, bar);
            } else {
                child.accept(this);
            }
        }
        indent -= 2;
        blank();
    }

    @Override
    public void visit(BulletList list) {
        renderItems(list, null);
    }

    @Override
    public void visit(OrderedList list) {
        Integer start = list.getMarkerStartNumber();
        renderItems(list, start == null ? 1 : start);
    }

    private void renderItems(ListBlock list, Integer number) {
        int at = number == null ? 0 : number;
        for (Node item = list.getFirstChild(); item != null; item = item.getNext()) {
            String marker = number == null ? "- " : (at++) + ". ";
            String pad = " ".repeat(marker.length());
            boolean first = true;
            for (Node child = item.getFirstChild(); child != null; child = child.getNext()) {
                if (child instanceof Paragraph) {
                    emit(collect(child), first ? marker : pad, pad);
                    first = false;
                } else {
                    indent += marker.length();
                    child.accept(this);
                    indent -= marker.length();
                }
            }
        }
        blank();
    }

    @Override
    public void visit(FencedCodeBlock block) {
        code(block.getLiteral());
        blank();
    }

    @Override
    public void visit(IndentedCodeBlock block) {
        code(block.getLiteral());
        blank();
    }

    private void code(String literal) {
        for (String line : literal.stripTrailing().split("\n", -1)) {
            out.append(" ".repeat(indent + 2)).append(Ansi.style(line, Ansi.YELLOW)).append('\n');
        }
    }

    @Override
    public void visit(ThematicBreak rule) {
        out.append(" ".repeat(indent))
           .append(Ansi.style("-".repeat(Math.max(1, width - indent)), Ansi.DIM))
           .append('\n');
        blank();
    }

    @Override
    public void visit(Text text) {
        inline.append(text.getLiteral());
    }

    @Override
    public void visit(StrongEmphasis node) {
        inline.append(Ansi.style(collect(node), Ansi.BOLD));
    }

    @Override
    public void visit(Emphasis node) {
        inline.append(Ansi.style(collect(node), Ansi.ITALIC));
    }

    @Override
    public void visit(Code node) {
        inline.append(Ansi.style(node.getLiteral(), Ansi.YELLOW));
    }

    @Override
    public void visit(Link link) {
        inline.append(Ansi.style(collect(link), Ansi.UNDERLINE))
              .append(Ansi.style(" (" + link.getDestination() + ")", Ansi.DIM));
    }

    @Override
    public void visit(SoftLineBreak node) {
        inline.append(' ');
    }

    @Override
    public void visit(HardLineBreak node) {
        inline.append('\n');
    }

    @Override
    public void visit(CustomNode node) {
        if (node instanceof Strikethrough) {
            inline.append(Ansi.style(collect(node), Ansi.STRIKE));
        } else {
            visitChildren(node);
        }
    }

    @Override
    public void visit(CustomBlock block) {
        if (block instanceof TableBlock) {
            table(block);
        } else {
            visitChildren(block);
        }
    }

    private void table(CustomBlock block) {
        List<List<String>> rows = new ArrayList<>();
        collectRows(block, rows);
        if (rows.isEmpty()) {
            return;
        }
        int columns = rows.stream().mapToInt(List::size).max().orElse(0);
        int[] size = new int[columns];
        for (List<String> row : rows) {
            for (int c = 0; c < row.size(); c++) {
                size[c] = Math.max(size[c], Ansi.visibleLength(row.get(c)));
            }
        }
        for (int r = 0; r < rows.size(); r++) {
            line(rows.get(r), size);
            if (r == 0) {
                StringBuilder rule = new StringBuilder(" ".repeat(indent));
                for (int c = 0; c < columns; c++) {
                    rule.append("-".repeat(size[c] + 2)).append(c == columns - 1 ? "" : "+");
                }
                out.append(Ansi.style(rule.toString(), Ansi.DIM)).append('\n');
            }
        }
        blank();
    }

    private void collectRows(Node node, List<List<String>> rows) {
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof TableRow) {
                List<String> cells = new ArrayList<>();
                for (Node cell = child.getFirstChild(); cell != null; cell = cell.getNext()) {
                    String value = collect(cell);
                    cells.add(cell instanceof TableCell && ((TableCell) cell).isHeader()
                            ? Ansi.style(value, Ansi.BOLD) : value);
                }
                rows.add(cells);
            } else {
                collectRows(child, rows);
            }
        }
    }

    private void line(List<String> cells, int[] size) {
        StringBuilder row = new StringBuilder(" ".repeat(indent));
        for (int c = 0; c < size.length; c++) {
            String value = c < cells.size() ? cells.get(c) : "";
            row.append(' ').append(value)
               .append(" ".repeat(size[c] - Ansi.visibleLength(value))).append(' ');
            if (c < size.length - 1) {
                row.append(Ansi.style("|", Ansi.DIM));
            }
        }
        out.append(row).append('\n');
    }

    private String collect(Node node) {
        StringBuilder previous = inline;
        inline = new StringBuilder();
        visitChildren(node);
        String value = inline.toString();
        inline = previous;
        return value;
    }

    private void emit(String text, String firstPrefix, String restPrefix) {
        int usable = Math.max(1, width - indent - Ansi.visibleLength(firstPrefix));
        List<String> lines = new ArrayList<>();
        for (String segment : text.split("\n", -1)) {
            lines.addAll(Wrap.lines(segment, usable));
        }
        for (int i = 0; i < lines.size(); i++) {
            out.append(" ".repeat(indent))
               .append(i == 0 ? firstPrefix : restPrefix)
               .append(lines.get(i))
               .append('\n');
        }
    }

    private void blank() {
        out.append('\n');
    }
}
