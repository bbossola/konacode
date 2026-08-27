# Follow-up

Deferred work that carries a design consequence. Everything here was considered during the
initial design and consciously left out; this is the record of what was left and what it will
cost.

## 1. Opaque provider passthrough on `AssistantMessage`

**Status:** deferred, but it is the item most likely to hurt if it stays deferred.

`AssistantMessage` is currently `(String text, List<ToolCall> toolCalls)`. Every provider field
that is neither of those is dropped on the floor when a response is decoded, and cannot be sent
back on the following request.

The proposal is one extra component — a `JsonNode raw` (or `Map<String, Object> providerExtras`)
that `ChatCompletionsCodec` populates from the response and writes back verbatim on the next
request, and that **nothing else in the codebase ever inspects**. It is not part of the domain
model; it is a courier.

**Cost now:** about ten lines, and it looks like dead weight.

**Cost later:** it touches the sealed `Message` hierarchy, the codec, and `Conversation` — the
three things every other component depends on. That is a wide change made under pressure, at
the moment you are also trying to get something else working.

**What it unblocks:** reasoning-state preservation across tool calls (below), Anthropic
thinking blocks — which must be passed back unchanged when continuing on the same model — and
provider fields not yet encountered. Each becomes a codec-local change instead of a
hierarchy-wide one.

**Recommendation:** add it when a *second* provider lands, or when reasoning support does —
whichever comes first — and not before. The OpenAI Chat Completions provider is the baseline and
needs nothing from this field, so adding it there would mean guessing the shape against a payload
that never exercises it.

## 2. Reasoning

"Reasoning" names two different capabilities that solve different problems. Both are worth
having; they are not substitutes.

### Model-side reasoning

The model thinks before it answers. This improves *single-step* decisions: which tool to call,
with what arguments, whether this is really the right file.

**Basic support is nearly free.** A `reasoningEffort` field on `OpenAiConfig` and one line in
`ChatCompletionsCodec`. Perhaps five lines, no structural impact.

**Doing it properly is where the trap is.** When a reasoning model makes a tool call, its
reasoning for that turn should be carried into the next request, or it re-derives its thinking
from scratch on every iteration of the loop — paying for it twice, in tokens and in coherence.
Preserving it requires the passthrough field from section 1.

> **Verify before implementing:** the exact semantics of reasoning-state persistence on Chat
> Completions versus the Responses API. This determines whether `LlmClient` needs any notion of
> conversation state at all, or whether the passthrough field is sufficient. Read the current
> provider documentation rather than trusting recollection — this detail moves.

### Harness-side reasoning

Structure the agent imposes on itself: plan before acting, track progress, verify that the last
edit did what it was supposed to, decide when it is finished. This improves *multi-step*
behavior.

The existing loop is already a primitive form of this — observe, act, observe — which is why it
chains tools without being told to. It is also where that stops.

| Capability | Cost | Why |
|---|---|---|
| "Think step by step" in the system prompt | free | it is a string |
| A `plan` / `todo` tool the model writes into and reads back | one class, one registration | what the tool registry was for |
| Reacting to tool failure with corrective guidance | small | `ToolResult.Err` is already typed, so the loop can branch on failure rather than pass a string through untouched |
| Explicit plan-then-act phases | moderate | `Agent` grows a notion of phase; the loop is currently one flat `while` |
| Sub-agents | one class | a `Tool` that owns its own `Agent`. Works because `Agent` depends only on interfaces and does not care that its caller is another agent |

**Note:** `maxIterations` defaults to 8. That is sufficient for read-read-edit and nowhere near
enough for anything that plans. It is already a system property
(`-Dkonacode.maxIterations=...`) for exactly this reason — raise it in the same change that
adds planning, and expect to revisit the default.

## 3. Smaller deferred items

- **Path confinement — built.** It became `EffectPolicy`, which asks rather than denies. See
  [the design](docs/superpowers/specs/2026-08-24-approval-design.md).
- **Interactive approval — built.** `Decision` gained the `Ask` case this entry proposed. See
  [the design](docs/superpowers/specs/2026-08-24-approval-design.md).
- **`/compact`.** The command reads `conversation.messages()`, asks the model for a summary, and
  calls `conversation.restart(List.of(systemMessage, summary))`. It needs the `LlmClient`, which
  `Commands` does not hold today. This replaces the older plan to swap the conversation for one
  with a token budget. The user asks for it, and no policy decides.
- **A `run_command` tool — built.** It runs one shell line with `sh -c`. See
  [the design](docs/superpowers/specs/2026-08-27-run-command-design.md).
- **An approved command line can run code written later.** `a` remembers the exact line, and the
  line is honest. Its meaning is not: `make` reads a `Makefile`, and the model may change that file
  inside the project with no question, then run the approved line again with no question. No test
  of the characters in a line can see this. Decide whether an `ExactCommand` permission should end
  when a file inside the project changes.
- **`AllowAllPolicy` is the default for a piped session, and `run_command` now exists.** A pipe
  has no user to answer a question, so konacode allows every call there. That was one risk while
  every tool acted on a path. It is a larger one now, because a piped session runs any shell line
  with no question. Decide whether a pipe should refuse a `RUNS` call instead of allowing it.
- **Bounded retry in `OpenAiClient`.** The client makes exactly one attempt, so a single transient
  `429` or `5xx` discards a whole turn — costly for a loop that may make eight round trips per
  user message. Two or three attempts with backoff, scoped to `429`, `502`, `503`, `504` and
  `IOException`, and never to a `4xx`: the model cannot fix a 401, and retrying one wastes the
  user's time twice.
