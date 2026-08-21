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
extended-thinking blocks — which carry signatures that must be returned unmodified or the
request is rejected — and provider fields not yet encountered. Each becomes a codec-local
change instead of a hierarchy-wide one.

**Recommendation:** add it with the first provider change, and no later.

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
- **A `run_command` tool.** One class, and a genuine safety question — it is the point at which
  `AllowAllPolicy` stops being a defensible default.
