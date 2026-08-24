# Observability — design

**Date:** 2026-08-24
**Status:** approved, pending implementation plan
**Issue:** [#23](https://github.com/bbossola/konacode/issues/23)

## Problem

konacode shows the tool name and the tool result. It shows nothing else.

You cannot see the message list that went to the provider, the reply that came back, the
iteration number, the time each step took, the token count, or the reason the turn ended. An
answer after eight iterations and a give-up after eight iterations look the same on the screen.
When a turn goes wrong, there is no record to read afterwards.

`ToolCallListener` is the only report the loop makes, and it carries two facts.

## Decision

Add one event stream. The loop and the provider both emit into it. Two sinks read it, and each
sink has its own level.

`ToolCallListener` is deleted. `Ui` reads the same stream.

## The package

A new package, `dev.konacode.trace`. It depends on nothing.

```
cli -> agent -> { llm, tools, policy } -> trace
```

Every event carries strings, numbers and booleans only. No event carries a `Message`, a
`ToolResult` or a `JsonNode`. That is what keeps the package free of dependencies, and it is what
lets `agent` and `llm` both emit into it without breaking the rule that `tools` must not depend on
`llm`.

| Element | Kind | Definition |
|---|---|---|
| `Trace` | interface | `void emit(TraceEvent event)`. The whole sink SPI. `NONE` discards. `fanOut(Trace...)` combines. |
| `TraceEvent` | sealed interface | One thing that happened. A record for each case. Sealed, so a new case is a compile error at every sink. |
| `Level` | enum | `OFF`, `BASIC`, `FULL`. It answers one question: `Optional<TraceEvent> keep(TraceEvent)`. |

## The events

The loop emits five events:

| Event | Components |
|---|---|
| `TurnStarted` | `turn`, `userText` |
| `IterationStarted` | `turn`, `iteration`, `maxIterations` |
| `ToolCalled` | `turn`, `name`, `argumentsJson` |
| `ToolFinished` | `turn`, `name`, `ok`, `output`, `millis` |
| `TurnEnded` | `turn`, `outcome`, `iterations`, `millis` |

The provider emits four events:

| Event | Components |
|---|---|
| `RequestSent` | `url`, `model`, `messageCount`, `toolCount`, `bodyJson` |
| `ReplyReceived` | `status`, `millis`, `bodyJson` |
| `TokensUsed` | `prompt`, `completion`, `total` |
| `RetryRequested` | `reason` |

`turn` is a counter. It starts at 1 and it increases for each call to `respond`. It groups the
lines of one turn in the file.

`outcome` is `ANSWERED`, `STOPPED`, `EXHAUSTED` or `FAILED`. This is the fact the screen hides
today.

`TokensUsed` needs one change in `ChatCompletionsCodec`. The codec drops the `usage` object of the
response. It must read it. This is the number you cannot get in any other way.

`RetryRequested` reports the second attempt that `ReplyValidator` forces. A retry is invisible
today.

## The sinks

**`JsonlTrace` writes the file.** One JSON object for each event, one line for each object, with a
timestamp. The file is `~/.konacode/traces/2026-08-24T14-03-11-482.jsonl`, beside the existing
`chat_history`. konacode makes a new file for each session, so there is no rotation code, and one
file holds one session that you can replay.

**konacode keeps the last 100 files.** When it makes the file for a new session, it first counts
the `*.jsonl` files in `~/.konacode/traces/` and deletes the oldest ones, until `maxFiles` files
remain. The name of a file is the time it started, so a sort by name is a sort by age.

The sweep runs once, at the start of the session, and never during a turn. It reads one
directory and it touches no other file. The name carries milliseconds, so two sessions that start
in the same second still get two files. If a delete fails, konacode prints one warning and
continues: a trace that cannot be swept is not a reason to stop.

`JsonlTrace` owns the sweep, because `JsonlTrace` owns the directory.

**`Ui` shows the screen.** `Ui` extends `Trace` and reads the events with a pattern-matching
switch. It renders `ToolCalled` and `ToolFinished` as it renders them today. It renders every
other event as one line in a different colour. `Ui` is the live sink because `Ui` already owns the
screen and the spinner. A second writer to `System.out` would fight the spinner.

`Main` gives `Agent` and `OpenAiClient` one `Trace.fanOut(ui, jsonl)` each.

## The level

The loop and the provider always emit every event, in full. Each sink applies its own level.

| Level | What the sink keeps |
|---|---|
| `OFF` | Nothing. |
| `BASIC` | Every event. It drops `bodyJson` from `RequestSent` and `ReplyReceived`, and it cuts every other payload at 2 KB. |
| `FULL` | Every event, verbatim. |

The `Ui` applies the level to the trace events only. `ToolCalled` and `ToolFinished` are the
normal display of konacode, so the `Ui` always shows them, at every level. `/trace off` therefore
gives you the screen you have today.

The level is in each sink and not in front of both, because the screen can be `full` while the
file is `basic`. One filter for both sinks cannot do that.

The rule lives in `Level` and not in the sinks. A sink asks the level one question and renders
what comes back. An empty answer means the sink writes nothing. A `BASIC` answer is a new event
with the payloads already cut, so a sink never cuts a string itself. The sinks stay short.

Events are built even when no sink keeps them. The cost is a few small records for each
iteration. The JSON bodies already exist as strings inside the client, so nothing extra is built
for them. The gain is that `/trace full` works on a session that you started with no property.

## Configuration

| Name | Kind | Values | Default |
|---|---|---|---|
| `konacode.trace` | property | `off`, `basic`, `full` | `off` |
| `konacode.trace.maxFiles` | property | a whole number, 1 or more | `100` |

`konacode.trace` sets the level of the file for the whole session. `konacode.trace.maxFiles` sets
how many trace files konacode keeps. A wrong value prints one line and exits 1, as
`konacode.maxIterations` does.

| Command | Effect |
|---|---|
| `/trace` | prints the level of the screen and the level of the file |
| `/trace off\|basic\|full` | sets the level of the screen |

The screen starts at `off`. An unknown word prints an error, as every other command does.

`/trace` changes the screen and never the file. The file is a record of the session, and a level
that moves in the middle would make it hard to read.

## Two rules

**A sink never throws into the loop.** If konacode cannot open the file, it prints one warning and
runs with `Trace.NONE`. Observability that can end a session is worse than no observability. This
is the rule from CLAUDE.md: never promote a failure to an exception.

**The trace never records the API key.** `RequestSent` carries the request body. The key is a
header. It cannot reach the file.

## Wiring

| Class | Change |
|---|---|
| `Trace`, `TraceEvent`, `Level`, `JsonlTrace` | New, in `dev.konacode.trace`. `JsonlTrace` sweeps the directory when it opens the file, and reports the configured count with a static `configuredMaxFiles()`, as `Agent.configuredMaxIterations()` does. |
| `ToolCallListener` | Deleted. |
| `Agent` | Takes a `Trace` in place of the `ToolCallListener`. Emits the five loop events. |
| `OpenAiClient` | Takes a `Trace`. Emits `RequestSent`, `ReplyReceived` and `RetryRequested`. |
| `ChatCompletionsCodec` | Reads the `usage` object and reports it, for `TokensUsed`. |
| `Ui` | Extends `Trace`. Gains `liveTrace(Level)`. |
| `PlainUi`, `RichUi` | Implement `emit` with a switch. `PlainUi` prints a prefix, `RichUi` prints a colour. |
| `Commands` | Gains `/trace`. |
| `Main` | Reads `konacode.trace` and `konacode.trace.maxFiles`, opens the file, builds the fan-out. |
| `RecordingToolCallListener` | Becomes `RecordingTrace`. |

Two commits. The package and the loop first. The provider, the `/trace` command and the wiring
second.

## Testing

Test first, and offline. `RecordingTrace` is a hand-written double, as `FakeLlmClient` is.

| Test | What it proves |
|---|---|
| One tool turn | The events arrive in order, with one `turn` number. |
| Four endings | `TurnEnded` reports `ANSWERED`, `STOPPED`, `EXHAUSTED` and `FAILED`. |
| `Level.BASIC` | It drops the two `bodyJson` components and cuts a long payload at 2 KB. |
| `Level.OFF` on the screen | The `Ui` shows the tool call and shows no other event. |
| `JsonlTrace` | One event makes one line, and the line matches a fixture. |
| A file that cannot be opened | konacode warns once and the turn still completes. |
| A directory with more files than the count | The oldest files go, the newest stay, and the count is exact. |
| A delete that fails | konacode warns once and the session still starts. |
| `konacode.trace.maxFiles=zero` | One line, and exit 1. |
| `/trace basic` | The level of the screen changes. `/trace wrong` prints an error. |
| `usage` in a reply | The codec reports the three counts. |

konacode records a duration and never asserts one. There is no clock to inject.

## Rejected alternatives

**Two narrow listeners.** Widen `ToolCallListener` into an `AgentListener`, and give `llm` a
separate `WireListener`. It saves one package. It costs two vocabularies for one log, two
interfaces for each sink, and empty methods on `Ui` for events that `Ui` ignores.

**A string logger.** `Trace.log(String category, String message)`, in twenty lines. The file is
then prose and not data. You cannot filter it, count it, or replay a request from it, and replay
is half the value of the `full` level.

**A sink inside the provider only.** The wire is the ground truth, and it is cheap. The provider
never sees the iteration number, the tool timings or the outcome, so the questions that start this
design stay unanswered.

**One file for everything, rotated by size.** A single `trace.jsonl` that konacode cuts at a size
needs the size check on every write, and it can cut one session in two. One file for each
session, swept by count at the start, keeps the session as the unit you read and keeps the check
off the hot path.
