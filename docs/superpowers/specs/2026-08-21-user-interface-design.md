# User interface — design

**Date:** 2026-08-21
**Status:** approved, pending implementation plan

## Problem

The user interface lives inside `Main`. It reads a line with `BufferedReader.readLine()`. This
leaves the terminal in canonical mode. The kernel collects the line and gives it to konacode when
the user presses Enter.

Canonical mode gives the user backspace and `ctrl-u`. It gives no cursor movement and no history.
An arrow key sends three bytes, `ESC [ A`. The kernel passes them through. They arrive in the
string as the literal text `^[[A`.

This is not a fault in the loop. konacode has no line editor. A line editor is the thing we add.

## Scope

konacode gets these:

- Line editing and history
- Input on more than one line
- Slash commands
- Rendered markdown output

konacode does not get these:

- Streaming output. The method `LlmClient.chat` returns a complete `AssistantMessage`. To stream,
  we must change the provider interface. That is separate work.
- Interruption of a turn. The agent loop cannot stop in the middle. That is separate work.

## Dependencies

konacode takes six new artifacts. Two of them are for tests only. The artifact `org.jline:jline` is the bundle. It contains the reader and the terminal.

| Artifact | Version | Audit |
|---|---|---|
| `org.jline:jline` | 4.3.1 | clean |
| `org.commonmark:commonmark` | 0.30.0 | clean |
| `org.commonmark:commonmark-ext-gfm-tables` | 0.30.0 | clean |
| `org.commonmark:commonmark-ext-gfm-strikethrough` | 0.30.0 | clean |
| `org.mockito:mockito-core` | 5.23.0 | clean, test scope |
| `org.mockito:mockito-junit-jupiter` | 5.23.0 | clean, test scope |

I audited every artifact with Meterian. Every one is clean.

**Pin JLine at 4.x.** Version 3.26.3 carries two HIGH severity faults, CVE-2026-56740 and
CVE-2026-56741. The safe versions are 3.30.16 and 4.3.1. The search endpoint at
`search.maven.org` reports 3.26.3 as the newest version. That report is wrong, because the
endpoint sorts by relevance. Read `maven-metadata.xml` instead.

`CLAUDE.md` says today: *"Dependencies: Jackson and JUnit 5. Nothing else."* That sentence must
change. The rule it protects is about the agent. konacode has no agent framework, no HTTP client
library, and no dependency injection container. A terminal library and a markdown parser hide
nothing about how an agent works.

commonmark parses markdown. It does not draw markdown. konacode writes the renderer.

## Configuration

konacode keeps one rule for configuration:

> The environment configures the provider. A system property configures konacode.

| Name | Kind | Purpose |
|---|---|---|
| `OPENAI_API_KEY` | environment | the key |
| `KONACODE_MODEL` | environment | the model |
| `KONACODE_BASE_URL` | environment | the endpoint |
| `konacode.maxIterations` | property | the ceiling on tool iterations |
| `konacode.ui` | property | `auto`, `plain`, or `rich` |

This rule keeps the key out of the process list. konacode reads no command line argument.

`konacode.ui` defaults to `auto`. In `auto` mode konacode uses the rich interface when
`System.console()` returns a terminal. It uses the plain interface in every other case. A pipe
therefore gets the plain interface, and every piped test keeps working.

A wrong value fails loudly. `-Dkonacode.ui=rihc` prints one line and exits 1. This matches
`konacode.maxIterations`, which already refuses a value it cannot read.

`-Dkonacode.ui=rich` with no terminal also fails loudly. The user asked for something konacode
cannot give.

## The conversation becomes a class

`Conversation` is an interface today. `AppendOnlyConversation` implements it. konacode deletes
both and writes one final class.

```java
public final class Conversation {

    private final List<Message> messages = new ArrayList<>();

    public Conversation(Message... initial) { ... }

    public void add(Message message) { ... }

    public List<Message> messages() { ... }

    public void restart(List<Message> messages) { ... }
}
```

`restart` removes every message. It then adds the given messages. It copies the given list, so a
later change to that list cannot reach the internal one.

Two commands need `restart`. The command `/clear` calls
`conversation.restart(List.of(systemMessage))`. The command `/compact` will call
`conversation.restart(List.of(systemMessage, summary))`. One method serves both. The caller
decides what to keep, so the class holds no hidden rule about the system message.

