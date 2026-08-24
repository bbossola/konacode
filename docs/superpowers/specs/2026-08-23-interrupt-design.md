# Interrupting the agent — design

**Date:** 2026-08-23
**Status:** approved, pending implementation plan

## Problem

A turn can run for a long time. The model can write a long reply, and the loop can call up to
`konacode.maxIterations` tools before it stops. The user has no way to say "stop, that is not
what I wanted".

Today the user has one key, and it is too blunt. `Agent.respond` blocks the one thread that the
REPL owns, so ctrl-C during a turn ends the process. The user loses the session, the history and
the terminal state.

## Decision

**ESC stops the turn. Ctrl-C still ends konacode.**

The user presses ESC while the agent works. konacode stops the turn, prints `Stopped.`, and shows
the prompt again. The conversation keeps a full record of the turn, so the model knows what it
did.

Four choices give the design its shape:

| Choice | Decision |
|---|---|
| The key | ESC. Ctrl-C keeps today's behaviour, so a soft stop and a hard stop stay different. |
| The speed | The in-flight HTTP request aborts at once. |
| The tools | A tool stops itself between steps. A tool that waits inside one call declares that an interrupt is safe. |
| The interface | The rich interface only. A pipe has no user to press a key. |
| The history | konacode keeps the whole turn, and marks the tool calls that never ran. |

## Why the history is kept

An earlier draft removed the turn from the history, so the conversation looked as it did before
the user typed the line. That is simple, and it is wrong for the case that matters.

A stop usually means "you are doing the wrong thing". The next thing the user says is often "undo
that". The model can only undo an edit that it can see. `EditFile` records the exact `old_str` and
`new_str` in the tool call, so a model that reads its own history can write the reverse edit. A
model whose history was erased cannot.

konacode therefore keeps the record and adds no undo machinery. There is one limit, and it is
accepted: `EditFile` creates a file when `old_str` is empty, and no tool deletes a file. The model
cannot undo a file that it created. A `DeleteFile` tool and a `/revert` command with a write
journal were both considered and both left out. See "Out of scope".

## `Cancellation` — a new class in `dev.konacode.agent`

The user's request to stop one turn. One object, shared by the interface, the loop and the tools.
It implements `StopCheck`, which is how a tool sees it. See "Stopping a tool".

| Member | Visibility | Definition |
|---|---|---|
| `request()` | public | The user asked to stop. Sets the flag, and interrupts the armed thread. |
| `stopped()` | public | Asks whether to stop. The loop reads it, and so does a tool. From `StopCheck`. |
| `clear()` | public | A new turn starts. Resets the flag. |
| `arm()` | package private | Records the calling thread, so that a later `request()` interrupts it. |
| `disarm()` | package private | Forgets that thread, and clears the interrupt status of the caller. |

`request` is public and `arm` is not. The user interface may ask for a stop. Only the loop may
decide where an interrupt is safe.

`arm`, `disarm` and `request` share one lock. Without the lock, an interrupt sent as the loop
disarms arrives after the clear, and the status stays set. A file operation ignores a set status,
but the next `client.chat` does not: a blocking HTTP send checks the status and throws at once, so
the following turn would fail for no reason. The lock makes `disarm` wait for the interrupt, and
then clear it.

## The loop

`Agent` takes a `Cancellation`. `respond` changes in four places.

1. It calls `cancellation.clear()` before it appends the user message.
2. It arms the cancellation around `client.chat`, and disarms in a `finally`. The interrupt
   therefore aborts the HTTP request through the `InterruptedException` path that `OpenAiClient`
   already has.
3. The `catch (LlmException)` asks `cancellation.stopped()` first. An aborted request is a stop,
   not a transport failure.
4. It asks `stopped()` before each tool call, and after each iteration.

It also arms around a tool that asks for it, and only that tool:

```java
private ToolResult execute(Tool tool, JsonNode args) {
    if (!tool.stopsOnInterrupt()) {
        return tool.execute(args);
    }
    cancellation.arm();
    try {
        return tool.execute(args);
    } finally {
        cancellation.disarm();
    }
}
```

A stop does three things, and then returns the text `Stopped.`:

1. **Answers every dangling tool call.** For each `ToolCall` in the last assistant message that has
   no `ToolMessage`, it appends `ToolResult.err("Stopped by the user before this tool ran.")`. This
   is true, and it uses the error channel that the model already reads.
2. **Closes the turn.** It appends `AssistantMessage("Stopped by the user.", List.of())`, exactly as
   `fail` already does for a transport failure.
3. **Returns.** The REPL prints the text with `showAnswer`.

`LlmClient`, `ToolPolicy`, `ToolResult`, `Conversation` and `Ui` do not change. `Tool` gains one
method, and the next section says why.

## Sequence: ESC during the provider call

The turn is already running. The user typed a line, and the interface started the spinner and the
watcher.

