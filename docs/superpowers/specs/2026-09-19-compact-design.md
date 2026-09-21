# `/compact` — design

The last item of FOLLOWUP.md §3 that has no open design question.

## Problem

konacode sends the whole conversation on each request. A planned turn runs up to 24 iterations,
so one turn can add 48 messages. A long session then pays for every old tool result on every
call, and it reaches the context limit of the model.

`/clear` is the only remedy today. It removes everything, so the model forgets the work in
progress too.

## Decision

konacode adds one command. `/compact` asks the model for a summary of the conversation, and it
replaces the conversation with the system message and that summary. The user asks for it, and no
policy decides. konacode compacts nothing on its own.

The summary names every file the model changed, and what it changed. The exact text of the last
edit is gone, but the list names the places, so "undo that" after a compact still has something to
read.

## The class

`Compaction` is a public final class in `dev.konacode.agent`. It lives there because `arm` and
`disarm` on `Cancellation` are package-private, and only that package decides where an interrupt
is safe.

It takes four collaborators in the constructor: the `LlmClient`, the system `Message`, the
`Conversation` and the `Cancellation`. It holds no other state.

It has one method, `Optional<String> compact()`:

1. It reads `conversation.messages()`. When the list holds one message, it returns empty and
   sends nothing.
2. It sends the list plus one `UserMessage` with the prompt below, and an empty tool list. The
   interrupt is armed around the call and no longer, the way `Agent.chat` does it.
3. A reply with tool calls, or with blank text, is a failure: it throws an `LlmException` that
   says the model gave no summary.
4. It calls `conversation.restart` with three messages: the system message, a `UserMessage` that
   says "This is the summary of the conversation so far. Continue from it." followed by a blank
   line and the summary, and an `AssistantMessage` that says "Understood." with no tool call.
5. It returns the summary.

The pair in step 4 is the shape `/skill` uses. The history keeps the alternation of user and
assistant, and a reader of the trace file recognises the pattern.

The request carries a history with tool calls and no `tools` field. The provider is expected to
accept this. Verify it on the first real run.

## The prompt

The prompt is prompt text, and a test pins it.

```
Summarize this conversation for yourself, so that you can continue the work from the summary alone.
Answer with text only. Do not call a tool.
Include what the user asked for, what is done, and what is not done.
Include every file you changed, and what you changed in it.
Include the facts a next step needs: paths, names, and decisions.
Leave out the contents of files and the output of commands.
```

## The command

`Commands` gains `/compact` and a `Compaction` field. It takes no argument. `/help` lists it.

The command runs in this order:

1. It reads the message count.
2. It calls `ui.thinking()`. In the rich interface this starts the spinner and the
   `EscapeWatcher`, so `esc` works while the model writes.
3. It calls `compact()`.
4. It shows the summary as markdown, then one line: "The conversation held 41 messages. It now
   holds 3."

`showAnswer` and `showError` both stop the spinner and the watcher, so the command adds no
spinner code.

When `compact()` returns empty, the command shows "Nothing to compact. The conversation is
empty." The count is the only fact `Commands` reads from the conversation; whether there is
something to compact is a fact `Compaction` owns.

## Where the interrupt is cleared

`Repl` clears the `Cancellation` before it runs a command, in the line before it runs a turn
today. The reason is the same: a key pressed at the prompt must not stop the next thing. This
covers every command, and only `/compact` makes a call that an interrupt can stop.

## Wiring

`Main.build` builds one `Compaction` on the loop's client, the system message, the conversation
and the cancellation, and gives it to `Commands`. The token counts of a compaction then appear
under `kona`, and the `RequestSent` line shows the message count, so a user reads the cost of a
compaction in the trace.

## Error channels

Three outcomes, and the conversation changes in the first only.

| Outcome | What happens |
|---|---|
| The model answers | The conversation is restarted. The user sees the summary and the count. |
| `LlmException` | `Compaction` lets it through. `Commands` shows the message in red. The conversation stays as it was. |
| No text, or a tool call | `Compaction` throws an `LlmException`. Same path as the row above. |

`esc` takes the second row. `OpenAiClient` translates the interrupt into an `LlmException`, so a
stopped compaction leaves the conversation unchanged.

No outcome reaches the model. The summary is not a tool result, and the failure is not one of the
four channels in CLAUDE.md: it is a command that failed at the prompt, the way `/skill` fails on a
missing folder.

## Rejected alternatives

**The loop's `Agent` makes the call.** `Commands` calls `agent.respond(prompt)`. The spinner, the
trace events and the interrupt come free. Rejected because the model has every tool in that turn,
so it can read a file while it summarizes, and because a failure comes back as the string
`<error> …`, so `Commands` must read a string to know that the turn failed.

**A second `Agent` with no tool and one iteration**, the way `AgentJudge` does it. Same shape as
the judge. Rejected for the string failure alone: the judge accepts it because a failure there is
one of three words, and a summary has no such alphabet.

**Keep the last turn word for word beside the summary.** The model keeps the exact tool calls of
the last turn. Rejected because the result can be 48 messages larger after a planned turn, which
is the case that needs the compact most.

**Append the summary to the system message.** Smallest history. Rejected because the system
message is first in history and never removed, and `/clear` would then need the original.

## Out of scope

- A compact that konacode starts on its own, at a token count or a message count. The user asks,
  and no policy decides.
- A token count in the message the user reads. The trace has it.
- A second client named `compact`. The loop's client serves, and the trace shows the request.

## Tests

`CompactionTest`, with `FakeLlmClient` and `RecordingTrace`:

- The request holds the whole history, then the prompt as the last user message, and no tool.
- After a reply, the conversation holds the system message, the framed summary and the
  acknowledgement, in that order.
- After an `LlmException`, the conversation is unchanged and the exception reaches the caller.
- A conversation with the system message only gets no request and an empty result.
- A reply with blank text, or with a tool call, throws and leaves the conversation unchanged.
- The interrupt is armed during the call and disarmed after it, the way `AgentTest` checks it
  for `Agent.chat`.

`CommandsTest`:

- `/compact` shows the summary and the count.
- `/compact` on a failure shows the error, and the conversation is unchanged.
- `/compact` on an empty conversation shows the sentence and makes no request.
- `/compact x` is an unknown command.
- `/help` lists `/compact`.

`ReplTest`: the cancellation is cleared before a command runs.

`MainTest`: `build` gives `Commands` a `Compaction` on the loop's client. `CompactionTest` pins
the prompt, because `Compaction` writes it.

## Documents

`CLAUDE.md` gains a `Compaction` row in the `agent` table and the word `/compact` in the
`Commands` row. `FOLLOWUP.md` §3 marks `/compact` built. `CHANGELOG.md` gains an entry under an
unreleased heading. `README.md` gains the command where it lists the others.
