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
mvn test                        # 684 tests, all offline, no network
mvn package                     # produces an executable jar
OPENAI_API_KEY=sk-... java -jar target/konacode.jar
```

konacode keeps one rule for configuration. The environment configures the provider. A system
property configures konacode.

| Name | Kind | Required | Default |
|---|---|---|---|
| `OPENAI_API_KEY` | environment | yes | — |
| `KONACODE_MODEL` | environment | no | `gpt-5-mini` |
| `KONACODE_JUDGE_MODEL` | environment | no | the value of `KONACODE_MODEL` |
| `KONACODE_BASE_URL` | environment | no | `https://api.openai.com/v1` |
| `konacode.maxIterations` | property | no | `8` |
| `konacode.maxIterations.whenPlanning` | property | no | `24` |
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
loop asks the user. No part does two of those jobs. A standing permission is a decision the user
already made, so the loop applies it before it consults the policy.

**A question shows a string the model chose, so it goes through `Ansi.oneLine` first.** The model
picks the path, the command line, the name of a tool and the arguments of a call. A newline in one
of those draws a second question below the real one, and an escape code repaints the screen, so the
user approves something they did not read. A user cannot approve what they cannot read. The two
words konacode writes, the name in the first line of a question and the sentence beside it, need no
guard: each tool writes its own `name()` into the `Action` it states, so `EffectPolicy` writes
`action.toolName()` and never `call.name()`.

**A line ends with the payload the model chose, and puts no delimiter around it.** A delimiter is a
character the model can write too: a backtick around an operand let the operand close the backtick
and write a verdict of its own. A payload with nothing after it can forge nothing.

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
| `OpenAiConfig` | record `(apiKey, model, judgeModel, baseUrl, timeout)` | Provider settings. `forJudge()` gives the same key, base URL and timeout, with the model the judge uses. |
| `Usage` | record `(prompt, completion, total)` | The token counts of one reply. `ChatCompletionsCodec.decodeUsage` reads them, and never throws: a count is a diagnostic, so a reply konacode cannot read here has no counts and is not a failed turn. |
| `ChatCompletionsCodec` | final class, pure | Translates `Message`/`ToolSpec` to request JSON and response JSON back to `AssistantMessage`. **Contains no HTTP.** This is what makes the wire format testable against fixtures. |
| `OpenAiClient` | implements `LlmClient` | `java.net.http.HttpClient` plus the codec. Owns status handling and error translation, nothing else. It holds two retry loops, and two budgets. `sendUntilAccepted` repairs the protocol through `ReplyValidator`, on a garbled reply. `sendUntilDelivered` repairs the transport: three attempts, and a wait of 500 ms then 1 s, which a test replaces with a `Backoff` that does not sleep. The two budgets stay apart, so a garbled reply on a poor network spends neither twice. |
| `TransientFailure` | extends `LlmException`, package-private | A failure another attempt may pass: `429`, `502`, `503`, `504`, or a request that did not arrive. `sendOnce` states this fact and decides nothing; `sendUntilDelivered` reads the type and decides. It carries `retryReason` beside the message, because the message holds the answer of the provider and a trace line must carry only the words konacode wrote. Every other status ends the turn at once: the model cannot fix a 401, and a second attempt wastes the time of the user twice. |
| `ReplyValidator` | class, one for each request | Finds a tool call that the model wrote as prose. Owns the budget for a second attempt. `accepts` is final, so the retry loop in the client always stops. `isMisencodedToolCall` is the extension point for the quirk of another model. |

### `dev.konacode.tools`

