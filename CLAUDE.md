# konacode

A coding agent in Java 21: a loop, a set of tools, and a language model.

The agent loop is the whole trick. Send the conversation and the tool descriptions to the
model; if it answers with text, print it; if it answers with a tool call, run the tool, append
the result, and go round again. Everything around that loop — tools, LLM providers,
conversation handling, tool approval — is an interface with a default implementation, so
extending any of them is a new class rather than a rewrite.

## Commands

```bash
sdk use java 21.0.2-open        # the default java on this machine is 11; konacode needs 21
mvn test                        # 108 tests, all offline, no network
mvn package                     # produces an executable jar
OPENAI_API_KEY=sk-... java -jar target/konacode.jar
```

Configuration is environment-only:

| Variable | Required | Default |
|---|---|---|
| `OPENAI_API_KEY` | yes | — |
| `KONACODE_MODEL` | no | `gpt-5-mini` |
| `KONACODE_BASE_URL` | no | `https://api.openai.com/v1` |

Plus one system property: `-Dkonacode.maxIterations=8` caps tool-call iterations per user message.

## Architecture rule

[ARCHITECTURE.md](ARCHITECTURE.md) has the runtime picture — how a turn runs, and the
invariants that hold it together. Read it before changing the loop.

Dependencies run strictly downhill:

```
cli -> agent -> { llm, tools, policy }
```

**`tools` must not depend on `llm`.** A tool exposes a name, a description and a JSON schema
as plain types; the `ToolSpecs` adapter in `agent` translates that into whatever a provider
needs. This keeps tools writable without knowing an LLM exists. If you find yourself importing
`dev.konacode.llm` from `dev.konacode.tools`, the adapter is the answer, not the import.

## Definitions

### `dev.konacode.llm` — provider-neutral conversation model

| Element | Kind | Definition |
|---|---|---|
| `Message` | sealed interface | One entry in the conversation. Permits the four below. |
| `SystemMessage` | record `(String text)` | The standing instruction, first in history, never removed. |
| `UserMessage` | record `(String text)` | One line typed by the human. |
| `AssistantMessage` | record `(String text, List<ToolCall> toolCalls)` | The model's reply. Carries text, tool calls, or both. |
| `ToolMessage` | record `(String toolCallId, String content)` | The result of one tool call, keyed back to the call that produced it. |
| `ToolCall` | record `(String id, String name, String argumentsJson)` | A model request to run a tool. Arguments stay a raw JSON **string**, exactly as emitted — never pre-parsed, so a malformed argument is the tool's problem to report, not a transport failure. |
| `ToolSpec` | record `(String name, String description, ObjectNode schema)` | What a provider advertises to the model. |
| `LlmClient` | interface | `AssistantMessage chat(List<Message>, List<ToolSpec>)`. Blocking. The entire provider SPI. |
| `LlmException` | RuntimeException | Transport or protocol failure. Not a tool failure — see Error channels. |

### `dev.konacode.llm.openai` — the one implementation

| Element | Kind | Definition |
|---|---|---|
| `OpenAiConfig` | record `(apiKey, model, baseUrl, timeout)` | Provider settings. |
| `ChatCompletionsCodec` | final class, pure | Translates `Message`/`ToolSpec` to request JSON and response JSON back to `AssistantMessage`. **Contains no HTTP.** This is what makes the wire format testable against fixtures. |
| `OpenAiClient` | implements `LlmClient` | `java.net.http.HttpClient` plus the codec. Owns status handling and error translation, nothing else — there is no retry; see FOLLOWUP.md. |
| `ReplyValidator` | class, one for each request | Finds a tool call that the model wrote as prose. Owns the budget for a second attempt. `accepts` is final, so the retry loop in the client always stops. `isMisencodedToolCall` is the extension point for the quirk of another model. |

### `dev.konacode.tools`

