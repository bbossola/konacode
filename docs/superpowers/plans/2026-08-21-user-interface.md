# User Interface Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give konacode a second user interface with line editing, history, input on more than one line, slash commands and rendered markdown. Keep the present interface for a pipe.

**Architecture:** A `Ui` interface with two implementations. A `Repl` owns the loop, so both share it. The markdown renderer lives in its own package. Nothing in `agent`, `tools`, `policy` or `llm` changes, except that `Conversation` becomes a class.

**Tech Stack:** Java 21, Maven, JLine 4.3.1, commonmark 0.30.0, JUnit 5, Mockito 5.23.0.

**Spec:** [2026-08-21-user-interface-design.md](../specs/2026-08-21-user-interface-design.md). Read it first.

**Baseline:** 108 tests pass. Build with this command. The default `java` here is 11, and `sdk use` does not work in a script.

```bash
JAVA_HOME=/home/bbossola/.sdkman/candidates/java/21.0.2-open mvn -q test
```

**Style:** `CLAUDE.md` holds two rules that this plan follows. Write in Simplified Technical English. Do not write a comment that repeats the code.

---

## File Structure

| File | Responsibility |
|---|---|
| `pom.xml` | modified — four new dependencies, two test dependencies, one surefire setting |
| `agent/Conversation.java` | **replaced** — the interface becomes a final class with `restart` |
| `agent/AppendOnlyConversation.java` | **deleted** |
| `cli/Ansi.java` | modified — becomes public, gains the codes the renderer needs |
| `cli/markdown/Wrap.java` | **new** — wraps styled text at a space |
| `cli/markdown/Markdown.java` | **new** — the entry point, `render(String, int)` |
| `cli/markdown/AnsiRenderer.java` | **new** — walks the commonmark tree |
| `cli/Spinner.java` | **new** — one daemon thread that draws and erases |
| `cli/Ui.java` | **new** — the seam |
| `cli/PlainUi.java` | **new** — the present behaviour |
| `cli/RichUi.java` | **new** — JLine |
| `cli/Repl.java` | **new** — the loop |
| `cli/Commands.java` | **new** — the slash commands |
| `cli/Main.java` | modified — wiring and selection only |
| `cli/ConsoleToolCallListener.java` | **deleted** — `Ui` absorbs it |

---

### Task 1: Dependencies and the build

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: Add the version properties**

In the `<properties>` block, after `<junit.version>`, add:

```xml
    <jline.version>4.3.1</jline.version>
    <commonmark.version>0.30.0</commonmark.version>
    <mockito.version>5.23.0</mockito.version>
```

Pin JLine at 4.3.1. Version 3.26.3 carries CVE-2026-56740 and CVE-2026-56741, and both are HIGH.

- [ ] **Step 2: Add the dependencies**

In the `<dependencies>` block, after the Jackson entry, add:

```xml
    <dependency>
      <groupId>org.jline</groupId>
      <artifactId>jline</artifactId>
      <version>${jline.version}</version>
    </dependency>
    <dependency>
      <groupId>org.commonmark</groupId>
      <artifactId>commonmark</artifactId>
      <version>${commonmark.version}</version>
    </dependency>
    <dependency>
      <groupId>org.commonmark</groupId>
      <artifactId>commonmark-ext-gfm-tables</artifactId>
      <version>${commonmark.version}</version>
    </dependency>
    <dependency>
      <groupId>org.commonmark</groupId>
      <artifactId>commonmark-ext-gfm-strikethrough</artifactId>
      <version>${commonmark.version}</version>
    </dependency>
    <dependency>
      <groupId>org.mockito</groupId>
      <artifactId>mockito-core</artifactId>
      <version>${mockito.version}</version>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.mockito</groupId>
      <artifactId>mockito-junit-jupiter</artifactId>
      <version>${mockito.version}</version>
      <scope>test</scope>
    </dependency>
```

- [ ] **Step 3: Stop the Mockito warning**

Mockito loads an agent into the running JVM. Java 21 prints a warning about this on every test
run. Replace the surefire plugin block with this one:

```xml
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-surefire-plugin</artifactId>
        <version>3.2.5</version>
        <configuration>
          <argLine>-XX:+EnableDynamicAgentLoading</argLine>
        </configuration>
      </plugin>
```

- [ ] **Step 4: Verify**

```bash
JAVA_HOME=/home/bbossola/.sdkman/candidates/java/21.0.2-open mvn test 2>&1 | grep -E "Tests run:.*Skipped: 0$|BUILD|WARNING.*[Aa]gent"
JAVA_HOME=/home/bbossola/.sdkman/candidates/java/21.0.2-open mvn dependency:tree | grep -E "jline|commonmark|mockito|kotlin"
```

Expected: 108 tests pass. No agent warning appears. The tree shows `jline:4.3.1`,
`commonmark:0.30.0`, both commonmark extensions, and Mockito at test scope.

**No `kotlin` line may appear.** Kotlin arrives only with Mordant, which konacode does not use.
See the spec for the reason.

- [ ] **Step 5: Commit**

```bash
git add pom.xml
git commit -m "build: add JLine, commonmark and Mockito"
```

---

### Task 2: Conversation becomes a class

**Files:**
- Delete: `src/main/java/dev/konacode/agent/AppendOnlyConversation.java`
- Replace: `src/main/java/dev/konacode/agent/Conversation.java`
- Modify: `src/test/java/dev/konacode/agent/AgentTest.java`
- Modify: `src/main/java/dev/konacode/cli/Main.java`
- Test: `src/test/java/dev/konacode/agent/ConversationTest.java`

- [ ] **Step 1: Write the failing test**

```java
package dev.konacode.agent;

import dev.konacode.llm.Message;
import dev.konacode.llm.Message.SystemMessage;
import dev.konacode.llm.Message.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConversationTest {

    @Test
    void keepsTheMessagesInTheOrderTheyArrive() {
        Conversation conversation = new Conversation(new SystemMessage("s"));
        conversation.add(new UserMessage("first"));
        conversation.add(new UserMessage("second"));

        assertEquals(3, conversation.messages().size());
        assertEquals(new UserMessage("second"), conversation.messages().get(2));
    }

    @Test
    void returnsACopyThatTheCallerCannotChange() {
        Conversation conversation = new Conversation(new SystemMessage("s"));

        assertThrows(UnsupportedOperationException.class,
                () -> conversation.messages().add(new UserMessage("x")));
    }

    @Test
    void restartRemovesEveryMessageAndAddsTheGivenOnes() {
        Conversation conversation = new Conversation(new SystemMessage("s"));
        conversation.add(new UserMessage("forget me"));

        conversation.restart(List.of(new SystemMessage("s")));

        assertEquals(List.of(new SystemMessage("s")), conversation.messages());
    }

    @Test
    void restartCopiesTheGivenListSoALaterChangeCannotReachIt() {
        Conversation conversation = new Conversation();
        List<Message> given = new ArrayList<>();
        given.add(new SystemMessage("s"));

        conversation.restart(given);
        given.clear();

        assertEquals(1, conversation.messages().size());
    }

    @Test
    void refusesANullMessage() {
        Conversation conversation = new Conversation();

        assertThrows(NullPointerException.class, () -> conversation.add(null));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
JAVA_HOME=/home/bbossola/.sdkman/candidates/java/21.0.2-open mvn -q test -Dtest=ConversationTest
```