konacode needs no other implementation. The pair `messages()` and `restart(List)` covers every
change to the history. A caller reads all of it, transforms it, and writes all of it back.
Persistence, compaction, and trimming all work this way, and none of them changes the class.

**konacode now has three extension seams, and not four.** They are the tools, the LLM provider,
and the tool policy. The conversation is a plain data structure. Four documents state four seams
today. All four must change.

## The user interface seam

```java
package dev.konacode.cli;

public interface Ui extends ToolCallListener, AutoCloseable {

    void welcome();

    Optional<String> readLine();

    void showAnswer(String text);

    void showError(String message);

    void thinking();

    @Override
    default void close() {}
}
```

`Ui` extends `ToolCallListener` because the class `ConsoleToolCallListener` is already a user
interface concern with a different name. It exists so the `Agent` never touches `System.out`.
That is what a `Ui` is for. One object then owns the screen.

`Ui` extends `AutoCloseable` because JLine holds a real terminal and must give it back. The plain
implementation takes the empty default and pays nothing.

`readLine` returns an empty `Optional` when the session ends. This happens at end of input, or
when the user presses `ctrl-d`.

`thinking` tells the interface that the agent started work. The rich interface starts a spinner.
The plain interface does nothing.

## The loop moves out of Main

```java
final class Repl {

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

Both interfaces then share the empty line rule, the end of input rule, and the command rule.
`Main` keeps the wiring and picks the implementation.

## Commands

```java
final class Commands {
    boolean handles(String line);
    void run(String line);
}
```

`handles` returns true when the line starts with `/`.

| Command | Action |
|---|---|
| `/help` | Prints every command and one line for each. |
| `/tools` | Prints the name and the description of every tool in the registry. |
| `/clear` | Calls `conversation.restart(List.of(systemMessage))`. |

An unknown command prints an error through `ui.showError`. It does not reach the model. A user
who types `/tolos` gets a message, and not a request to the provider.

`Commands` holds the `Conversation`, the system message, the `ToolRegistry`, and the `Ui`. `Main`
builds the `Conversation` and gives the same object to the `Agent` and to `Commands`.

`/compact` comes later. It must ask the model for a summary, so it needs the `LlmClient`. This
specification does not cover it.

## The plain interface

`PlainUi` keeps the present behaviour exactly. It reads with a `BufferedReader`. It prints
`You: ` and `konacode: ` with the three existing colours. It prints one line for each tool call.
It does not use JLine. It does not render markdown. It does not show a spinner.

Every piped test keeps working, because `auto` mode selects this interface when no terminal
exists.

## The rich interface

`RichUi` uses JLine.

**Line editing and history.** JLine gives the arrow keys, `ctrl-a`, `ctrl-e`, `ctrl-w`, and
`ctrl-r`. The history file is `~/.konacode/chat_history`. konacode creates the directory when it
is absent. A prompt from an old session then returns under the up arrow.

The history file holds what the user typed. That text can be private. konacode writes the file
with owner-only permissions.

**Input on more than one line.** `Alt+Enter` adds a newline. `Enter` sends the message. konacode
does not continue a line automatically when a bracket is open, because that rule surprises a user
who writes prose.

**Tool calls.** `RichUi` prints the same text as `PlainUi`, in a colour. The format stays
`tool: read_file({"path":"pom.xml"})`. A second format would give us a second thing to maintain,
and the smoke tests search for this one.

**Markdown.** `RichUi` renders the answer. It asks JLine for the terminal width and gives that
width to the renderer.

**Failure.** When JLine cannot open a terminal, `RichUi` fails to build. In `auto` mode `Main`
then uses `PlainUi`. In `rich` mode `Main` prints the reason and exits 1.

**Construction.** `RichUi` takes its collaborators in the constructor. It does not build them.

```java
final class RichUi implements Ui {

    RichUi(LineReader reader, Terminal terminal, PrintStream out, Spinner spinner);

    static RichUi open() throws IOException;
}
```

`open` builds the real JLine objects and calls the constructor. A test calls the constructor with
a mocked `LineReader`, a mocked `Terminal`, a captured `PrintStream`, and a recording `Spinner`.
This is why `RichUi` can have tests.

**The interrupt key.** JLine throws `UserInterruptException` when the user presses `ctrl-c` at the
prompt. konacode catches it and returns an empty string. The `Repl` then skips the empty line and
prompts again. This matches a shell. konacode does not end the session, because `ctrl-d` already
does that and JLine reports it as `EndOfFileException`.

## The spinner

The spinner lives in its own class. It does not know about the agent, the interface, or markdown.

```java
class Spinner {