| Element | Kind | Definition |
|---|---|---|
| `Tool` | interface | `name()`, `description()`, `inputSchema()`, `ToolResult execute(JsonNode args)`. The description is written for the model to read — it is prompt text, not a code comment. |
| `ToolResult` | sealed interface | `Ok(String text)` or `Err(String message)`. Typed rather than a bare string so the loop and the policy can react to failure without sniffing for `"<error>"`. |
| `ToolRegistry` | final class | Name-to-`Tool` map. `lookup(String)` returns `Optional`; `all()` enumerates. |
| `ListFiles` | implements `Tool` | Directory snapshot, sorted, capped at 200 entries. Directories get a `/` suffix, symlinks `@`. |
| `ReadFile` | implements `Tool` | File contents, capped at 100 KB. Decodes with malformed-input replacement rather than failing, so a cap landing mid-codepoint is not reported as "binary file". |
| `EditFile` | implements `Tool` | Exact-match replacement. Refuses zero matches, refuses more than one, refuses `old_str == new_str`. Creates the file when `old_str` is empty and the file does not exist. Replacement is **literal** — `String.replace`, never `replaceAll`, which would treat `$` and `\` in the model's `new_str` as replacement-template syntax and silently corrupt the edit. |
| `Workspace` | final class | Owns every filesystem *operation* — resolving relative, `~` and absolute paths against a root, plus `readUtf8Capped`, `writeAtomic`, `listSorted`. Tools call bare `Files.exists` / `isDirectory` / `isSymbolicLink` predicates inline; everything that reads, writes or enumerates goes through here. Where path confinement will hook in when it is added. |
| `Schemas` | static helper | Builds tool input schemas without repeating Jackson boilerplate. |

### `dev.konacode.policy`

| Element | Kind | Definition |
|---|---|---|
| `ToolPolicy` | interface | `Decision check(Tool tool, JsonNode args)`. Consulted before every tool execution. |
| `Decision` | sealed interface | `Allow` or `Deny(String reason)`. Sealed on purpose: adding `Ask` later becomes a compile error at every handling site. |
| `AllowAllPolicy` | implements `ToolPolicy` | The default. Always allows. The seam exists; the restriction does not, yet. |

### `dev.konacode.agent`

| Element | Kind | Definition |
|---|---|---|
| `Agent` | final class | `String respond(String userText)`. The loop. Depends only on interfaces. |
| `Conversation` | interface | `add(Message)`, `messages()`. |
| `AppendOnlyConversation` | implements `Conversation` | Appends forever, never trims. |
| `ToolCallListener` | interface | `onToolCall(name, argsJson)`, `onToolResult(name, ToolResult)`. How the loop reports activity without owning `System.out` — and how tests assert on it. |
| `ToolSpecs` | static adapter | `Tool` to `ToolSpec`. The one place `tools` and `llm` meet. |

### `dev.konacode.cli`

`Main` (env parsing, wiring, REPL), `Ansi` (three colour codes), `ConsoleToolCallListener` (prints `tool: name({...})`).

## Error channels

Three, deliberately not merged:

1. **Tool failure** — `ToolResult.Err`, rendered `<error> …` and appended to the conversation as a `ToolMessage`. The model reads it and recovers. This is a normal part of operation, not an exception.
2. **Policy denial** — also an `Err`, so a refusal is something the model can route around rather than a crash.
3. **Transport/protocol failure** — `LlmException`, caught at the top of `respond` and surfaced to the human. The model cannot fix a 401.

Never promote a tool failure to an exception. Never hand an `LlmException` to the model.

## Questions

Give each question a number. Put the number at the start of the question. The reader can then
answer with the number only.

## Comments

Do not write a comment that repeats the code. Write a comment only when a reader cannot
understand the code without it. Give the reason, not the action.

Javadoc on a public type or method is different. Write it when the contract needs an
explanation.

## Writing style

Write all documents and all replies in ASD-STE100 Simplified Technical English.

- Write short sentences. Use 20 words or less in a procedural sentence.
- Write one instruction in one sentence.
- Use the active voice.
- Use the same word for the same idea. Do not use synonyms.
- Use articles. Write "the file", not "file".
- Do not put more than three nouns together.
- Start each paragraph with the topic sentence.
- Write positive statements. Do not put two negatives in one sentence.
- Use simple verb tenses.

## Conventions

- Java 21. Records for data, sealed interfaces for closed sets, pattern-matching switch over them.
- Dependencies: Jackson and JUnit 5. Nothing else. No agent framework, no HTTP client library, no DI container. If a new dependency seems necessary, that is a conversation, not a commit.
- TDD. Test first, and keep the suite offline — no test may touch the network.
- Tool descriptions are prompt engineering. Changing one changes agent behavior; treat it like changing code.