- **`finish_reason` is decoded and discarded.** A completion truncated at the token limit
  (`finish_reason: "length"`) is currently indistinguishable from a complete one. Plain text is
  silently cut off; a truncated tool call usually fails argument parsing and recovers by accident
  rather than by design. Capturing it would make truncation diagnosable.
- **A hard link defeats every path check, for a read.** `toRealPath` resolves a symbolic link and
  not a hard one, so a hard link inside the project to a file outside it is judged inside, and
  `read_file` and `edit_file` return the outside content. The write side is safe, and this was
  verified: `writeAtomic` moves a file onto the path and breaks the hard link, and `delete_file`
  removes one name while the other survives. A path check cannot close this. The answer is a
  check on the file after it is opened.
- **`Commands` reads a policy with `instanceof` in three places.** Twice in `label` and once in
  the warning. A third policy would be named "a policy konacode cannot name", which is loud, and
  would silently skip the warning, which is not. An abstract `ToolPolicy.label()`, or a method
  that says whether a policy asks questions, closes both. It is not worth the cost while two
  policies exist, because `ToolPolicy` has one method on purpose and the tests use it as a
  lambda.
- **Nothing proves the tools and the policy share a root.** `Main.build` builds both from one
  `Workspace`, so no caller can pass a mismatch. If someone changed `build` to use two, no test
  would notice: both wiring tests use an absolute path outside the project, and
  `Workspace.resolve` ignores the root for an absolute path. A test that reads a relative path
  inside the project through `build` would close it.

## 4. Streaming, and interrupting a turn

The user interface work left both of these out on purpose. Both change an interface outside the
`cli` package, so neither is a detail of the interface. Interrupting a turn is now built;
streaming is not.

### Streaming

`LlmClient.chat` returns a complete `AssistantMessage`. Streaming changes that contract: an
overload that takes a consumer, or a second method. Every provider then implements it.

Three things follow from that change.

The codec must read server-sent events, and not one JSON body. A tool call arrives in pieces, so
`ChatCompletionsCodec` must join the name and the arguments across several events before it can
build a `ToolCall`.

The markdown renderer needs a whole block. It cannot lay out a table or wrap a paragraph from half
of one. So `RichUi` must print raw text while it arrives and render the block when it closes, or
render nothing until the answer is complete. The second choice gives up most of the benefit.

The spinner then goes away. It exists because nothing appears while the model works.

### Interrupting a turn — **done**

Built. `esc` stops a turn. `ctrl-c` is untouched: it clears the line at the prompt, and it ends
konacode during a turn. See
[the design](docs/superpowers/specs/2026-08-23-interrupt-design.md).

Two notes for anyone reading the original entry, because the built design differs from what this
document proposed.

**The abort is a thread interrupt, not `sendAsync`.** `OpenAiClient` already translated
`InterruptedException`, so `LlmClient` did not change and no future provider inherits a
cancellation contract. `Cancellation` arms the interrupt around the provider call and around a
tool that answers `stopsOnInterrupt()` with true, and around nothing else.

**The cancel path finishes the results it owes.** This entry offered two options. They are not
equal. Removing the assistant message also removes the model's only record of what it did, and the
first thing a user says after a stop is often "undo that". So a tool call that never ran is
answered with an `Err` saying the user stopped the turn before it ran, and the whole turn stays in
the history.

Two things the built feature leans on, which are worth knowing before changing either:

- **`esc` needs a native JLine terminal provider.** The spike ran on `JniUnixSysTerminal`, where
  `terminal.reader().read(timeout)` delivers a keystroke while `readLine` is not running, and
  `ISIG` can be turned back on. If JLine ever falls back to a provider without that,
  `EscapeWatcher` fails quietly by design — it must not corrupt the screen the agent is writing —
  and the user loses `esc` with no message. Nobody has asked for a fallback yet.
- **`EditFile`'s guarantee leans on `writeAtomic`.** The guarantee is that the edit is fully
  applied or the file is untouched. It holds because the stop check sits on one side of
  `writeAtomic` and never inside. `writeAtomic` itself has two pre-existing paths that predate the
  guarantee and are not covered by it: a non-atomic fallback move that fails part way, and a
  `deleteIfExists` on the temporary file that fails after a successful move. Both are unlikely on
  a real filesystem, where the move is a same-directory rename.

## 5. A native Anthropic provider

Roughly 200 lines, and not a base-URL swap — the Messages API differs structurally from Chat
Completions. `system` is a top-level parameter rather than a message; tool results are
user-role content blocks rather than a `tool` role; completion is signalled by
`stop_reason: "tool_use"`; and thinking blocks must be returned unchanged when continuing on
the same model, which needs the passthrough field from section 1.

Two credential details worth handling when it is written:

- An unset `ANTHROPIC_API_KEY` does not mean there are no credentials. `ant auth login` stores
  an OAuth profile that the official SDKs resolve automatically. For hand-rolled HTTP that is
  `Authorization: Bearer <token>` plus an `anthropic-beta: oauth-2025-04-20` header, with the
  token from `ant auth print-credentials --access-token` — note `Authorization`, not
  `x-api-key`. Check for a profile before demanding a key.
- A Claude Pro/Max subscription does **not** grant API access, and cannot be used to
  authenticate konacode. This comes up often enough to be worth stating in the README.

Because the whole conversation is resent on every turn, prompt caching is worth more here than
in most applications. Design the codec so cache breakpoints have somewhere to go.
