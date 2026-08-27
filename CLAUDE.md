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
mvn test                        # 498 tests, all offline, no network
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
| `konacode.trace` | property | no | `off` |
| `konacode.trace.maxFiles` | property | no | `100` |
| `konacode.command.timeoutSeconds` | property | no | `600` |

This rule keeps the key out of the process list. konacode reads no command line argument.

A wrong value fails loudly. Every property prints one line and exits 1.

## Architecture rule

[ARCHITECTURE.md](ARCHITECTURE.md) has the runtime picture — how a turn runs, and the
invariants that hold it together. Read it before changing the loop.

Dependencies run strictly downhill:

```
cli -> agent -> { llm, tools, policy } -> trace
cli -> skills -> tools
```

**`tools` must not depend on `llm`.** A tool exposes a name, a description and a JSON schema
as plain types; the `ToolSpecs` adapter in `agent` translates that into whatever a provider
needs. This keeps tools writable without knowing an LLM exists. If you find yourself importing
`dev.konacode.llm` from `dev.konacode.tools`, the adapter is the answer, not the import.

The approval seam keeps three jobs apart. The tool states a fact, the policy decides, and the
loop asks the user. No part does two of those jobs.

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
| `Usage` | record `(prompt, completion, total)` | The token counts of one reply. `ChatCompletionsCodec.decodeUsage` reads them, and never throws: a count is a diagnostic, so a reply konacode cannot read here has no counts and is not a failed turn. |
| `ChatCompletionsCodec` | final class, pure | Translates `Message`/`ToolSpec` to request JSON and response JSON back to `AssistantMessage`. **Contains no HTTP.** This is what makes the wire format testable against fixtures. |
| `OpenAiClient` | implements `LlmClient` | `java.net.http.HttpClient` plus the codec. Owns status handling and error translation, nothing else — it retries only through `ReplyValidator`, on a garbled reply; a transient HTTP failure gets no retry, see FOLLOWUP.md. |
| `ReplyValidator` | class, one for each request | Finds a tool call that the model wrote as prose. Owns the budget for a second attempt. `accepts` is final, so the retry loop in the client always stops. `isMisencodedToolCall` is the extension point for the quirk of another model. |

### `dev.konacode.tools`

| Element | Kind | Definition |
|---|---|---|
| `Tool` | interface | `name()`, `description()`, `inputSchema()`, `ToolResult execute(JsonNode args)`, `boolean stopsOnInterrupt()`, `Action computeAction(JsonNode args)`. The description is written for the model to read — it is prompt text, not a code comment. `stopsOnInterrupt` and `computeAction` are abstract and not a default, so a new tool must answer both, the way the sealed `Decision` makes a new case a compile error everywhere. |
| `Effect` | enum | `READS_INSIDE`, `READS_OUTSIDE`, `WRITES_INSIDE`, `WRITES_OUTSIDE`, `RUNS`. What one call to a tool does. The tool states this fact and decides nothing; a `ToolPolicy` reads it and decides. |
| `Action` | record `(Effect effect, String operand, Optional<Permission> permission)` | What one call does, what it acts on, and what a standing "always" would cover. An empty permission says that no standing "always" can describe this call. |
| `Permission` | sealed interface | `InFolder(toolName, folder)` or `ExactCommand(toolName, command)`. konacode compares two permissions and never examines one, so a record gives the whole lookup, and a sealed set makes a third kind a compile error at `inWords`. |
| `Actions` | static helper, package-private | `read`, `write` and `readThenWrite` build the `Action` of a tool that acts on one path. Three named entry points, because the two questions a path needs must agree, and two loose lambdas let a caller pair them wrongly. |
| `ToolResult` | sealed interface | `Ok(String text)` or `Err(String message)`. Typed rather than a bare string so the loop and the policy can react to failure without sniffing for `"<error>"`. |
| `ToolRegistry` | final class | Name-to-`Tool` map. `lookup(String)` returns `Optional`; `all()` enumerates. |
| `ListFiles` | implements `Tool` | Directory snapshot, sorted, capped at 200 entries. Directories get a `/` suffix, symlinks `@`. |
| `ReadFile` | implements `Tool` | File contents, capped at 100 KB. Decodes with malformed-input replacement rather than failing, so a cap landing mid-codepoint is not reported as "binary file". |
| `EditFile` | implements `Tool` | Exact-match replacement. Refuses zero matches, refuses more than one, refuses `old_str == new_str`. Creates the file when `old_str` is empty and the file does not exist. Replacement is **literal** — `String.replace`, never `replaceAll`, which would treat `$` and `\` in the model's `new_str` as replacement-template syntax and silently corrupt the edit. |
| `DeleteFile` | implements `Tool` | Removes one file. Refuses a directory. On a symbolic link it removes the link, never the target. It reaches any path the other tools reach, with no confirmation and no copy — see the design for the two places a control layer will land. |
| `RunCommand` | implements `Tool` | Runs one shell line with `sh -c`, in the project directory, with the two output streams merged and no standard input. The last line is `<exit N>`. A non-zero exit code is `Ok`, because konacode ran the command and the command answered. It offers an `ExactCommand` permission only when the line holds none of `$` `` ` `` `*` `?` `[` `~`, because a line that expands means something else on another day. `esc` and a timeout both end it. |
| `CappedOutput` | final class, package-private | Keeps the first 50 KB and the last 50 KB of a stream, and names the lines and the bytes it removed with `<removed …>`. |
| `StopCheck` | interface | `boolean stopped()`. The one question a tool asks between two steps of its work. It lives here and not in `agent`, because `agent` already depends on `tools` and the reverse import would close a cycle. `NEVER` serves every tool and test that does not stop. |
| `Workspace` | final class | Owns every filesystem *operation* — resolving relative, `~` and absolute paths against a root, plus `readUtf8Capped`, `writeAtomic`, `listSorted`, `delete`. `readUtf8Capped` and `listSorted` each take a `StopCheck`, so the user can stop a long read or a long listing between steps. Tools call bare `Files.exists` / `isDirectory` / `isSymbolicLink` predicates inline; everything that reads, writes or enumerates goes through here. It also owns every judgement a policy needs about a path — inside the root, readable, writable — and resolves the real file or folder a question about that path must name. |
| `Schemas` | static helper | Builds tool input schemas without repeating Jackson boilerplate. |