Expected: COMPILATION ERROR. `Conversation` is an interface today, so `new Conversation(...)`
does not compile.

- [ ] **Step 3: Replace the interface with a class**

Delete `AppendOnlyConversation.java`. Replace the whole content of `Conversation.java` with:

```java
package dev.konacode.agent;

import dev.konacode.llm.Message;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The history of one session. The agent loop keeps no other state.
 *
 * <p>This is a class and not an interface. The pair {@link #messages()} and
 * {@link #restart(List)} covers every change to the history, so a caller reads all of it,
 * transforms it, and writes all of it back. Persistence, compaction and trimming all work that
 * way, and none of them needs a second implementation.
 */
public final class Conversation {

    private final List<Message> messages = new ArrayList<>();

    public Conversation(Message... initial) {
        Collections.addAll(messages, initial);
    }

    public void add(Message message) {
        messages.add(Objects.requireNonNull(message, "message"));
    }

    public List<Message> messages() {
        return List.copyOf(messages);
    }

    /**
     * Removes every message, then adds the given ones.
     *
     * <p>The caller decides what to keep, so this class holds no rule about the system message.
     * The command {@code /clear} keeps the system message. The command {@code /compact} keeps the
     * system message and a summary.
     */
    public void restart(List<Message> messages) {
        this.messages.clear();
        this.messages.addAll(List.copyOf(messages));
    }
}
```

`Agent.java` needs no change. The type has the same name and the same package.

- [ ] **Step 4: Fix the two callers**

In `AgentTest.java`, replace every `new AppendOnlyConversation(` with `new Conversation(`. Replace
the type `AppendOnlyConversation` with `Conversation` in the three places that declare a variable.

In `Main.java`, replace `new AppendOnlyConversation(new SystemMessage(SYSTEM_PROMPT))` with
`new Conversation(new SystemMessage(SYSTEM_PROMPT))`.

- [ ] **Step 5: Run the tests**

```bash
JAVA_HOME=/home/bbossola/.sdkman/candidates/java/21.0.2-open mvn -q test
```

Expected: BUILD SUCCESS, 113 tests. That is 108 plus 5.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/dev/konacode/agent src/test/java/dev/konacode/agent src/main/java/dev/konacode/cli/Main.java
git commit -m "refactor(agent): Conversation becomes a class with restart"
```

---

### Task 3: ANSI codes and word wrap

The renderer needs more codes than the three that exist. It also needs to measure the visible
width of styled text. An escape code takes bytes and takes no columns, so `String.length()` gives
the wrong answer for any styled string.

**Files:**
- Modify: `src/main/java/dev/konacode/cli/Ansi.java`
- Create: `src/main/java/dev/konacode/cli/markdown/Wrap.java`
- Test: `src/test/java/dev/konacode/cli/AnsiTest.java`
- Test: `src/test/java/dev/konacode/cli/markdown/WrapTest.java`

- [ ] **Step 1: Write the failing tests**

`src/test/java/dev/konacode/cli/AnsiTest.java`:

```java
package dev.konacode.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AnsiTest {

    @Test
    void putsTheCodeBeforeTheTextAndAResetAfterIt() {
        assertEquals(Ansi.BOLD + "hello" + Ansi.RESET, Ansi.style("hello", Ansi.BOLD));
    }

    @Test
    void appliesMoreThanOneCode() {
        assertEquals(Ansi.BOLD + Ansi.CYAN + "hi" + Ansi.RESET,
                Ansi.style("hi", Ansi.BOLD, Ansi.CYAN));
    }

    @Test
    void countsOnlyTheVisibleCharacters() {
        assertEquals(5, Ansi.visibleLength(Ansi.style("hello", Ansi.BOLD)));
    }

    @Test
    void removesEveryCode() {
        assertEquals("hello", Ansi.strip(Ansi.style("hello", Ansi.BOLD, Ansi.RED)));
    }

    @Test
    void leavesPlainTextAlone() {
        assertEquals(5, Ansi.visibleLength("plain"));
        assertEquals("plain", Ansi.strip("plain"));
    }
}
```

`src/test/java/dev/konacode/cli/markdown/WrapTest.java`:

```java
package dev.konacode.cli.markdown;