```
User         Watcher    Cancellation      Agent     Conversation    LlmClient
  │             │             │             │             │             │
  │             │             │◄───clear()──│             │             │
  │             │             │             │─add(user)──►│             │
  │             │             │◄────arm()───│             │             │
  │             │             │             │───chat(history, tools)───►│
  │             │             │             │             │             ┃
  │─────ESC────►│             │             │             │             ┃  blocked
  │             │─request()──►│             │             │             ┃  in HTTP
  │             │             │─interrupt()►│             │             ┃
  │             │             │             │◄╌╌ LlmException ╌╌╌╌╌╌╌╌╌╌┃
  │             │             │◄──disarm()──│             │             │
  │             │             │◄─stopped()──│             │             │
  │             │             │╌╌╌╌true╌╌╌╌►│             │             │
  │             │             │             │  add(AssistantMessage)    │
  │             │             │             │────────────►│             │
  │◄╌╌╌╌╌╌╌╌╌╌ "Stopped." ╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌│             │             │
  │             │             │             │             │             │
```

`request` does two things at once. It sets the flag, which is what the loop reads. It interrupts
the armed thread, which is what makes the HTTP call return now instead of in thirty seconds.

`disarm` runs before anything else, so no interrupt leaks into the code that follows.

No tool ran, so no tool call is dangling. The history holds the user message and the closing
assistant message.

## Sequence: ESC during a tool call

The reply carries two tool calls. Tool 1 is `ReadFile`, and it reads in chunks. The user presses
ESC while it runs.

```
User         Watcher    Cancellation      Agent     Conversation      Tool
  │             │             │             │             │             │
  │             │             │◄─stopped()──│             │             │
  │             │             │╌╌╌╌false╌╌╌►│             │             │
  │             │             │             │─────execute(args)────────►│
  │             │             │             │             │             ┃
  │─────ESC────►│             │             │             │             ┃  tool 1
  │             │─request()──►│             │             │             ┃  runs on
  │             │             │             │             │             ┃
  │             │             │◄──── stopped() ─────────────────────────┃
  │             │             │╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌ true ╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌►┃
  │             │             │             │             │             ┃
  │             │             │             │◄╌╌╌ ToolResult.Err ╌╌╌╌╌╌╌┃
  │             │             │             │  add(ToolMessage)         │
  │             │             │             │────────────►│             │
  │             │             │◄─stopped()──│             │             │
  │             │             │╌╌╌╌true╌╌╌╌►│             │             │
  │             │             │             │             │             │
  │             │             │        tool 2 never starts              │
  │             │             │             │  add(ToolMessage) that    │
  │             │             │             │  says it was stopped      │
  │             │             │             │────────────►│             │
  │             │             │             │  add(AssistantMessage)    │
  │             │             │             │────────────►│             │
  │◄╌╌╌╌╌╌╌╌╌╌ "Stopped." ╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌│             │             │
  │             │             │             │             │             │
```

Three things hold here.

**The tool stops itself.** Nothing interrupts it. It reads `stopped()` between two chunks, and it
returns an `Err` that says how far it got and what it changed. The tool holds the same
`Cancellation` object as the loop, seen through `StopCheck`.

**The result is still appended.** The loop asks `stopped()` before each tool call, not after. The
first call therefore returns the normal way, and the check that stops the turn happens where the
second call would have started.

**The dangling call invariant holds.** ARCHITECTURE.md says that every `ToolCall` is answered by
exactly one `ToolMessage`. Tool 2 never ran, so the stop writes its result: an `Err` that says the
user stopped the turn before this tool ran. A provider then accepts the history, and the model
reads an accurate account of what happened.

A third case needs no diagram. ESC between two iterations sets the flag only, because nothing is
running. The loop reads the flag at the top of the next iteration and stops.

## Stopping a tool

### What a thread interrupt does to a filesystem call

Measured on JDK 21.0.2, Linux. Every call ran with the interrupt status already set:

| Call | Used by | Outcome |
|---|---|---|
| `Files.newInputStream` + `readNBytes` | `ReadFile` | completed, status still set |
| `Files.readAllBytes` | `EditFile`, read phase | completed, status still set |
| `Files.list` | `ListFiles` | completed, status still set |
| `Files.writeString` | `EditFile`, write phase | completed, status still set |
| `Files.move` with ATOMIC\_MOVE | `EditFile`, write phase | completed, status still set |
| `Files.deleteIfExists` | `writeAtomic` cleanup | completed, status still set |

The whole of `writeAtomic` also ran to the end with the status set. The destination held the new
content, and no temporary file was left.

**A thread interrupt cannot stop the tools that exist today.** `Files.newInputStream` is
uninterruptible on a modern JDK, and a move is one system call. So a tool must stop itself.

### Two mechanisms

They answer two different questions, and a tool may use both.