| Element | Kind | Definition |
|---|---|---|
| `Tool` | interface | `name()`, `description()`, `inputSchema()`, `ToolResult execute(JsonNode args)`, `boolean stopsOnInterrupt()`, `Action computeAction(JsonNode args)`. The description is written for the model to read — it is prompt text, not a code comment. `stopsOnInterrupt` and `computeAction` are abstract and not a default, so a new tool must answer both, the way the sealed `Decision` makes a new case a compile error everywhere. |
| `Effect` | enum | `READS_INSIDE`, `READS_OUTSIDE`, `WRITES_INSIDE`, `WRITES_OUTSIDE`, `RUNS`, `NONE`. What one call to a tool does. The tool states this fact and decides nothing; a `ToolPolicy` reads it and decides. `NONE` is the value of a call that reaches nothing outside the session: it names no place, and a policy asks no question about it. |
| `Action` | record `(String toolName, Effect effect, String toolOperand, Optional<Permission> standingPermission)` | The name of the tool that states the action, what one call does, what it acts on, and what a standing "always" would cover. An empty permission says that no standing "always" can describe this call. |
| `Permission` | sealed interface | `InFolder(toolName, folder)` or `ExactCommand(toolName, command)`. konacode compares two permissions and never examines one, so a record gives the whole lookup, and a sealed set makes a third kind a compile error at `inWords`. |
| `Actions` | static helper, package-private | `read`, `write` and `readThenWrite` build the `Action` of a tool that acts on one path. Three named entry points, because the two questions a path needs must agree, and two loose lambdas let a caller pair them wrongly. |
| `ToolResult` | sealed interface | `Ok(String text)` or `Err(String message)`. Typed rather than a bare string so the loop and the policy can react to failure without sniffing for `"<error>"`. |
| `ToolRegistry` | final class | Name-to-`Tool` map. `lookup(String)` returns `Optional`; `all()` enumerates. |
| `ListFiles` | implements `Tool` | Directory snapshot, sorted, capped at 200 entries. Directories get a `/` suffix, symlinks `@`. |
| `ReadFile` | implements `Tool` | File contents, capped at 100 KB. Decodes with malformed-input replacement rather than failing, so a cap landing mid-codepoint is not reported as "binary file". |
| `EditFile` | implements `Tool` | Exact-match replacement. Refuses zero matches, refuses more than one, refuses `old_str == new_str`. Creates the file when `old_str` is empty and the file does not exist. Replacement is **literal** — `String.replace`, never `replaceAll`, which would treat `$` and `\` in the model's `new_str` as replacement-template syntax and silently corrupt the edit. |
| `DeleteFile` | implements `Tool` | Removes one file. Refuses a directory. On a symbolic link it removes the link, never the target. It reaches any path the other tools reach, with no confirmation and no copy — see the design for the two places a control layer will land. |
| `RunCommand` | implements `Tool` | Runs one shell line with `sh -c`, in the project directory, with the two output streams merged and no standard input. The last line is `<exit N>`. A non-zero exit code is `Ok`, because konacode ran the command and the command answered. It offers an `ExactCommand` permission only when the line holds none of `$` `` ` `` `*` `?` `[` `~`, because a line that expands means something else on another day. `esc` and a timeout both end it, and the `Err` holds what the command printed first. |
| `CappedOutput` | final class, package-private | Keeps the first 50 KB and the last 50 KB of a stream, and names the lines and the bytes it removed with `<removed …>`. |
| `StopCheck` | interface | `boolean stopped()`. The one question a tool asks between two steps of its work. It lives here and not in `agent`, because `agent` already depends on `tools` and the reverse import would close a cycle. `NEVER` serves every tool and test that does not stop. |
| `Workspace` | final class | Owns every filesystem *operation* — resolving relative, `~` and absolute paths against a root, plus `readUtf8Capped`, `writeAtomic`, `listSorted`, `delete`. `readUtf8Capped` and `listSorted` each take a `StopCheck`, so the user can stop a long read or a long listing between steps. Tools call bare `Files.exists` / `isDirectory` / `isSymbolicLink` predicates inline; everything that reads, writes or enumerates goes through here. It also owns every judgement a policy needs about a path — inside the root, readable, writable — and resolves the real file or folder a question about that path must name. |
| `Schemas` | static helper | Builds tool input schemas without repeating Jackson boilerplate. |

### `dev.konacode.policy`

