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

- **Path confinement.** `WorkspaceConfinedPolicy` — resolve symlinks, require the result to sit
  under the launch directory, return `Deny` otherwise. One class; `ToolPolicy` and `Workspace`
  already have the hooks. The default is `AllowAllPolicy` until then.
- **Interactive approval.** `AskUserPolicy` requires a third `Decision` case. Because `Decision`
  is sealed, adding `Ask` produces a compile error at every handling site — which is the
  intended behavior, not an obstacle.
- **Streaming.** Changes the shape of `LlmClient.chat`, which currently returns a complete
  `AssistantMessage`. Either an overload taking a consumer, or a second method. Worth doing only
  once the CLI can render partial output usefully.
- **Conversation trimming.** `Conversation` is an interface precisely so `AppendOnlyConversation`
  can be swapped for something with a token budget. No other component changes.
- **Observability for reply validation.** A retry inside the provider is currently invisible:
  nothing in the agent loop, the `ToolCallListener`, or the conversation records that a reply was
  refused and re-sent. A session that sent forty requests looks exactly like one that sent twenty.
  This is the accepted cost of repairing a model quirk below the loop — see
  `docs/superpowers/specs/2026-08-21-reply-validation-design.md`. To be addressed with logging.
- **A `run_command` tool.** One class, and a genuine safety question — it is the point at which
  `AllowAllPolicy` stops being a defensible default.
- **Bounded retry in `OpenAiClient`.** The client makes exactly one attempt, so a single transient
  `429` or `5xx` discards a whole turn — costly for a loop that may make eight round trips per
  user message. Two or three attempts with backoff, scoped to `429`, `502`, `503`, `504` and
  `IOException`, and never to a `4xx`: the model cannot fix a 401, and retrying one wastes the
  user's time twice.
- **`finish_reason` is decoded and discarded.** A completion truncated at the token limit
  (`finish_reason: "length"`) is currently indistinguishable from a complete one. Plain text is
  silently cut off; a truncated tool call usually fails argument parsing and recovers by accident
  rather than by design. Capturing it would make truncation diagnosable.

## 4. A native Anthropic provider

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
