# Architecture

How a turn actually runs, in domain terms. No transport, no serialization, no provider —
those are implementation details of one collaborator.

For what each element *is*, see [CLAUDE.md](CLAUDE.md). For why it is shaped this way, see
[CONTEXT.md](CONTEXT.md).

## Who talks to whom

```
                    ┌──────────────────┐
                    │   Conversation   │   an ordered sequence of Message
                    │  append / read   │   ── the agent's only state
                    └────────┬─────────┘
                             │
                             │ appends every turn, reads the whole thing
                             │
   ToolRegistry              │                          LlmClient
   ────────────              ▼                          ─────────
   name → Tool   ──specs──►  Agent  ──(Messages, ToolSpecs)──►
   enumerate                 loop   ◄──── AssistantMessage ────
                              │
          ┌───────────────────┼────────────────────┐
          ▼                   ▼                    ▼
      ToolPolicy            Tool             ToolCallListener
      ──────────            ────             ────────────────
      → Decision            → ToolResult     observes; changes nothing
        Allow | Deny          Ok | Err
```

Three of those collaborators are interfaces with a default implementation: the tools, the LLM
provider, and the tool policy. `Conversation` is a class. A caller changes the history through
`messages()` and `restart(List)`, so a second implementation buys nothing.

`Agent` names no implementation. The CLI decides what they are, and it is the only place that
does.

## One turn

```
   UserMessage
        │
        ▼
   append to Conversation
        │
        ▼
 ┌─► ask LlmClient  ⟨every Message so far, every ToolSpec⟩
 │      │
 │      ▼
 │  AssistantMessage
 │      │
 │      ▼
 │  append to Conversation      ← always, and before anything is run
 │      │
 │      ├── carries NO ToolCall ────────────►  its text is the answer.  DONE
 │      │
 │      └── carries ToolCalls
 │             │   for each ToolCall ⟨id, name, arguments⟩
 │             ▼
 │        ToolRegistry: is there a Tool by that name?
 │             │  no ──────────────────────────► Err
 │             │  yes
 │             ▼
 │        are the arguments well formed?
 │             │  no ──────────────────────────► Err
 │             │  yes
 │             ▼
 │        ToolPolicy → Decision
 │             │  Deny ────────────────────────► Err
 │             │  Allow
 │             ▼
 │        Tool.execute  ─────────────────────►  Ok | Err
 │             │
 │             ▼
 │        ToolMessage ⟨correlated to that ToolCall's id⟩
 │             │
 │             ▼
 │        append to Conversation
 │             │
 └─────────────┘  unless the iteration budget is spent
```

Nothing in that picture teaches the model to list a directory before reading a file, or to
re-read a file after a failed edit. That behaviour emerges from the loop and the tool
descriptions alone.

## Invariants

**The Conversation is the only state.** The loop keeps no memory of its own. Everything the
model will see next turn is something appended to the Conversation this turn.

**Every ToolCall is answered by exactly one ToolMessage**, correlated by id. Unknown tool,
malformed arguments, a policy denial, a tool that throws — all four still produce a
ToolMessage. Nothing is left dangling, because a provider rejects a conversation where a call
has no result.

**The assistant's message is appended before any tool runs.** Providers reject a tool result
whose originating assistant message is absent from the history. Reversing these two lines
produces an error that looks like a serialization bug and is miserable to diagnose.

**Failure is data, not control flow.** An `Err` is an ordinary value that flows back into the
Conversation and is read by the model, which is how it recovers. Promoting one to an exception
would take a situation the model can fix and hand it to the human instead.

**Four ways a turn ends**, and all four return text: an AssistantMessage with no ToolCalls, an
exhausted iteration budget, a transport failure, or the user pressing ESC. None of them throws.

A stopped turn stays in the history, so the model can read what it did and reverse it when the
user asks. Every ToolCall that never ran is answered with an Err saying the user stopped the turn
before it ran, so the dangling call invariant above holds with no special case.

## Stopping a turn

The user presses ESC. `EscapeWatcher` reads the byte from the terminal and calls
`Cancellation.request()`. That does two things at once: it sets a flag the loop reads, and it
interrupts the thread the loop armed.

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
```

The loop arms the interrupt around the provider call and around a tool that answers
`stopsOnInterrupt()` with true. Around nothing else. A tool that has not said an interrupt is safe
is never interrupted, because arming every tool would rest the safety of the loop on every tool
author writing correct cleanup, for ever.

A tool that works in many steps stops itself instead. It reads a `StopCheck` between the steps and
returns an `Err` that says what it changed. `EditFile` reads it on one side of `writeAtomic` and
never inside, which is what makes its guarantee hold: the edit is fully applied and the model gets
an `Ok`, or the file is untouched and the model gets an `Err` that says so.

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
```

The loop asks `stopped()` **before** each tool call, never after. A tool that has already started
therefore runs to the end and its real result is appended the normal way. The check that ends the
turn happens where the next tool would have started.

A thread interrupt stops none of the tools that ship today. This was measured on JDK 21.0.2:
`Files.list`, `Files.newInputStream`, `Files.readAllBytes`, `Files.writeString`, `Files.move` and
`Files.deleteIfExists` all run to the end with the interrupt status already set. So all four tools
answer `stopsOnInterrupt()` with false, and asking is the only mechanism that works for them.

## A reply that lies about being finished

The invariants above contain one that does more work than it looks:

> **An AssistantMessage that carries no ToolCalls means the model is done.**

The loop has no other test for completion. A model can write a tool call as prose. The reply then
carries no ToolCalls. That reply is correct in form and it means "done". The model meant the
opposite. Some local models do this. qwen3-coder does it in about one turn in four.

konacode treats this as a provider defect. It is not a domain idea. `LlmClient` promises text or
tool calls, and it promises them faithfully. A model that writes the call as prose gives a broken
response from that provider. Therefore the provider repairs it. The OpenAI provider finds such a
reply and sends the same request again, one time. The loop never learns that this happens.

The detector misses cases on purpose. A wrong refusal costs more than a missed one. konacode reads
its own repository, which contains many tool call formats. A model that quotes one of them is
correct, and konacode must not refuse that reply. See
[the design](docs/superpowers/specs/2026-08-21-reply-validation-design.md).