| Element | Kind | Definition |
|---|---|---|
| `ToolPolicy` | interface | `Decision check(Action action, String userText)`. Consulted before every tool execution. The loop computes the `Action`, so a policy cannot run a tool or read the raw arguments. It gets the message the user typed, because a policy that judges the call must know why the agent acts. It also answers `label()`, the word the user types after `/policy`, and `refusal()`, the words for what it refuses when nothing can answer its question, empty when it asks nothing. One method and not two, because a boolean beside the words must be kept in step with them for ever. Both are abstract, so a new policy names itself, and `Commands` reads a policy with no `instanceof`. |
| `Decision` | sealed interface | `Allow`, `Deny(String reason)`, or `Ask(String toolName, String toolIntent, String toolOperand, Optional<Permission> standingPermission, String note)`. The note says why konacode asks, and it is empty when the question needs no reason. Sealed on purpose: a new case is a compile error at every handling site. |
| `EffectPolicy` | implements `ToolPolicy` | Allows a call inside the launch directory. Asks about every other one. It holds no state: it reads the `Action` the tool states, and it adds only the words. |
| `Judge` | interface | `Decision judge(Decision.Ask ask, String userText)`. It answers allow, the same `Ask`, a `Deny`, or the `Ask` with the note `NO_ANSWER`. An interface, because an implementation needs a model, and `agent` depends on `policy`. |
| `JudgePolicy` | implements `ToolPolicy` | Uses `EffectPolicy`, and calls the judge for an `Ask` only. **A call inside this project reaches no judge**, because `EffectPolicy` allows it and writes no `Ask`. The judge sees a read or a write outside this project, and a command. It decides nothing: it takes the answer, and for a refusal it writes the frame around the reason. The frame names one call and ends with "This answers one call and sets no rule", because the main model reads the reason of a `Deny` and a rule stops it from calling the tool again. It strips the reason, cuts it to 200 characters with the mark `Level` uses, and adds a full stop when the reason has none, so the last sentence keeps its boundary. It emits a `Judged` for every answer, with the time the judge took, because a call the judge allows runs with no question and a judgement is the cost of this policy. Both interfaces start with it: a pipe answers `NO`, so it refuses every call outside this project, and every command, that the judge does not allow. |
| `SelectedPolicy` | implements `ToolPolicy` | The policy in use now. `/policy` changes it while a session runs; `Agent` holds this one policy and never learns that the choice can change. |
| `AllowAllPolicy` | implements `ToolPolicy` | Allows every call. A user chooses it with `/policy allow-all`, in a terminal and in a pipe. `JudgePolicy` is the default now. |

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
| `TraceEvent` | sealed interface | One thing that happened. Eleven records. Each carries strings, numbers and booleans only, so this package depends on no other konacode package and both `agent` and `llm` can emit into it. `FromAgent(agent, event)` holds one other event and the name of the agent that made it, because konacode runs more than one agent and two turns share one stream. `Judged(toolName, verdict, millis, toolOperand)` holds what the judge answered about one call, and the time the judge took. The operand is last, because the model wrote it. |
| `Trace` | interface | `void emit(TraceEvent)`. `NONE` discards, `fanOut` combines. A sink never throws into the caller. |
| `NamedTrace` | implements `Trace` | Puts each event inside a `FromAgent` and passes it on. The name travels in the data, and not in a thread local, because a turn on another thread would take the wrong name and nothing would fail. |
| `Level` | enum | `OFF`, `BASIC`, `FULL`. `keep(TraceEvent)` gives back the event a level keeps, with the payloads already cut. It reaches the event inside a `FromAgent`, and a `FromAgent` goes when the event inside it goes. `BASIC` keeps a `Judged`, because a call that ran with no question is the fact a user most needs to see. The rule lives here, because each sink holds its own level. |
| `JsonlTrace` | implements `Trace` | The file sink. One JSON line for each event, in `~/.konacode/traces/`, one file for each session. A `FromAgent` writes an `agent` field beside the fields of the event inside it, so a reader can filter by agent. It sweeps the oldest files when it opens, and it flushes every line. |

### `dev.konacode.agent`

