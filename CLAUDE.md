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
mvn test                        # 184 tests, all offline, no network
mvn package                     # produces an executable jar
OPENAI_API_KEY=sk-... java -jar target/konacode.jar
```

konacode keeps one rule for configuration. The environment configures the provider. A system
property configures konacode.

| Name | Kind | Required | Default |
|---|---|---|---|
| `OPENAI_API_KEY` | environment | yes | — |
| `KONACODE_MODEL` | environment | no | `gpt-5-mini` |
| `KONACODE_BASE_URL` | environment | no | `https://api.openai.com/v1` |
| `konacode.maxIterations` | property | no | `8` |
| `konacode.ui` | property | no | `auto` |

This rule keeps the key out of the process list. konacode reads no command line argument.

A wrong value fails loudly. Both properties print one line and exit 1.

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
| `Conversation` | final class | `add(Message)`, `messages()`, `restart(List<Message>)`. The history of one session, and the only state the loop keeps. It is a class and not an interface, because `messages()` and `restart` together cover every change to the history. A caller reads all of it, transforms it, and writes all of it back. `/clear` and `/compact` both work that way. |
| `ToolCallListener` | interface | `onToolCall(name, argsJson)`, `onToolResult(name, ToolResult)`. How the loop reports activity without owning `System.out` — and how tests assert on it. |
| `ToolSpecs` | static adapter | `Tool` to `ToolSpec`. The one place `tools` and `llm` meet. |

### `dev.konacode.cli`

| Element | Kind | Definition |
|---|---|---|
| `Ui` | interface | Everything konacode shows the user, and the one thing it reads from them. It extends `ToolCallListener`, because showing a tool call is a user interface concern. One object then owns the screen. |
| `PlainUi` | implements `Ui` | The interface for a pipe. It reads with a `BufferedReader` and prints what konacode printed before there were two interfaces. It renders no markdown and shows no spinner. |
| `RichUi` | implements `Ui` | The interface for a terminal. JLine gives the line editing, the history in `~/.konacode/chat_history`, and `alt-enter` for a second line. It renders markdown and drives the spinner. The constructor takes every collaborator, and `open()` builds the real ones, which is why the class can have tests. |
| `Repl` | final class | The loop. Read a line, skip it when empty, run it as a command when it starts with `/`, otherwise ask the agent. Both interfaces share it. |
| `Commands` | final class | `/help`, `/tools`, `/clear` and `/exit`. `run` returns false when the session must end, so every command lives in one class and `Repl` gains one line. A command writes markdown, so the rich interface renders it and needs no second output method. An unknown command prints an error and never reaches the model. |
| `Spinner` | class | One daemon thread that draws and erases a character while the agent works. `RichUi` stops it before every write of its own. It is not final, so a test can record the calls. |
| `Banner` | final class | The art from the README, which reads `kona`. It is 41 columns wide, so a narrower terminal gets the plain name. Generated from `README.md`, not retyped. |
| `Ansi` | final class | The escape codes, plus `strip` and `visibleLength`. A code takes bytes and no columns, so word wrap and table alignment both need `visibleLength`. |
| `Main` | final class | Reads the environment, picks the interface, wires the parts. The only place that names a concrete implementation. |

### `dev.konacode.cli.markdown`

| Element | Kind | Definition |
|---|---|---|
| `Markdown` | final class | `render(String, int width)`. The whole surface. Mordant would replace everything behind it, but konacode cannot use Mordant. See FOLLOWUP.md. |
| `AnsiRenderer` | final class | Walks the commonmark tree. Two rules keep the blank lines right: `emit` and `code` never add one, and every top level block adds one for itself. A hard line break ends the line. A soft one becomes a space, which is what markdown means. |
| `Wrap` | final class | Wraps styled text at a space, and repeats the open style after a break. |

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
- Dependencies: konacode has no agent framework, no HTTP client library, and no dependency
  injection container. Those three hide the mechanism this project exists to show. A library that
  solves a different problem is allowed. Jackson, JLine, commonmark, JUnit 5, and Mockito are
  allowed. A new dependency is a conversation, not a commit. Check the version with Meterian
  before you pin it, and read `maven-metadata.xml` for the newest version, because the search
  endpoint at `search.maven.org` sorts by relevance and reports an old version as the newest.
- TDD. Test first, and keep the suite offline. No test may touch the network.
- Write the test double by hand when the type is ours. `FakeLlmClient` and
  `RecordingToolCallListener` are small, explicit, and they read well. Use Mockito when the type
  belongs to a library and a hand-written double is impractical, for example the JLine
  `LineReader`. A class that needs a mock takes its collaborators in the constructor.
- Tool descriptions are prompt engineering. Changing one changes agent behavior; treat it like changing code.
