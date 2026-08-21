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

Every one of those collaborators is an interface with a default implementation. `Agent` names
none of them concretely — the CLI decides what they are, and it is the only place that does.

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

**Three ways a turn ends**, and all three return text: an AssistantMessage with no ToolCalls,
an exhausted iteration budget, or a transport failure. None of them throws.

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