**`StopCheck` — am I between steps?** A new interface in `dev.konacode.tools`:

```java
@FunctionalInterface
public interface StopCheck {

    boolean stopped();

    StopCheck NEVER = () -> false;
}
```

`Cancellation` implements it, so the wiring reads `new ReadFile(workspace, cancellation)`. A tool
that does not stop takes no `StopCheck`, and its constructor says so. `NEVER` serves every test
that does not test stopping.

The interface lives in `tools` and not in `agent`, because `agent` already depends on `tools`. The
import would close a cycle. konacode answered the same question once before: `tools` must not
import `dev.konacode.llm`, so `ToolSpecs` adapts. A narrow interface is the same decision, and it
needs no adapter class.

Moving `Cancellation` down into `tools` instead was rejected. Package private then means visible to
every tool, so `arm` and `disarm` would have to become public, and any caller could aim an
interrupt at a thread inside `writeAtomic`.

**`stopsOnInterrupt()` — am I stuck inside one call?** A tool that waits in `HttpClient.send`, a
socket read or `Process.waitFor` reads nothing between steps, because there are no steps. `Tool`
gains one method:

```java
public interface Tool {

    String name();

    String description();

    ObjectNode inputSchema();

    ToolResult execute(JsonNode args);

    boolean stopsOnInterrupt();
}
```

The method is abstract and not a default. A new tool author must answer it, in the same way that
the sealed `Decision` makes a new case a compile error at every handling site. Six sites answer it
today: `ListFiles`, `ReadFile`, `EditFile`, and the three stub tools in the tests. All six return
`false`.

A tool that returns `true` carries two obligations:

1. It catches the interrupt, and it returns a `ToolResult` that says what it changed before it
   stopped.
2. It does its cleanup with calls that no interrupt can break. The status is still set while the
   cleanup runs, so a `waitFor` inside a `finally` throws at once and the cleanup is skipped.
   `disarm` cleans up after the tool, not during it.

No tool returns `true` yet. A web search tool will be the first.

### The three tools

| Tool | Where it reads `stopped()` | What it reports |
|---|---|---|
| `ListFiles` | Between entries, while it builds the sorted list. | `Err`: "Stopped by the user after 1200 entries. Nothing was changed." An `Err` and not an `Ok`, so the model cannot read a part of a listing as the whole of it. |
| `ReadFile` | Between chunks. `readNBytes(100_000)` becomes a loop over a buffer. | `Err`: "Stopped by the user after 40960 of 100000 bytes. The file was not changed." |
| `EditFile` | In the read phase, and once more immediately before `writeAtomic`. **Never inside it.** | `Err`: "Stopped by the user before the write. The file was not changed." |

`EditFile` then carries a guarantee that fits in one line: **the edit is fully applied and the
model gets an `Ok`, or the file is untouched and the model gets an `Err` that says so.** There is
no third outcome, because the check sits on one side of `writeAtomic` and never inside it.

### What cannot be stopped

A tool that blocks inside one **uninterruptible** call cannot be stopped by either mechanism.
`ReadFile` on a named pipe waits in a single `read`, and no check runs while it waits. konacode has
no escape from that today, and none after this change. A check helps while work proceeds, not when
it is stuck.

## `EscapeWatcher` — a new class in `dev.konacode.cli`

ESC is not a signal. It is the byte `0x1B` on standard input. Something must read the terminal
while the agent works.

`EscapeWatcher` is a sibling of `Spinner`: one daemon thread, `start()` and `stop()`, both
idempotent, and not `final`, so a test can subclass it and record the calls.

```
start()   attributes := terminal.enterRawMode()
          turn ISIG back on, so ctrl-C still ends konacode
          start the daemon thread "konacode-escape"

thread    reader = terminal.reader()            a NonBlockingReader
          loop: c = reader.read(POLL_MS)
                c == 27  -> cancellation.request(), and stop reading
                expired  -> loop again
                EOF      -> stop reading

stop()    clear the running flag, join briefly, restore the attributes
```

Two consequences are accepted.

**Raw mode turns echo off, and the watcher eats the input.** Today, text typed while the agent
works waits in the buffer and appears at the next prompt. After this change the watcher consumes
it and drops it. This is an improvement, because typed-ahead text at a prompt is a common way to
send a line by accident.

**Any `0x1B` stops the turn**, including the first byte of an arrow key sequence. During a turn
there is no line to edit, so this is correct. It also makes one ESC press work with no timeout.

## Wiring

