# konacode — design

**Date:** 2026-08-21
**Status:** approved, pending implementation plan

Element definitions live in [CLAUDE.md](../../../CLAUDE.md) and are not repeated here. Design
rationale lives in [CONTEXT.md](../../../CONTEXT.md). Deferred work lives in
[FOLLOWUP.md](../../../FOLLOWUP.md). This document covers what those three do not: data flow,
the testing plan, and the file inventory the implementation plan will work from.

## Scope

A CLI coding agent: a REPL, an agent loop, three filesystem tools, one LLM provider. Four
extension seams (tools, provider, conversation, tool policy), each an interface with a default
implementation. Roughly 1100–1300 lines including tests.

Out of scope for the first cut: streaming, persistence, token budgets, sub-agents,
`run_command`, path confinement, reasoning support.

## Architecture

```
cli  ──►  agent  ──►  llm      (Message model + LlmClient SPI)
                 ──►  tools    (Tool, ToolRegistry, three tools, Workspace)
                 ──►  policy   (ToolPolicy, Decision)

llm/openai ──implements──► llm
```

`tools` does not depend on `llm`. The `ToolSpecs` adapter in `agent` is the only place the two
meet.

## Data flow

One user line, start to finish:

1. `Main` reads a line from stdin. Empty lines are skipped; EOF ends the session.
2. `Agent.respond(text)` appends a `UserMessage` to the `Conversation`.
3. Loop, at most `maxIterations` times:
   1. `LlmClient.chat(conversation.messages(), specs)` → `AssistantMessage`.
   2. Append the `AssistantMessage` to the conversation, tool calls included. This must happen
      before any tool runs — the provider rejects a `ToolMessage` whose originating assistant
      message is absent from the history.
   3. If `toolCalls` is empty, return `text`. Done.
   4. Otherwise, for each `ToolCall`, in order:
      - `ToolCallListener.onToolCall(name, argumentsJson)`.
      - `registry.lookup(name)` — absent yields `Err("Unknown tool: …")`.
      - Parse `argumentsJson`. A parse failure yields `Err`, never an exception.
      - `policy.check(tool, args)` — `Deny` yields `Err(reason)`.
      - `tool.execute(args)` → `ToolResult`. A thrown exception is caught and converted to
        `Err`; a misbehaving tool must not kill the loop.
      - `ToolCallListener.onToolResult(name, result)`.
      - Append a `ToolMessage(toolCallId, render(result))`.
   5. Continue.
4. Exhausting the loop returns `<error> Exceeded maximum tool iterations.` — returned, not
   thrown, so the session survives.
5. `LlmException` anywhere in the loop is caught at the top of `respond` and returned as
   `<error> …` text.

`render(ToolResult)` is `Ok(text) -> text` and `Err(msg) -> "<error> " + msg`. It is the single
point where typed results become the string the model reads.

## Error handling

Three channels, deliberately not merged — see CLAUDE.md § Error channels. The invariant worth
restating: **the loop never throws.** `respond` returns a string in every case, including
transport failure and iteration exhaustion, because a REPL that dies on a transient 500 is
worse than one that reports it.

## Configuration

| Source | Key | Default |
|---|---|---|
| env | `OPENAI_API_KEY` | none — fatal if absent or empty |
| env | `KONACODE_MODEL` | `gpt-5-mini` |
| env | `KONACODE_BASE_URL` | `https://api.openai.com/v1` |
| system property | `konacode.maxIterations` | `8`, via `Integer.getInteger` |

HTTP timeout is a constant in `OpenAiConfig` until there is a reason for it not to be.

## Testing

JUnit 5. Every test offline — no test may open a socket.

| Suite | ~Cases | Approach |
|---|---|---|
| `EditFileTest` | 12 | `@TempDir`. Create-on-empty-`old_str`; zero matches; exactly one; multiple matches refused; `old_str == new_str` refused; empty path; missing parent directory created; `$` and `\` in `new_str` survive verbatim; non-UTF-8 target; existing file with empty `old_str` refused. |
| `ListFilesTest` | 5 | `@TempDir`. Sorted order; `/` on directories; `@` on symlinks; empty directory; the 200-entry cap and its "… N more" line. |
| `ReadFileTest` | 5 | `@TempDir`. Small file; missing file; directory passed as a file; over-cap truncation; a cap landing mid-codepoint producing replacement characters rather than an error. |
| `AgentLoopTest` | 6 | `FakeLlmClient` returning a scripted queue of `AssistantMessage`s. Plain text reply; single tool call then reply; two calls in one message; unknown tool; policy `Deny`; iteration ceiling reached. Asserts on `RecordingToolCallListener` and on the conversation the fake received. |
| `ChatCompletionsCodecTest` | 4 | Fixture JSON under `src/test/resources/openai/`. Request shape for history plus tools; response with text only; response with tool calls; malformed response raising `LlmException`. |
| `ToolRegistryTest` | 2 | Lookup hit and miss. |
| `WorkspaceTest` | 4 | Relative, absolute and `~` resolution; normalization. |

Test doubles are hand-written — `FakeLlmClient`, `RecordingToolCallListener`, `FixedPolicy`. No
mocking framework.

## File inventory

```
pom.xml
src/main/java/dev/konacode/
  cli/Main.java  cli/Ansi.java  cli/ConsoleToolCallListener.java
  agent/Agent.java  agent/Conversation.java  agent/AppendOnlyConversation.java
  agent/ToolCallListener.java  agent/ToolSpecs.java
  tools/Tool.java  tools/ToolResult.java  tools/ToolRegistry.java  tools/Workspace.java
  tools/Schemas.java  tools/ListFiles.java  tools/ReadFile.java  tools/EditFile.java
  policy/ToolPolicy.java  policy/Decision.java  policy/AllowAllPolicy.java
  llm/Message.java  llm/ToolCall.java  llm/ToolSpec.java  llm/LlmClient.java
  llm/LlmException.java
  llm/openai/OpenAiConfig.java  llm/openai/ChatCompletionsCodec.java
  llm/openai/OpenAiClient.java
src/test/java/dev/konacode/...       (suites above, plus test doubles)
src/test/resources/openai/*.json     (recorded fixtures)
```

27 main classes. `Message` holds the four message records as nested types, so the sealed
hierarchy stays in one file.

## Build

Maven, Java 21 release target, `maven-shade-plugin` producing `target/konacode.jar` with
`dev.konacode.cli.Main` as the entry point. Dependencies: `jackson-databind`, and
`junit-jupiter` at test scope. Nothing else.

## Implementation order

Bottom-up, so each layer is testable when written:

1. `pom.xml`, package skeleton.
2. `tools` — `Tool`, `ToolResult`, `Workspace`, `Schemas`, then the three tools with their tests.
   This is where the real logic is, so it goes first.
3. `policy` — trivial, but `Agent` needs it to compile.
4. `llm` — the message model and the SPI. No implementation yet.
5. `agent` — the loop against `FakeLlmClient`. The loop is fully tested before any HTTP exists.
6. `llm/openai` — codec against fixtures first, then the transport around it.
7. `cli` — wiring and the REPL.
8. Manual smoke test against the live API.