### `dev.konacode.policy`

| Element | Kind | Definition |
|---|---|---|
| `ToolPolicy` | interface | `Decision check(Tool tool, JsonNode args)`. Consulted before every tool execution. |
| `Decision` | sealed interface | `Allow`, `Deny(String reason)`, or `Ask(String toolName, String intent, String operand, Optional<Permission> permission)`. Sealed on purpose: a new case is a compile error at every handling site. |
| `EffectPolicy` | implements `ToolPolicy` | Allows a call inside the launch directory. Asks about every other one. It holds no state: it reads the `Action` the tool states, and it adds only the words. |
| `SelectedPolicy` | implements `ToolPolicy` | The policy in use now. `/policy` changes it while a session runs; `Agent` holds this one policy and never learns that the choice can change. |
| `AllowAllPolicy` | implements `ToolPolicy` | Allows every call. The default for an interface that cannot ask a question. A user chooses it with `/policy allow-all`. |

### `dev.konacode.skills`

| Element | Kind | Definition |
|---|---|---|
| `Skill` | record `(String name, String description, Path folder)` | One skill, without its body. `name` is the folder name the user types after `/skill`. |
| `SkillRegistry` | final class | Reads the skills folder on every call, so a new skill needs no restart, and a missing folder gives an empty list. Holds no `StopCheck`, because `/skill` runs at the prompt, not inside a turn, so there is no turn to stop. |
| `FrontMatter` | record, package-private | The header of a `SKILL.md`, and the body after it. Not a YAML parser — it reads only a `name` key and a `description` key, each on one line. |
| `SkillException` | RuntimeException | The name the user typed is not a folder name, or the folder holds no readable `SKILL.md`. Not a tool failure, and it never reaches the model. |

### `dev.konacode.trace` — what happened during a turn

| Element | Kind | Definition |
|---|---|---|
| `TraceEvent` | sealed interface | One thing that happened. Nine records. Each carries strings, numbers and booleans only, so this package depends on no other konacode package and both `agent` and `llm` can emit into it. |
| `Trace` | interface | `void emit(TraceEvent)`. `NONE` discards, `fanOut` combines. A sink never throws into the caller. |
| `Level` | enum | `OFF`, `BASIC`, `FULL`. `keep(TraceEvent)` gives back the event a level keeps, with the payloads already cut. The rule lives here, because each sink holds its own level. |
| `JsonlTrace` | implements `Trace` | The file sink. One JSON line for each event, in `~/.konacode/traces/`, one file for each session. It sweeps the oldest files when it opens, and it flushes every line. |

### `dev.konacode.agent`

| Element | Kind | Definition |
|---|---|---|
| `Agent` | final class | `String respond(String userText)`. The loop. Depends only on interfaces. |
| `Conversation` | final class | `add(Message)`, `messages()`, `restart(List<Message>)`. The history of one session, and the only state the loop keeps. It is a class and not an interface, because `messages()` and `restart` together cover every change to the history. A caller reads all of it, transforms it, and writes all of it back. `/clear` and `/compact` both work that way. |
| `Cancellation` | final class | The user's request to stop one turn. `request` and `stopped` are public; `arm` and `disarm` are not, because only the loop may decide where an interrupt is safe. One lock keeps an interrupt from arriving after the clear. Implements `StopCheck`. |
| `ToolApproval` | interface | `Answer ask(Decision.Ask ask)`, `boolean canAsk()`. `Answer` is `YES`, `NO` or `ALWAYS`. The loop asks, and not the policy, because `Cancellation` lives here and only the loop knows where an interrupt is safe. |
| `Approvals` | final class | The set of permissions the user gave during this session. Coverage is equality. The memory sits here and not in the policy, so `/policy` changes the policy and the answers stay. Nothing is written to disk. |
| `ToolSpecs` | static adapter | `Tool` to `ToolSpec`. The one place `tools` and `llm` meet. |