import dev.konacode.cli.Ansi;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WrapTest {

    @Test
    void breaksAtASpace() {
        assertEquals(List.of("one two", "three"), Wrap.lines("one two three", 8));
    }

    @Test
    void keepsAShortLineWhole() {
        assertEquals(List.of("short"), Wrap.lines("short", 40));
    }

    @Test
    void measuresTheVisibleWidthAndNotTheBytes() {
        String bold = Ansi.style("aaaa", Ansi.BOLD);

        assertEquals(1, Wrap.lines(bold + " bb", 8).size());
    }

    @Test
    void repeatsTheStyleAfterABreakSoTheSecondLineKeepsIt() {
        String text = Ansi.BOLD + "one two three" + Ansi.RESET;

        List<String> lines = Wrap.lines(text, 8);

        assertEquals(2, lines.size());
        assertTrue(lines.get(1).startsWith(Ansi.BOLD), lines.get(1));
    }

    @Test
    void breaksAWordThatIsLongerThanTheWidth() {
        List<String> lines = Wrap.lines("aaaaaaaaaaaa", 5);

        assertEquals(3, lines.size());
        assertEquals("aaaaa", lines.get(0));
    }

    @Test
    void returnsOneEmptyLineForEmptyText() {
        assertEquals(List.of(""), Wrap.lines("", 10));
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
JAVA_HOME=/home/bbossola/.sdkman/candidates/java/21.0.2-open mvn -q test -Dtest=AnsiTest,WrapTest
```

Expected: COMPILATION ERROR. `Ansi.style` and the class `Wrap` do not exist.

- [ ] **Step 3: Replace `Ansi.java`**

`Ansi` is package-private today. The renderer lives in a sub-package, and Java gives a
sub-package no extra access, so the class becomes public.

Type the escape as the six characters `\u001B`. Never paste a raw escape byte. It is
invisible in an editor and in a diff, and some tools refuse the file.

```java
package dev.konacode.cli;

import java.util.regex.Pattern;

public final class Ansi {

    public static final String RESET = "\u001B[0m";
    public static final String BOLD = "\u001B[1m";
    public static final String DIM = "\u001B[2m";
    public static final String ITALIC = "\u001B[3m";
    public static final String UNDERLINE = "\u001B[4m";
    public static final String STRIKE = "\u001B[9m";
    public static final String RED = "\u001B[31m";
    public static final String GREEN = "\u001B[32m";
    public static final String YELLOW = "\u001B[33m";
    public static final String BLUE = "\u001B[34m";
    public static final String MAGENTA = "\u001B[35m";
    public static final String CYAN = "\u001B[36m";

    private static final Pattern CODE = Pattern.compile("\u001B\\[[0-9;]*m");

    private Ansi() {
    }

    public static String style(String text, String... codes) {
        return String.join("", codes) + text + RESET;
    }

    public static String strip(String text) {
        return CODE.matcher(text).replaceAll("");
    }

    public static int visibleLength(String text) {
        return strip(text).length();
    }

    public static String blue(String text) {
        return style(text, BLUE);
    }

    public static String green(String text) {
        return style(text, GREEN);
    }
}
```

- [ ] **Step 4: Write `Wrap.java`**

```java
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
```

- [ ] **Step 5: Run the tests**

```bash
JAVA_HOME=/home/bbossola/.sdkman/candidates/java/21.0.2-open mvn -q test
```

Expected: BUILD SUCCESS, 124 tests. That is 113 plus 11.

- [ ] **Step 6: Check for a raw escape byte**

```bash
grep -c 'u001B' src/main/java/dev/konacode/cli/Ansi.java
grep -qP '[\x00-\x08\x0B\x0C\x0E-\x1F]' src/main/java/dev/konacode/cli/Ansi.java src/main/java/dev/konacode/cli/markdown/Wrap.java && echo "RAW BYTE - WRONG" || echo clean
```

Expected: 13 for the first command, and `clean` for the second.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/dev/konacode/cli src/test/java/dev/konacode/cli
git commit -m "feat(cli): ANSI codes and word wrap for styled text"
```

---

### Task 4: The markdown renderer

This is the largest task. The code below is not a sketch. I compiled it against
`commonmark 0.30.0` and ran it, and the tests below assert what it actually produced.

**Files:**
- Create: `src/main/java/dev/konacode/cli/markdown/Markdown.java`
- Create: `src/main/java/dev/konacode/cli/markdown/AnsiRenderer.java`
- Test: `src/test/java/dev/konacode/cli/markdown/MarkdownTest.java`

- [ ] **Step 1: Write the failing test**

```java
package dev.konacode.cli.markdown;

import dev.konacode.cli.Ansi;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownTest {

    /** The visible layout, with the codes and the trailing spaces removed. */
    private static List<String> layout(String markdown, int width) {
        return Arrays.stream(Markdown.render(markdown, width).split("\n", -1))
                .map(Ansi::strip)
                .map(String::stripTrailing)
                .toList();
    }

    @Test
    void putsAHeadingInBoldAndAColour() {
        assertTrue(Markdown.render("# Title", 40).startsWith(Ansi.BOLD + Ansi.CYAN));
        assertEquals(List.of("Title"), layout("# Title", 40));
    }

    @Test
    void marksBoldAndItalicText() {
        String rendered = Markdown.render("a **b** and *c*", 40);

        assertTrue(rendered.contains(Ansi.BOLD), rendered);
        assertTrue(rendered.contains(Ansi.ITALIC), rendered);
        assertEquals(List.of("a b and c"), layout("a **b** and *c*", 40));
    }

    @Test
    void putsACodeSpanInOneColour() {
        assertTrue(Markdown.render("call `read_file` now", 40).contains(Ansi.YELLOW));
    }

    @Test
    void marksStrikethrough() {
        assertTrue(Markdown.render("~~gone~~", 40).contains(Ansi.STRIKE));
    }

    @Test
    void showsTheAddressOfALink() {
        String rendered = Markdown.render("[the design](docs/design.md)", 40);

        assertTrue(rendered.contains(Ansi.UNDERLINE), rendered);
        assertEquals(List.of("the design (docs/design.md)"),
                layout("[the design](docs/design.md)", 40));
    }

    @Test
    void wrapsProseAtTheGivenWidth() {
        assertEquals(List.of("alpha beta gamma", "delta epsilon zeta"),
                layout("alpha beta gamma delta epsilon zeta", 20));
    }

    @Test
    void neverWrapsInsideACodeBlock() {
        String source = "```\nthis is a very long line of code that exceeds twenty\n```";

        assertEquals(List.of("  this is a very long line of code that exceeds twenty"),
                layout(source, 20));
    }

    @Test
    void putsNoBlankLineBetweenTheItemsOfAList() {
        assertEquals(List.of("- one", "- two", "- three"), layout("- one\n- two\n- three", 40));
    }

    @Test
    void numbersAnOrderedList() {
        assertEquals(List.of("1. first", "2. second"), layout("1. first\n2. second", 40));
    }

    @Test
    void alignsTheColumnsOfATable() {
        String source = "| A | Bee |\n|---|-----|\n| 1 | 22 |";

        assertEquals(List.of(" A | Bee", "---+-----", " 1 | 22"), layout(source, 40));
    }

    @Test
    void marksABlockQuoteWithABar() {
        assertEquals(List.of("  | quoted text here"), layout("> quoted text here", 40));
    }

    @Test
    void drawsAThematicBreakAcrossTheWidth() {
        assertEquals(List.of("-".repeat(20)), layout("---", 20));
    }

    @Test
    void putsOneBlankLineBetweenBlocks() {
        assertEquals(List.of("first", "", "second"), layout("first\n\nsecond", 40));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
JAVA_HOME=/home/bbossola/.sdkman/candidates/java/21.0.2-open mvn -q test -Dtest=MarkdownTest
```

Expected: COMPILATION ERROR. The class `Markdown` does not exist.

- [ ] **Step 3: Write `Markdown.java`**

```java
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
```

- [ ] **Step 4: Write `AnsiRenderer.java`**

```java
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
        inline.append(' ');
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
        List<String> lines = Wrap.lines(text, usable);
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
```

Two rules keep the blank lines correct. `emit` and `code` never add a blank line. Every top
level block adds one for itself. A list item calls `emit` directly, so the items of a list stay
together. This was wrong in my first version and every list had a blank line between the items.

- [ ] **Step 5: Run the tests**

```bash
JAVA_HOME=/home/bbossola/.sdkman/candidates/java/21.0.2-open mvn -q test
```

Expected: BUILD SUCCESS, 137 tests. That is 124 plus 13.

- [ ] **Step 6: Look at the output with your own eyes**

A test asserts the layout. It does not tell you whether the result looks right. Render a document
that uses every element and read it.

```bash
cat > /tmp/sample.md <<'EOM'
# konacode

It is a **Java CLI agent** that calls *tools*. A call looks like `read_file`.

| Tool | Purpose |
|------|---------|
| list_files | a directory |
| read_file | one file |

1. Send the whole conversation.
2. Run the tool, then repeat.

- ~~streaming~~ is out of scope
- see [the design](docs/design.md)

> A loop where the model decides when to stop deserves some doubt.

```java
public String respond(String userText) { }
```
EOM
```

Write a small class that reads the file and prints `Markdown.render(text, 72)`. Delete it
afterwards. Check that the table columns line up, that the list has no blank line between the
items, and that the code block keeps its long lines.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/dev/konacode/cli/markdown src/test/java/dev/konacode/cli/markdown
git commit -m "feat(cli): render markdown to the terminal"
```

---

### Task 5: The spinner

**Files:**
- Create: `src/main/java/dev/konacode/cli/Spinner.java`
- Test: `src/test/java/dev/konacode/cli/SpinnerTest.java`

- [ ] **Step 1: Write the failing test**

```java
package dev.konacode.cli;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpinnerTest {

    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(captured, true, StandardCharsets.UTF_8);

    private String written() {
        return captured.toString(StandardCharsets.UTF_8);
    }

    @Test
    void drawsTheLabelAfterItStarts() throws InterruptedException {
        Spinner spinner = new Spinner(out, "thinking");
        spinner.start();
        Thread.sleep(250);
        spinner.stop();

        assertTrue(written().contains("thinking"), written());
    }

    @Test
    void erasesTheLineWhenItStops() throws InterruptedException {
        Spinner spinner = new Spinner(out, "thinking");
        spinner.start();
        Thread.sleep(250);
        spinner.stop();

        assertTrue(written().endsWith("\r" + " ".repeat(Spinner.ERASE) + "\r"), "no erase");
    }

    @Test
    void writesNothingWhenItNeverStarts() {
        new Spinner(out, "thinking").stop();

        assertEquals("", written());
    }

    @Test
    void survivesTwoCallsToStopAndTwoCallsToStart() throws InterruptedException {
        Spinner spinner = new Spinner(out, "thinking");
        spinner.start();
        spinner.start();
        Thread.sleep(150);
        spinner.stop();
        spinner.stop();

        assertFalse(spinner.running());
    }

    @Test
    void stopsTheThread() throws InterruptedException {
        Spinner spinner = new Spinner(out, "thinking");
        spinner.start();
        Thread.sleep(150);
        assertTrue(spinner.running());

        spinner.stop();

        assertFalse(spinner.running());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
JAVA_HOME=/home/bbossola/.sdkman/candidates/java/21.0.2-open mvn -q test -Dtest=SpinnerTest
```

Expected: COMPILATION ERROR. The class `Spinner` does not exist.

- [ ] **Step 3: Write `Spinner.java`**

```java
package dev.konacode.cli;

import java.io.PrintStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Draws a moving character while the agent works.
 *
 * <p>The class is not final, so a test can give {@link RichUi} a subclass that records the calls.
 * The thread is a daemon thread, so it cannot keep the process alive.
 */
public class Spinner {

    static final int ERASE = 40;

    private static final char[] FRAMES = {'|', '/', '-', '\\'};
    private static final long PERIOD = 120;

    private final PrintStream out;
    private final String label;
    private final AtomicBoolean running = new AtomicBoolean();
    private Thread thread;

    public Spinner(PrintStream out, String label) {
        this.out = out;
        this.label = label;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        thread = new Thread(this::draw, "konacode-spinner");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        thread.interrupt();
        try {
            thread.join(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        out.print("\r" + " ".repeat(ERASE) + "\r");
        out.flush();
    }

    boolean running() {
        return running.get();
    }

    private void draw() {
        int frame = 0;
        while (running.get()) {
            out.print("\r" + Ansi.style(FRAMES[frame++ % FRAMES.length] + " " + label, Ansi.DIM));
            out.flush();
            try {
                Thread.sleep(PERIOD);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
```

- [ ] **Step 4: Run the tests**

```bash
JAVA_HOME=/home/bbossola/.sdkman/candidates/java/21.0.2-open mvn -q test
```

Expected: BUILD SUCCESS, 142 tests. That is 137 plus 5.

These five tests use `Thread.sleep`. That makes them slower than every other test in the suite,
and it makes them the first place to look if the suite becomes unreliable. Report it if one of
them fails once and passes the next time. Do not add a longer sleep to hide it.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/konacode/cli/Spinner.java src/test/java/dev/konacode/cli/SpinnerTest.java
git commit -m "feat(cli): a spinner on its own daemon thread"
```

---

### Task 6: The Ui seam and the plain implementation

**Files:**
- Create: `src/main/java/dev/konacode/cli/Ui.java`
- Create: `src/main/java/dev/konacode/cli/PlainUi.java`
- Delete: `src/main/java/dev/konacode/cli/ConsoleToolCallListener.java`
- Delete: `src/test/java/dev/konacode/cli/ConsoleToolCallListenerTest.java`
- Test: `src/test/java/dev/konacode/cli/PlainUiTest.java`

`PlainUi` must keep the present behaviour exactly. Read `Main.java` and
`ConsoleToolCallListener.java` first and copy what they print, character for character. Every
piped test depends on it.

- [ ] **Step 1: Write the failing test**

```java
package dev.konacode.cli;

import dev.konacode.tools.ToolResult;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlainUiTest {

    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(captured, true, StandardCharsets.UTF_8);

    private PlainUi ui(String input) {
        return new PlainUi(new BufferedReader(new StringReader(input)), out);
    }

    private String written() {
        return captured.toString(StandardCharsets.UTF_8);
    }

    @Test
    void readsALineAndPrintsThePrompt() {
        assertEquals(Optional.of("hello"), ui("hello\n").readLine());
        assertTrue(written().contains("You"), written());
    }

    @Test
    void returnsEmptyAtTheEndOfInput() {
        assertEquals(Optional.empty(), ui("").readLine());
    }

    @Test
    void printsTheBanner() {
        ui("").welcome();

        assertTrue(written().contains("Chat with konacode"), written());
    }

    @Test
    void printsTheAnswerAfterTheName() {
        ui("").showAnswer("two files here");

        assertTrue(Ansi.strip(written()).contains("konacode: two files here"), written());
    }

    @Test
    void printsOneLineForEachToolCall() {
        ui("").onToolCall("read_file", "{\"path\":\"pom.xml\"}");

        assertEquals("tool: read_file({\"path\":\"pom.xml\"})" + System.lineSeparator(), written());
    }

    @Test
    void printsNothingForAToolResult() {
        ui("").onToolResult("read_file", ToolResult.ok("content"));

        assertEquals("", written());
    }

    @Test
    void printsNothingWhenTheAgentStartsWork() {
        ui("").thinking();

        assertEquals("", written());
    }

    @Test
    void doesNotRenderMarkdown() {
        ui("").showAnswer("# not a heading");

        assertTrue(Ansi.strip(written()).contains("# not a heading"), written());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Expected: COMPILATION ERROR. The class `PlainUi` does not exist.

- [ ] **Step 3: Write `Ui.java`**

```java
package dev.konacode.cli;

import dev.konacode.agent.ToolCallListener;

import java.util.Optional;

/**
 * Everything konacode shows the user, and the one thing it reads from them.
 *
 * <p>This extends {@link ToolCallListener} because showing a tool call is a user interface
 * concern. One object then owns the screen, and the agent loop still never touches
 * {@code System.out}.
 */
public interface Ui extends ToolCallListener, AutoCloseable {

    void welcome();

    /** The next line the user typed. Empty when the session ends. */
    Optional<String> readLine();

    void showAnswer(String text);

    void showError(String message);

    /** The agent started work. An implementation may show progress. */
    void thinking();

    @Override
    default void close() {
    }
}
```

- [ ] **Step 4: Write `PlainUi.java`**

```java
package dev.konacode.cli;

import dev.konacode.tools.ToolResult;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * The interface for a pipe, and for a terminal that JLine cannot open.
 *
 * <p>It prints exactly what konacode printed before there were two interfaces. Every piped test
 * depends on that.
 */
final class PlainUi implements Ui {

    private final BufferedReader in;
    private final PrintStream out;

    PlainUi(BufferedReader in, PrintStream out) {
        this.in = in;
        this.out = out;
    }

    static PlainUi open() {
        return new PlainUi(
                new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)),
                System.out);
    }

    @Override
    public void welcome() {
        out.println();
        out.println("Chat with konacode (use 'ctrl-c' to quit)");
        out.println();
    }

    @Override
    public Optional<String> readLine() {
        out.print(Ansi.blue("You") + ": ");
        out.flush();
        try {
            return Optional.ofNullable(in.readLine());
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    @Override
    public void showAnswer(String text) {
        out.println(Ansi.green("konacode") + ": " + text);
    }

    @Override
    public void showError(String message) {
        out.println(Ansi.style(message, Ansi.RED));
    }

    @Override
    public void thinking() {
    }

    @Override
    public void onToolCall(String name, String argumentsJson) {
        out.println("tool: " + name + "(" + argumentsJson + ")");
    }

    @Override
    public void onToolResult(String name, ToolResult result) {
    }
}
```

- [ ] **Step 5: Delete the old listener**

Delete `ConsoleToolCallListener.java` and `ConsoleToolCallListenerTest.java`. `Ui` absorbs both.
`Main` will not compile until Task 9. That is expected. Comment out the two lines in `Main` that
build the listener and the agent, or leave `Main` broken and fix it in Task 9. Prefer the second,
and note it in the commit message.

**Do not** run the whole suite at this step. Run only `PlainUiTest`.

- [ ] **Step 6: Run the new test**

```bash
JAVA_HOME=/home/bbossola/.sdkman/candidates/java/21.0.2-open mvn -q test -Dtest=PlainUiTest
```

Expected: 8 tests pass. The rest of the suite does not compile yet, because `Main` refers to the
deleted class. Task 9 repairs it.

If you prefer a green suite at every step, do Task 6, Task 7 and Task 9 in one commit. Say so in
your report if you choose that.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/dev/konacode/cli src/test/java/dev/konacode/cli
git commit -m "feat(cli): the Ui seam and the plain implementation"
```

---

### Task 7: The loop and the commands

**Files:**
- Create: `src/main/java/dev/konacode/cli/Repl.java`
- Create: `src/main/java/dev/konacode/cli/Commands.java`
- Test: `src/test/java/dev/konacode/cli/RecordingUi.java`
- Test: `src/test/java/dev/konacode/cli/ReplTest.java`
- Test: `src/test/java/dev/konacode/cli/CommandsTest.java`

- [ ] **Step 1: Write the test double**

`RecordingUi.java`. `Ui` is our own type, so the double is hand-written.

```java
package dev.konacode.cli;

import dev.konacode.tools.ToolResult;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

final class RecordingUi implements Ui {

    private final Deque<String> lines = new ArrayDeque<>();
    final List<String> answers = new ArrayList<>();
    final List<String> errors = new ArrayList<>();
    final List<String> events = new ArrayList<>();

    RecordingUi(String... input) {
        Collections.addAll(lines, input);
    }

    @Override
    public void welcome() {
        events.add("welcome");
    }

    @Override
    public Optional<String> readLine() {
        return Optional.ofNullable(lines.poll());
    }

    @Override
    public void showAnswer(String text) {
        answers.add(text);
        events.add("answer");
    }

    @Override
    public void showError(String message) {
        errors.add(message);
        events.add("error");
    }

    @Override
    public void thinking() {
        events.add("thinking");
    }

    @Override
    public void onToolCall(String name, String argumentsJson) {
        events.add("tool:" + name);
    }

    @Override
    public void onToolResult(String name, ToolResult result) {
    }
}
```

- [ ] **Step 2: Write the failing tests**

`CommandsTest.java`:

```java
package dev.konacode.cli;

import dev.konacode.agent.Conversation;
import dev.konacode.llm.Message.SystemMessage;
import dev.konacode.llm.Message.UserMessage;
import dev.konacode.tools.ToolRegistry;
import dev.konacode.tools.Workspace;
import dev.konacode.tools.ListFiles;
import dev.konacode.tools.ReadFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandsTest {

    @TempDir
    Path root;

    private static final SystemMessage SYSTEM = new SystemMessage("You are konacode.");

    private Commands commands(RecordingUi ui, Conversation conversation) {
        Workspace workspace = new Workspace(root);
        return new Commands(conversation, SYSTEM,
                ToolRegistry.of(new ListFiles(workspace), new ReadFile(workspace)), ui);
    }

    @Test
    void handlesOnlyALineThatStartsWithASlash() {
        Commands commands = commands(new RecordingUi(), new Conversation(SYSTEM));

        assertTrue(commands.handles("/help"));
        assertFalse(commands.handles("help"));
        assertFalse(commands.handles("what files are here?"));
    }

    @Test
    void helpNamesEveryCommand() {
        RecordingUi ui = new RecordingUi();

        commands(ui, new Conversation(SYSTEM)).run("/help");

        String shown = String.join("\n", ui.answers);
        assertTrue(shown.contains("/help"), shown);
        assertTrue(shown.contains("/tools"), shown);
        assertTrue(shown.contains("/clear"), shown);
    }

    @Test
    void toolsNamesEveryTool() {
        RecordingUi ui = new RecordingUi();

        commands(ui, new Conversation(SYSTEM)).run("/tools");

        String shown = String.join("\n", ui.answers);
        assertTrue(shown.contains("list_files"), shown);
        assertTrue(shown.contains("read_file"), shown);
    }

    @Test
    void clearKeepsTheSystemMessageAndRemovesTheRest() {
        Conversation conversation = new Conversation(SYSTEM);
        conversation.add(new UserMessage("forget me"));
        RecordingUi ui = new RecordingUi();

        commands(ui, conversation).run("/clear");

        assertEquals(1, conversation.messages().size());
        assertEquals(SYSTEM, conversation.messages().get(0));
    }

    @Test
    void refusesAnUnknownCommand() {
        RecordingUi ui = new RecordingUi();

        commands(ui, new Conversation(SYSTEM)).run("/tolos");

        assertEquals(1, ui.errors.size());
        assertTrue(ui.errors.get(0).contains("/tolos"), ui.errors.get(0));
    }
}
```

`ReplTest.java`:

```java
package dev.konacode.cli;

import dev.konacode.agent.Agent;
import dev.konacode.agent.Conversation;
import dev.konacode.llm.LlmClient;
import dev.konacode.llm.Message;
import dev.konacode.llm.Message.AssistantMessage;
import dev.konacode.llm.Message.SystemMessage;
import dev.konacode.llm.ToolSpec;
import dev.konacode.policy.AllowAllPolicy;
import dev.konacode.tools.ToolRegistry;
import dev.konacode.tools.Workspace;
import dev.konacode.tools.ListFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplTest {

    @TempDir
    Path root;

    private static final SystemMessage SYSTEM = new SystemMessage("You are konacode.");

    private Repl repl(RecordingUi ui) {
        LlmClient client = (history, tools) -> new AssistantMessage("the answer", List.of());
        Conversation conversation = new Conversation(SYSTEM);
        ToolRegistry registry = ToolRegistry.of(new ListFiles(new Workspace(root)));
        Agent agent = new Agent(client, registry, new AllowAllPolicy(), conversation, ui, 8);
        return new Repl(agent, ui, new Commands(conversation, SYSTEM, registry, ui));
    }

    @Test
    void showsTheBannerBeforeItReadsAnything() {
        RecordingUi ui = new RecordingUi();

        repl(ui).run();

        assertEquals("welcome", ui.events.get(0));
    }

    @Test
    void sendsTheLineToTheAgentAndShowsTheAnswer() {
        RecordingUi ui = new RecordingUi("hello");

        repl(ui).run();

        assertEquals(List.of("the answer"), ui.answers);
    }

    @Test
    void tellsTheInterfaceThatWorkStartedBeforeItAsksTheAgent() {
        RecordingUi ui = new RecordingUi("hello");

        repl(ui).run();

        assertTrue(ui.events.indexOf("thinking") < ui.events.indexOf("answer"), ui.events.toString());
    }

    @Test
    void skipsAnEmptyLine() {
        RecordingUi ui = new RecordingUi("", "   ", "hello");

        repl(ui).run();

        assertEquals(1, ui.answers.size());
    }

    @Test
    void stopsAtTheEndOfInput() {
        RecordingUi ui = new RecordingUi("one", "two");

        repl(ui).run();

        assertEquals(2, ui.answers.size());
    }

    @Test
    void sendsACommandToTheCommandsAndNotToTheAgent() {
        RecordingUi ui = new RecordingUi("/help");

        repl(ui).run();

        assertTrue(ui.events.stream().noneMatch("thinking"::equals), ui.events.toString());
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Expected: COMPILATION ERROR. `Repl` and `Commands` do not exist.

- [ ] **Step 4: Write `Commands.java`**

```java
package dev.konacode.cli;

import dev.konacode.agent.Conversation;
import dev.konacode.llm.Message;
import dev.konacode.tools.Tool;
import dev.konacode.tools.ToolRegistry;

import java.util.List;

final class Commands {

    private final Conversation conversation;
    private final Message systemMessage;
    private final ToolRegistry registry;
    private final Ui ui;

    Commands(Conversation conversation, Message systemMessage, ToolRegistry registry, Ui ui) {
        this.conversation = conversation;
        this.systemMessage = systemMessage;
        this.registry = registry;
        this.ui = ui;
    }

    boolean handles(String line) {
        return line.startsWith("/");
    }

    void run(String line) {
        switch (line) {
            case "/help" -> help();
            case "/tools" -> tools();
            case "/clear" -> clear();
            default -> ui.showError("Unknown command: " + line + ". Type /help for the list.");
        }
    }

    private void help() {
        ui.showAnswer("""
                /help    show this list
                /tools   show the tools the model can call
                /clear   forget the conversation and start again""");
    }

    private void tools() {
        StringBuilder text = new StringBuilder();
        for (Tool tool : registry.all()) {
            text.append(tool.name()).append("\n  ")
                .append(tool.description().replace("\n", " ")).append("\n");
        }
        ui.showAnswer(text.toString().stripTrailing());
    }

    private void clear() {
        conversation.restart(List.of(systemMessage));
        ui.showAnswer("The conversation is empty.");
    }
}
```

- [ ] **Step 5: Write `Repl.java`**

```java
package dev.konacode.cli;

import dev.konacode.agent.Agent;

final class Repl {

    private final Agent agent;
    private final Ui ui;
    private final Commands commands;

    Repl(Agent agent, Ui ui, Commands commands) {
        this.agent = agent;
        this.ui = ui;
        this.commands = commands;
    }

    void run() {
        ui.welcome();
        for (var line = ui.readLine(); line.isPresent(); line = ui.readLine()) {
            String text = line.get().trim();
            if (text.isEmpty()) {
                continue;
            }
            if (commands.handles(text)) {
                commands.run(text);
                continue;
            }
            ui.thinking();
            ui.showAnswer(agent.respond(text));
        }
    }
}
```

Both interfaces share this loop. Neither one repeats the empty line rule, the end of input rule,
or the command rule.

- [ ] **Step 6: Run the tests**

```bash
JAVA_HOME=/home/bbossola/.sdkman/candidates/java/21.0.2-open mvn -q test -Dtest=ReplTest,CommandsTest,PlainUiTest
```

Expected: 19 tests pass. The whole suite still does not compile, because `Main` refers to the
class deleted in Task 6.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/dev/konacode/cli src/test/java/dev/konacode/cli
git commit -m "feat(cli): the loop and the slash commands"
```

---

### Task 8: The rich interface

Every JLine call below is verified. I read the API from `jline-4.3.1.jar` with `javap`.
`KeyMap.alt(String)`, `Reference(String)`, `LineReader.SELF_INSERT_UNMETA`, `LineReader.MAIN`,
`LineReader.HISTORY_FILE`, `LineReaderBuilder.variable`, `TerminalBuilder.system` and
`Terminal.getWidth` all exist with these signatures.

**Files:**
- Create: `src/main/java/dev/konacode/cli/RichUi.java`
- Test: `src/test/java/dev/konacode/cli/RichUiTest.java`

- [ ] **Step 1: Write the failing test**

```java
package dev.konacode.cli;

import dev.konacode.tools.ToolResult;
import org.jline.reader.EndOfFileException;
import org.jline.reader.History;
import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RichUiTest {

    /** Spinner is our own type, so the double is hand-written. */
    static final class RecordingSpinner extends Spinner {
        final List<String> calls = new ArrayList<>();

        RecordingSpinner() {
            super(new PrintStream(OutputStream.nullOutputStream()), "thinking");
        }

        @Override
        public void start() {
            calls.add("start");
        }

        @Override
        public void stop() {
            calls.add("stop");
        }
    }

    @Mock
    LineReader reader;

    @Mock
    Terminal terminal;

    @Mock
    History history;

    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(captured, true, StandardCharsets.UTF_8);
    private final RecordingSpinner spinner = new RecordingSpinner();

    private RichUi ui() {
        when(terminal.getWidth()).thenReturn(40);
        when(reader.getHistory()).thenReturn(history);
        return new RichUi(reader, terminal, out, spinner);
    }

    private String written() {
        return Ansi.strip(captured.toString(StandardCharsets.UTF_8));
    }

    @Test
    void returnsTheLineTheUserTyped() {
        when(reader.readLine(anyString())).thenReturn("what files are here?");

        assertEquals(Optional.of("what files are here?"), ui().readLine());
    }

    @Test
    void endsTheSessionWhenTheUserPressesCtrlD() {
        when(reader.readLine(anyString())).thenThrow(new EndOfFileException());

        assertEquals(Optional.empty(), ui().readLine());
    }

    @Test
    void givesAnEmptyLineWhenTheUserPressesCtrlC() {
        when(reader.readLine(anyString())).thenThrow(new UserInterruptException(""));

        assertEquals(Optional.of(""), ui().readLine());
    }

    @Test
    void rendersTheAnswerToTheWidthTheTerminalReports() {
        when(terminal.getWidth()).thenReturn(20);
        when(reader.getHistory()).thenReturn(history);

        new RichUi(reader, terminal, out, spinner)
                .showAnswer("alpha beta gamma delta epsilon zeta");

        assertTrue(written().contains("alpha beta gamma\ndelta epsilon zeta"), written());
    }

    @Test
    void rendersMarkdown() {
        ui().showAnswer("a **bold** word");

        assertTrue(captured.toString(StandardCharsets.UTF_8).contains(Ansi.BOLD));
    }

    @Test
    void startsTheSpinnerWhenTheAgentBeginsWork() {
        ui().thinking();

        assertEquals(List.of("start"), spinner.calls);
    }

    @Test
    void stopsTheSpinnerBeforeItPrintsTheAnswer() {
        RichUi ui = ui();
        ui.thinking();
        ui.showAnswer("done");

        assertEquals(List.of("start", "stop"), spinner.calls);
    }

    @Test
    void stopsTheSpinnerForAToolCallAndStartsItAfterTheResult() {
        RichUi ui = ui();
        ui.thinking();
        ui.onToolCall("read_file", "{}");
        ui.onToolResult("read_file", ToolResult.ok("content"));

        assertEquals(List.of("start", "stop", "start"), spinner.calls);
        assertTrue(written().contains("tool: read_file({})"), written());
    }

    @Test
    void savesTheHistoryAndClosesTheTerminalAtTheEnd() throws Exception {
        ui().close();

        verify(history).save();
        verify(terminal).close();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Expected: COMPILATION ERROR. The class `RichUi` does not exist.

- [ ] **Step 3: Write `RichUi.java`**

```java
package dev.konacode.cli;

import dev.konacode.cli.markdown.Markdown;
import dev.konacode.tools.ToolResult;
import org.jline.keymap.KeyMap;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.Reference;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The interface for a real terminal. JLine gives the line editing, the history and the input on
 * more than one line.
 *
 * <p>The constructor takes every collaborator, and {@link #open()} builds the real ones. A test
 * therefore gives this class a mocked reader and terminal, and a spinner that records.
 */
final class RichUi implements Ui {

    private final LineReader reader;
    private final Terminal terminal;
    private final PrintStream out;
    private final Spinner spinner;

    RichUi(LineReader reader, Terminal terminal, PrintStream out, Spinner spinner) {
        this.reader = reader;
        this.terminal = terminal;
        this.out = out;
        this.spinner = spinner;
    }

    static RichUi open() throws IOException {
        Terminal terminal = TerminalBuilder.builder().system(true).build();

        Path history = Path.of(System.getProperty("user.home"), ".konacode", "chat_history");
        Files.createDirectories(history.getParent());

        LineReader reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .variable(LineReader.HISTORY_FILE, history)
                .build();

        reader.getKeyMaps()
                .get(LineReader.MAIN)
                .bind(new Reference(LineReader.SELF_INSERT_UNMETA), KeyMap.alt("\r"));

        return new RichUi(reader, terminal, System.out, new Spinner(System.out, "thinking"));
    }

    @Override
    public void welcome() {
        out.println();
        out.println(Ansi.style("konacode", Ansi.BOLD, Ansi.CYAN)
                + Ansi.style("  ctrl-d quits, alt-enter adds a line, /help lists the commands",
                Ansi.DIM));
        out.println();
    }

    @Override
    public Optional<String> readLine() {
        try {
            return Optional.ofNullable(reader.readLine(Ansi.blue("You") + ": "));
        } catch (UserInterruptException e) {
            return Optional.of("");
        } catch (EndOfFileException e) {
            return Optional.empty();
        }
    }

    @Override
    public void showAnswer(String text) {
        spinner.stop();
        out.println(Ansi.style("konacode", Ansi.BOLD, Ansi.GREEN));
        out.println(Markdown.render(text, terminal.getWidth()));
        out.println();
    }

    @Override
    public void showError(String message) {
        spinner.stop();
        out.println(Ansi.style(message, Ansi.RED));
    }

    @Override
    public void thinking() {
        spinner.start();
    }

    @Override
    public void onToolCall(String name, String argumentsJson) {
        spinner.stop();
        out.println(Ansi.style("tool: " + name + "(" + argumentsJson + ")", Ansi.DIM));
    }

    @Override
    public void onToolResult(String name, ToolResult result) {
        spinner.start();
    }

    @Override
    public void close() {
        spinner.stop();
        try {
            reader.getHistory().save();
            terminal.close();
        } catch (IOException e) {
            out.println(Ansi.style("Could not close the terminal: " + e.getMessage(), Ansi.RED));
        }
    }
}
```

`Alt+Enter` uses `self-insert-unmeta`. That widget removes the meta from the key and inserts what
remains, so `Alt+Enter` inserts a newline into the buffer and `Enter` still sends the message.
This is the standard readline binding for this behaviour.

The spinner and the main thread both write to one terminal. Every method that writes stops the
spinner first. `onToolResult` starts it again, because the loop returns to the model after a
tool runs.

- [ ] **Step 4: Run the tests**

```bash
JAVA_HOME=/home/bbossola/.sdkman/candidates/java/21.0.2-open mvn -q test -Dtest=RichUiTest
```

Expected: 9 tests pass. The whole suite still does not compile until Task 9.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/konacode/cli/RichUi.java src/test/java/dev/konacode/cli/RichUiTest.java
git commit -m "feat(cli): a rich interface with line editing, history and markdown"
```

---

### Task 9: Main chooses the interface

**Files:**
- Modify: `src/main/java/dev/konacode/cli/Main.java`
- Test: `src/test/java/dev/konacode/cli/MainTest.java`

- [ ] **Step 1: Write the failing test**

```java
package dev.konacode.cli;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainTest {

    @AfterEach
    void clearTheProperty() {
        System.clearProperty("konacode.ui");
    }

    @Test
    void choosesThePlainInterfaceWhenAsked() throws Exception {
        System.setProperty("konacode.ui", "plain");

        assertInstanceOf(PlainUi.class, Main.selectUi());
    }

    @Test
    void choosesThePlainInterfaceForAPipe() throws Exception {
        System.setProperty("konacode.ui", "auto");

        assertInstanceOf(PlainUi.class, Main.selectUi());
    }

    @Test
    void defaultsToAuto() throws Exception {
        assertInstanceOf(PlainUi.class, Main.selectUi());
    }

    @Test
    void refusesAValueItCannotRead() {
        System.setProperty("konacode.ui", "rihc");

        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, Main::selectUi);

        assertTrue(thrown.getMessage().contains("rihc"), thrown.getMessage());
    }
}
```

The suite runs with no terminal, so `System.console()` returns null and `auto` gives `PlainUi`.
There is no test for `rich`, because that path needs a real terminal. Task 11 exercises it by
hand.

- [ ] **Step 2: Run the test to verify it fails**

Expected: COMPILATION ERROR. `Main.selectUi` does not exist.

- [ ] **Step 3: Rewrite `Main.java`**

```java
package dev.konacode.cli;

import dev.konacode.agent.Agent;
import dev.konacode.agent.Conversation;
import dev.konacode.llm.Message.SystemMessage;
import dev.konacode.llm.openai.OpenAiClient;
import dev.konacode.llm.openai.OpenAiConfig;
import dev.konacode.policy.AllowAllPolicy;
import dev.konacode.tools.EditFile;
import dev.konacode.tools.ListFiles;
import dev.konacode.tools.ReadFile;
import dev.konacode.tools.ToolRegistry;
import dev.konacode.tools.Workspace;

import java.io.IOException;

public final class Main {

    private static final String SYSTEM_PROMPT = "You are konacode, a concise CLI assistant.";

    private Main() {
    }

    public static void main(String[] args) {
        OpenAiConfig config;
        int maxIterations;
        Ui ui;
        try {
            config = OpenAiConfig.fromEnvironment(System.getenv());
            maxIterations = Agent.configuredMaxIterations();
            ui = selectUi();
        } catch (IllegalArgumentException | IOException e) {
            System.err.println(e.getMessage());
            System.exit(1);
            return;
        }

        Workspace workspace = Workspace.ofCurrentDirectory();
        ToolRegistry registry = ToolRegistry.of(
                new ListFiles(workspace), new ReadFile(workspace), new EditFile(workspace));
        SystemMessage system = new SystemMessage(SYSTEM_PROMPT);
        Conversation conversation = new Conversation(system);

        Agent agent = new Agent(new OpenAiClient(config), registry, new AllowAllPolicy(),
                conversation, ui, maxIterations);

        try (ui) {
            new Repl(agent, ui, new Commands(conversation, system, registry, ui)).run();
        } catch (Exception e) {
            System.err.println(e.getMessage());
            System.exit(1);
        }
    }

    static Ui selectUi() throws IOException {
        String choice = System.getProperty("konacode.ui", "auto");
        return switch (choice) {
            case "plain" -> PlainUi.open();
            case "rich" -> RichUi.open();
            case "auto" -> System.console() == null ? PlainUi.open() : openRichOrFallBack();
            default -> throw new IllegalArgumentException(
                    "konacode.ui must be auto, plain or rich, but was: " + choice);
        };
    }

    private static Ui openRichOrFallBack() {
        try {
            return RichUi.open();
        } catch (IOException e) {
            return PlainUi.open();
        }
    }
}
```

`rich` reports the failure and stops. `auto` falls back without a word, because the user asked
konacode to choose.

- [ ] **Step 4: Run the whole suite**

```bash
JAVA_HOME=/home/bbossola/.sdkman/candidates/java/21.0.2-open mvn -q test
```

Expected: BUILD SUCCESS, 173 tests. The suite compiles again.

That number is 142 after Task 5, plus 8 for `PlainUiTest`, minus 1 for the deleted
`ConsoleToolCallListenerTest`, plus 11 for `ReplTest` and `CommandsTest`, plus 9 for
`RichUiTest`, plus 4 for `MainTest`. 142 + 8 - 1 + 11 + 9 + 4 = 173. Report the number you see. Do not change a test to reach it.

- [ ] **Step 5: Check the piped path still works**

```bash
JAVA_HOME=/home/bbossola/.sdkman/candidates/java/21.0.2-open mvn -q package
env -u OPENAI_API_KEY PATH=/home/bbossola/.sdkman/candidates/java/21.0.2-open/bin:$PATH \
  java -jar target/konacode.jar </dev/null; echo "exit=$?"
```

Expected: `OPENAI_API_KEY is not set.` and `exit=1`.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/dev/konacode/cli/Main.java src/test/java/dev/konacode/cli/MainTest.java
git commit -m "feat(cli): choose the interface, and wire the two of them"
```

---

### Task 10: The documents

Five documents state something that this change makes false. The spec lists them.

**Files:** `CLAUDE.md`, `CONTEXT.md`, `ARCHITECTURE.md`, `FOLLOWUP.md`, `README.md`

- [ ] **Step 1: `CONTEXT.md`**

Find the row that says `| Extension seams | All four: tools, LLM provider, conversation, tool policy | ...`.
Change it to three seams: tools, the LLM provider, and the tool policy. Add one sentence. The
conversation is a plain class, because `messages()` and `restart(List)` together cover every
change to the history.

- [ ] **Step 2: `ARCHITECTURE.md`**

Find the sentence *"Every one of those collaborators is an interface with a default
implementation."* It is false now. Replace it. Three of them are interfaces. `Conversation` is a
class, and the caller transforms the history through `messages()` and `restart(List)`.

- [ ] **Step 3: `FOLLOWUP.md`**

Find the entry that begins *"**Conversation trimming.** `Conversation` is an interface precisely
so..."*. That reason is gone. Rewrite it. `/compact` is the manual form of this, and it needs the
`LlmClient` for the summary, so it is still deferred.

- [ ] **Step 4: `CLAUDE.md`**

Replace the two `Conversation` rows with one row for the class. Add rows for `Ui`, `PlainUi`,
`RichUi`, `Repl`, `Commands`, `Spinner` and `Markdown`. Remove the row for
`ConsoleToolCallListener`. Add `konacode.ui` to the configuration table, and state the rule: the
environment configures the provider, and a system property configures konacode.

- [ ] **Step 5: `README.md`**

Add `-Dkonacode.ui=auto|plain|rich` to the configuration section. Say that konacode detects a
terminal, that a pipe gets the plain interface, and that the rich interface gives line editing,
history in `~/.konacode/chat_history`, `alt-enter` for a second line, and rendered markdown. Add
`/help`, `/tools` and `/clear`.

- [ ] **Step 6: Update the test count**

`CLAUDE.md` and `CONTEXT.md` both name a number of tests. Set both to the number the suite
reports.

- [ ] **Step 7: Commit**

```bash
git add CLAUDE.md CONTEXT.md ARCHITECTURE.md FOLLOWUP.md README.md
git commit -m "docs: two interfaces, three seams"
```

---

### Task 11: Use it

A test tells you the layout is right. It does not tell you the interface is pleasant. This task
needs a human at a real terminal.

**Files:** none.

- [ ] **Step 1: Start it against the local model**

```bash
JAVA_HOME=/home/bbossola/.sdkman/candidates/java/21.0.2-open mvn -q package
cd /home/bbossola/projects/ai/konacode
OPENAI_API_KEY=ollama KONACODE_BASE_URL=http://localhost:11434/v1 KONACODE_MODEL=qwen3-coder \
  PATH=/home/bbossola/.sdkman/candidates/java/21.0.2-open/bin:$PATH \
  java -jar target/konacode.jar
```

- [ ] **Step 2: Check each promise**

| Check | Expected |
|---|---|
| Press the left arrow and the right arrow | The cursor moves. No `^[[D` appears. |
| Type a line, press Enter, press the up arrow | The old line returns. |
| Press `ctrl-a` and `ctrl-e` | The cursor goes to the start and to the end. |
| Press `alt-enter` | The line continues. Enter then sends both lines. |
| Press `ctrl-c` at the prompt | The line clears. The session continues. |
| Press `ctrl-d` at the prompt | The session ends. |
| Ask a question | A spinner turns while the model thinks. It disappears cleanly. |
| Ask for a table | The columns line up. |
| Read `~/.konacode/chat_history` | It holds the lines you typed. |

- [ ] **Step 3: Look for a stray spinner character**

Ask something that calls a tool, for example `what files are here?`. Watch the tool line appear.
The spinner must not leave a character in the middle of that line. This is the one part of the
design with two threads, so it is the one to watch.

- [ ] **Step 4: Check the plain interface still works**

```bash
echo "what files are here?" | env OPENAI_API_KEY=ollama \
  KONACODE_BASE_URL=http://localhost:11434/v1 KONACODE_MODEL=qwen3-coder \
  PATH=/home/bbossola/.sdkman/candidates/java/21.0.2-open/bin:$PATH \
  java -jar target/konacode.jar
```

Expected: the same output as before this change. No markdown, no spinner, one `tool:` line for
each call.

- [ ] **Step 5: Check the property**

```bash
java -Dkonacode.ui=rihc -jar target/konacode.jar
```

Expected: one line naming the wrong value, and exit 1.

- [ ] **Step 6: Report**

Say what looked wrong. A layout that a test accepts can still read badly.

---

## Done

`mvn test` is green. konacode has two interfaces. A pipe gets the old one, and a terminal gets
line editing, history, input on more than one line, slash commands and rendered markdown.

What remains is in [FOLLOWUP.md](../../../FOLLOWUP.md). The two entries from this work are
`/compact`, and the change to Mordant when Kotlin 2.4.20 reaches a stable release.