    Spinner(PrintStream out, String label);

    void start();

    void stop();
}
```

`start` begins a daemon thread. The thread draws one character, waits, and draws the next.
`stop` stops the thread and erases the line. Both methods are safe to call two times.

The class is not final, and `RichUi` takes one in its constructor. A test then gives `RichUi` a
subclass that records the calls. konacode needs no interface and no factory for this.

`RichUi` owns the spinner and controls it in three places. `thinking` starts it. `onToolCall`
stops it, prints the tool line, and `onToolResult` starts it again, because the loop returns to
the model. `showAnswer` and `showError` stop it before they print.

This coordination is the only hard part. Two threads must not write to the terminal at the same
time. `RichUi` therefore stops the spinner before every write of its own.

The thread is a daemon thread, so it cannot keep the process alive.

## The markdown renderer

The renderer lives in its own package, `dev.konacode.cli.markdown`. A reader who wants to
understand the agent can skip the package. The agent loop stays 24 lines.

```java
public final class Markdown {
    public static String render(String markdown, int width);
}
```

The class builds a commonmark `Parser` with the tables extension and the strikethrough extension.
It walks the tree with a visitor and produces text with ANSI codes.

The renderer handles every element:

| Element | Output |
|---|---|
| Heading | bold, and one colour for each of the six levels |
| Bold, italic, strikethrough | the matching ANSI code |
| Code span | one colour |
| Fenced code block | one colour, and an indent of two spaces |
| Unordered list | a bullet, and an indent for each level |
| Ordered list | a number, and an indent for each level |
| Block quote | a vertical bar and dim text |
| Link | the text, then the address in dim text |
| Table | a border drawn with box characters |
| Horizontal rule | a line of dashes across the width |

**Word wrap.** The renderer wraps prose at a space, to the given width. It never wraps inside a
fenced code block, because a broken line of code is wrong. It reduces the width for a list or a
block quote, by the size of the indent.

I estimate 350 to 450 lines. This is about 20 percent of the main source. That is the price of
the answer to question 4, and you accepted it.

## Testing

| Suite | Approach |
|---|---|
| `MarkdownTest` | Give markdown, assert the rendered text. Every element, and the wrap rule. This is the largest suite, and it needs no terminal. |
| `PlainUiTest` | Give a scripted reader and capture a `PrintStream`. Assert the prompts, the answer, and the tool line. |
| `ConversationTest` | `add`, `messages`, `restart`, and the copy rule. |
| `CommandsTest` | Each command, and the unknown command. Use a recording `Ui`. |
| `ReplTest` | The empty line rule, the end of input rule, and the command rule. Use a recording `Ui` and a fake `Agent`. |
| `SpinnerTest` | Start and stop. Assert the thread stops. Assert two calls to `close` are safe. |
| `UiSelectionTest` | `plain`, `rich`, `auto`, and a wrong value. |

| `RichUiTest` | Mockito gives a `LineReader` and a `Terminal`. The test captures a `PrintStream` and gives a recording `Spinner`. Cases: a typed line arrives; `EndOfFileException` ends the session; `UserInterruptException` gives an empty line and the session continues; the answer is rendered to the width the terminal reports; a tool call stops the spinner and prints the line; a tool result starts the spinner again; `close` closes the terminal. |

konacode prefers a hand-written double for its own types. `Spinner` is ours, so the test
subclasses it. `LineReader` and `Terminal` belong to JLine, so the test mocks them. `CLAUDE.md`
holds this rule.

Mockito loads an agent into the running JVM. Java 21 prints a warning about this. The build must
add `-XX:+EnableDynamicAgentLoading` to the surefire `argLine`, or the warning appears in every
test run and hides real output.

## Documents to correct

| Document | Correction |
|---|---|
| `CLAUDE.md` | The dependency rule. The `Conversation` rows. The `Ui` rows. The configuration table. |
| `CONTEXT.md` | Four seams becomes three. |
| `ARCHITECTURE.md` | The sentence *"Every one of those collaborators is an interface"* becomes false. |
| `FOLLOWUP.md` | The trimming entry says the interface exists for that purpose. `/compact` replaces that plan. |
| `README.md` | The new property, and the two interfaces. |

## Out of scope

The agent loop, the tools, the policy, the provider, and the codec do not change.