| Element | Kind | Definition |
|---|---|---|
| `Agent` | final class | `String respond(String userText)`. The loop. Depends only on interfaces. |
| `Conversation` | final class | `add(Message)`, `messages()`, `restart(List<Message>)`. The history of one session, and the only state the loop keeps. It is a class and not an interface, because `messages()` and `restart` together cover every change to the history. A caller reads all of it, transforms it, and writes all of it back. `/clear` and `/compact` both work that way. |
| `Cancellation` | final class | The user's request to stop one turn. `request` and `stopped` are public; `arm` and `disarm` are not, because only the loop may decide where an interrupt is safe. One lock keeps an interrupt from arriving after the clear. `Repl` clears it before each turn, and not `Agent`, because the stale key was pressed at the prompt and a judgement inside a turn must not erase a stop the user asked for. Implements `StopCheck`. |
| `ToolApproval` | interface | `Answer ask(Decision.Ask ask)`, `boolean canAsk()`. `Answer` is `YES`, `NO` or `ALWAYS`. The loop asks, and not the policy, because `Cancellation` lives here and only the loop knows where an interrupt is safe. |
| `Approvals` | final class | The set of permissions the user gave during this session. `covers(Action)` reads the set, and the loop calls it before the policy. `approve(Ask)` puts the question and records an `always`. Two methods, so the memory is tested in one place. Coverage is equality. The memory sits here and not in the policy, so `/policy` changes the policy and the answers stay. Nothing is written to disk. |
| `ToolSpecs` | static adapter | `Tool` to `ToolSpec`. The one place `tools` and `llm` meet. |
| `PlanTool` | implements `Tool` | Records the steps of the work, and gives the list back. It is the only tool outside `tools`, because it acts on the turn and the five tools in `tools` act on the world. konacode stores no plan: the result goes into the conversation, and konacode sends the whole conversation on each request. Two caps limit the size, 20 steps and 200 characters for one step, because that result enters the conversation again on every later iteration of the turn. It reads the whole list before it calls `TurnBudget.extend`, so a call it refuses adds no iteration. Each `Err` names one fault and the step that holds it, and no message repeats a word the model wrote. |
| `TurnBudget` | final class | The number of iterations one turn may use. `PlanTool` calls `extend()`, and the loop reads `max()`. `Agent.respond` calls `reset()` once for each turn, so only the turn that records a plan uses the larger maximum. One budget serves one agent: a second agent that shares it puts the number back in the middle of the first turn. |
| `AgentJudge` | implements `policy.Judge` | A second `Agent` with no tool, no history and one iteration. It sends one JSON object that Jackson builds, so an operand the model wrote cannot end its own field, and it reads one word back. It lives here because it needs `Agent`. |

### `dev.konacode.cli`