### `dev.konacode.cli`

| Element | Kind | Definition |
|---|---|---|
| `Ui` | interface | Everything konacode shows the user, and the one thing it reads from them. It extends `Trace`, because showing what the agent did is a user interface concern, and it gains `liveTrace`, the level the screen shows. It extends `ToolApproval` for the same reason: asking a question is a user interface concern too. One object then owns the screen and the keyboard. |
| `PlainUi` | implements `Ui` | The interface for a pipe. It reads with a `BufferedReader` and prints what konacode printed before there were two interfaces. It renders no markdown and shows no spinner. |
| `RichUi` | implements `Ui` | The interface for a terminal. JLine gives the line editing, the history in `~/.konacode/chat_history`, and `alt-enter` for a second line. It renders markdown, and it owns the spinner and the `EscapeWatcher`. `emit` stops the spinner before it prints a line, and restarts it once a tool finishes; the watcher keeps running, so ESC still works while a tool runs. The constructor takes every collaborator, and `open()` builds the real ones, which is why the class can have tests. |
| `Repl` | final class | The loop. Read a line, skip it when empty, run it as a command when it starts with `/`, otherwise ask the agent. Both interfaces share it. |
| `Commands` | final class | `/help`, `/tools`, `/skill`, `/trace`, `/policy`, `/clear` and `/exit`. `run` returns false when the session must end, so every command lives in one class and `Repl` gains one line. A command writes markdown, so the rich interface renders it and needs no second output method. An unknown command prints an error and never reaches the model. |
| `EscapeWatcher` | class | Reads the terminal during a turn and calls `Cancellation.request()` on the byte `0x1B`. A sibling of `Spinner`: one daemon thread, `start` and `stop`, both idempotent, not final so a test can record. Raw mode keeps `ISIG` on, so ctrl-C still ends konacode. |
| `Spinner` | class | One daemon thread that draws and erases a character while the agent works. `RichUi` stops it before every write of its own. It is not final, so a test can record the calls. |
| `Banner` | final class | The art from the README, which reads `kona`. It is 41 columns wide, so a narrower terminal gets the plain name. Generated from `README.md`, not retyped. |
| `Ansi` | final class | The escape codes, plus `strip` and `visibleLength`. A code takes bytes and no columns, so word wrap and table alignment both need `visibleLength`. |
| `TraceLine` | final class | `of(TraceEvent)`. One event as one line of text. `PlainUi` and `RichUi` both call it, so the two interfaces show the same words. |
| `Main` | final class | Reads the environment, picks the interface, wires the parts. The only place that names a concrete implementation. |

### `dev.konacode.cli.markdown`

| Element | Kind | Definition |
|---|---|---|
| `Markdown` | final class | `render(String, int width)`. The whole surface. Mordant would replace everything behind it, but konacode cannot use Mordant. See FOLLOWUP.md. |
| `AnsiRenderer` | final class | Walks the commonmark tree. Two rules keep the blank lines right: `emit` and `code` never add one, and every top level block adds one for itself. A hard line break ends the line. A soft one becomes a space, which is what markdown means. |
| `Wrap` | final class | Wraps styled text at a space, and repeats the open style after a break. |

## Error channels

Four, deliberately not merged:

1. **Tool failure** — `ToolResult.Err`, rendered `<error> …` and appended to the conversation as a `ToolMessage`. The model reads it and recovers. This is a normal part of operation, not an exception.
2. **Policy denial** — also an `Err`, so a refusal is something the model can route around rather than a crash.
3. **No approval** — also an `Err`. The policy returned `Ask`, and the user said no, or the interface could not ask. This is not a policy denial: the policy asked for a decision and did not make one.
4. **Transport/protocol failure** — `LlmException`, caught at the top of `respond` and surfaced to the human. The model cannot fix a 401.

Never promote a tool failure to an exception. Never hand an `LlmException` to the model.

**An `Err` the model reads is prompt text.** Treat it the way you treat a tool description. Name one
call and one path. A message that names a kind of call teaches the model a rule: the first refusal
said "to read outside this project", and the model then stopped calling the tool at all, so konacode
never asked again and the user could not say yes.

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
- Write literally. Do not use a metaphor or an idiom.

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
  `RecordingTrace` are small, explicit, and they read well. Use Mockito when the type
  belongs to a library and a hand-written double is impractical, for example the JLine
  `LineReader`. A class that needs a mock takes its collaborators in the constructor.
- Tool descriptions are prompt engineering. Changing one changes agent behavior; treat it like changing code.
- Every git worktree lives in the `.worktree/` directory inside the project root. The folder takes
  the whole name of the branch, with each `/` written as `-`, so `feat/approval` becomes
  `.worktree/feat-approval`. A throwaway worktree for a review or a probe takes a `review-` prefix,
  so it cannot collide with the checkout of the branch it reviews. Remove a worktree when its
  branch merges.