| Class | Change |
|---|---|
| `Main` | Builds one `Cancellation`. Passes it to `selectUi`, to the three tools, and to the `Agent`. |
| `Main.selectUi` | Takes the `Cancellation`. `RichUi.open` uses it. `PlainUi.open` ignores it. |
| `RichUi` | Takes an `EscapeWatcher`. `thinking()` starts it. `showAnswer`, `showError` and `close()` stop it. `onToolCall` stops the spinner **only**, because ESC must work while a tool runs. |
| `Commands` | `/help` gains one line for ESC. |
| `Agent` | Takes a `Cancellation`. The loop changes as described above. |
| `Tool` | Gains `boolean stopsOnInterrupt()`. |
| `ListFiles`, `ReadFile`, `EditFile` | Take a `StopCheck`. Each returns `false` from `stopsOnInterrupt()`. |
| `StubTool`, `EchoTool`, `ExplodingTool` | The test stubs answer `stopsOnInterrupt()` with `false`. |
| `Ui`, `PlainUi` | No change. |

The welcome hint becomes `esc stops · ctrl-d quits · alt-enter adds a line · /help`. The present
wording plus ESC is wider than the 41 column banner.

## Rejected alternatives

**A cancel token in the provider SPI.** `LlmClient.chat(history, tools, cancellation)`. The most
explicit design, and the most expensive: it widens the one-method interface that every future
provider must implement, and each provider then writes its own abort. The armed thread interrupt
reaches the same result through a path the JDK already provides.

**A thread interrupt with no new type.** The watcher interrupts the turn thread, and the loop sees
`LlmException("Request was interrupted.")`. No new class, but the loop cannot tell a stop from a
transport failure, and a running tool is interrupted too.

**Ctrl-C as the stop key.** It needs a JLine signal handler rather than a reader, and it takes the
hard stop away from the user. Two keys with two clearly different results is better than one key
with a mode.

**Arming the interrupt around every tool.** Cheaper to write than a declaration, and wrong. The
safety of the loop would then rest on every tool author writing correct cleanup, for ever.
`EditFile` survives an interrupt only because `writeAtomic` was written with care, and a future
tool that streams to a socket would not.

**Erasing the turn from the history.** Simpler in the loop, and it removes the dangling call
problem instead of solving it. It also removes the model's only record of what it did, which
defeats the revert case above.

## Testing

Test first. Every test is offline. `Cancellation` and `EscapeWatcher` are ours, so the doubles are
hand-written. `Terminal` and `NonBlockingReader` belong to JLine, so they are Mockito mocks, as
`RichUiTest` already does.

| Test | What it proves |
|---|---|
| `CancellationTest` | `request` sets the flag. `clear` resets it. A request while armed interrupts that thread. After `disarm` the interrupt status is clear, and a request that races the disarm leaks no interrupt. |
| `AgentTest` | ESC during the provider call returns `Stopped.`, and the history ends with the user message and `AssistantMessage("Stopped by the user.")`. ESC during tool 1 of 2: tool 1 returns, tool 2 never executes, and both calls carry a `ToolMessage`. After `respond` returns, `Thread.interrupted()` is false. A hand-written tool that answers `stopsOnInterrupt()` with true and waits on a latch: `request()` makes it return, and the loop appends its `Err`. A tool that answers false is never armed. |
| `EscapeWatcherTest` | The byte 27 calls `request`. A read timeout keeps the loop polling. `stop` restores the terminal attributes. |
| `RichUiTest` | `thinking` starts the spinner and the watcher. `showAnswer` stops both. `onToolCall` stops the spinner and leaves the watcher running. |
| `ListFilesTest` | A `StopCheck` that answers true stops the listing, and the result is an `Err` that names the count. |
| `ReadFileTest` | A `StopCheck` that answers true stops the read after a chunk, and the `Err` names the bytes read. |
| `EditFileTest` | Stopped in the read phase: the file on disk is unchanged, and the `Err` says so. Not stopped: the edit is applied. |

## Risk

JLine 4.3.1 must deliver a keystroke through `terminal.reader().read(timeout)` while `readLine` is
not running, and `enterRawMode` must let ISIG be turned back on. This is not yet proved.

The first step of the implementation is a throwaway spike against a real terminal that proves
both. If either fails, the fallback is a worker thread for the turn, with `readLine` left running
to catch the key. That is a larger change, and it needs a new decision before anyone writes it.

## Documentation to update

- `CLAUDE.md`: rows for `Cancellation`, `StopCheck` and `EscapeWatcher`. The `Agent` row, the
  `Tool` row, and the three tool rows.
- `ARCHITECTURE.md`: the fourth way a turn ends, and the two sequence diagrams.
- `README.md`: the key.

## Out of scope

**A `DeleteFile` tool.** It would let the model undo a file that it created. Whether an agent may
delete a file is a policy question, and it belongs with the work on `ToolPolicy`.

**A `/revert` command with a write journal.** konacode would record every write and undo the
writes itself, without the model. This is exact and fast, and it is a separate subsystem: a
journal, a copy of each file before the write, and a command. It is a design of its own.

**An interrupt for the plain interface.** A pipe has no user to press a key.