| Element | Kind | Definition |
|---|---|---|
| `Ui` | interface | Everything konacode shows the user, and the one thing it reads from them. It extends `Trace`, because showing what the agent did is a user interface concern, and it gains `liveTrace`, the level the screen shows. It extends `ToolApproval` for the same reason: asking a question is a user interface concern too. One object then owns the screen and the keyboard. |
| `PlainUi` | implements `Ui` | The interface for a pipe. It reads with a `BufferedReader` and prints what konacode printed before there were two interfaces. It renders no markdown and shows no spinner. It calls `Ansi.oneLine` on the name and the arguments of a call, the way the rich interface does. It shows a `Judged` at every level under the word `judged: `, the way the rich interface does, because a call the judge allowed runs with no question. It cannot ask a question, so it answers `NO` and konacode refuses the call. |
| `RichUi` | implements `Ui` | The interface for a terminal. JLine gives the line editing, the history in `~/.konacode/chat_history`, and `alt-enter` for a second line. It renders markdown, and it owns the spinner and the `EscapeWatcher`. It calls `Ansi.oneLine` on four strings the model chose — the operand, the permission, the name of a tool and the arguments of a call — and it cuts the operand line, the `always` line, the tool line and the judged line to the columns of the terminal, because a user cannot approve what they cannot read. `emit` stops the spinner before it prints a line, and restarts it once a tool finishes; the watcher keeps running, so ESC still works while a tool runs. It shows a `Judged` at every level under the word `judged: `, the way it shows a `ToolCalled` under `tool: `, because a call the judge allowed runs with no question. The word `trace: ` marks a line the level gates, and this line is not one. The constructor takes every collaborator, and `open()` builds the real ones, which is why the class can have tests. |
| `Repl` | final class | The loop. Read a line, skip it when empty, run it as a command when it starts with `/`, otherwise clear the `Cancellation` and ask the agent. Both interfaces share it. |
| `Commands` | final class | `/help`, `/tools`, `/skill`, `/trace`, `/policy`, `/clear` and `/exit`. `/policy` takes `allow-all`, `effect` or `judge`, and it selects the one `JudgePolicy` that `Main` built, because a second one would build a second judge. It holds a `JudgePolicy` and not a `ToolPolicy`, so the compiler refuses another policy in that seat. The sentence that names what a pipe refuses comes from the policy, so `Commands` holds no `instanceof`. `run` returns false when the session must end, so every command lives in one class and `Repl` gains one line. A command writes markdown, so the rich interface renders it and needs no second output method. An unknown command prints an error and never reaches the model. |
| `EscapeWatcher` | class | Reads the terminal during a turn and calls `Cancellation.request()` on the byte `0x1B`. A sibling of `Spinner`: one daemon thread, `start` and `stop`, both idempotent, not final so a test can record. Raw mode keeps `ISIG` on, so ctrl-C still ends konacode. |
| `Spinner` | class | One daemon thread that draws and erases a character while the agent works. `RichUi` stops it before every write of its own. It is not final, so a test can record the calls. |
| `Banner` | final class | The art from the README, which reads `kona`. It is 41 columns wide, so a narrower terminal gets the plain name. Generated from `README.md`, not retyped. |
| `Ansi` | final class | The escape codes, plus `strip`, `visibleLength`, `cutToColumns` and `oneLine`. A code takes bytes and no columns, so word wrap and table alignment both need `visibleLength`. It counts columns and not characters, and `cutToColumns` cuts by columns, because a fullwidth character takes two columns: a count of characters let a padded operand pass the cut in `RichUi` and wrap into a line that reads as a line konacode wrote. `oneLine` makes one line of a string the model wrote, and it lives here because every place that prints such a string needs the same guard. |
| `TraceLine` | final class | `of(TraceEvent)`. One event as one line of text. `PlainUi` and `RichUi` both call it, so the two interfaces show the same words. It calls `Ansi.oneLine` on every payload the model or the provider chose, the name of a tool included, and on no word konacode writes, because one guard here covers both interfaces. A line ends with the payload the model chose, and puts no delimiter around it: a delimiter is a character the model can write too, so an operand closed a backtick and wrote a verdict of its own. A `FromAgent` writes the agent name, then `> `, then the line of the event inside it. `inside` and `names` give an interface the event a `FromAgent` holds and the names around it, because an interface that matches one kind of event must reach through the name first. |
| `Main` | final class | Reads the environment and every `konacode.*` system property, picks the interface, wires the parts. The only place that names a concrete implementation. It builds two clients on one `HttpClient` and one codec, `kona` and `judge`, so the request, the reply and the token counts of a judgement carry their own name. It builds them inside the `try`, so a failure there closes the interface and the trace file. It writes the system prompt, four lines that name the working directory, ask for a read before an edit, and say that an `<error>` is recoverable; `MainTest` pins them, because a prompt is prompt engineering and no compiler checks it. `Level.configured` stays on `Level`, because it is a factory for its own type; a reader that returns a plain value belongs here. |

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

**A failure in the judge is not a fifth channel.** `Agent.respond` never throws, so a transport
failure reaches `AgentJudge` as `<error> …` text, which is not one of the three words. The judge
then answers with the same `Ask` and the note `NO_ANSWER`, and the user decides. A failure in the
judge must never end a turn the loop can finish.

**An `Err` the model reads is prompt text.** Treat it the way you treat a tool description. Name one
call and one path. A message that names a kind of call teaches the model a rule: the first refusal
said "to read outside this project", and the model then stopped calling the tool at all, so konacode
never asked again and the user could not say yes.

## Questions

Give each question a number. Put the number at the start of the question. The reader can then
answer with the number only.

## Comments

Do not write a comment that repeats the code. Write a comment only when a reader cannot
understand the code without it. Give the reason, not the action. A comment is one line.

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
- Do not make a verb from the name of a type. `Decision.Ask` gives "the policy writes a
  question", and the reader learns nothing. Write what each part does: `EffectPolicy` allows
  the call, so konacode asks the user nothing.

## Conventions

- Java 21. Records for data, sealed interfaces for closed sets, pattern-matching switch over them.
- Keep a statement on one line while it fits in 180 columns. Do not break the line after the
  opening bracket of a call. Break a line only when the statement does not fit.
- Extend an existing concept before you add a new one. A parallel type copies fields that must
  then be kept in step for ever. Add a new type only when the existing one would have to hold a
  field that is meaningless for most of its uses.
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
