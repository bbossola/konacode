```
 █████
░░███
 ░███ █████  ██████  ████████    ██████
 ░███░░███  ███░░███░░███░░███  ░░░░░███
 ░██████░  ░███ ░███ ░███ ░███   ███████
 ░███░░███ ░███ ░███ ░███ ░███  ███░░███
 ████ █████░░██████  ████ █████░░████████
░░░░ ░░░░░  ░░░░░░  ░░░░ ░░░░░  ░░░░░░░░
```

**A coding agent in Java 21.** No framework, no orchestration library, no magic — a loop, three
tools, and a language model with opinions.

## The whole trick

Watching an agent chain file reads together, spot a bug and fix it feels like watching something
think. Underneath, it is this:

1. Send the conversation — *all of it, every time* — to the model, along with descriptions of
   the tools it is allowed to call.
2. If the model answers with text, print it. Done.
3. If it answers with a tool call instead, run the tool, append the result to the conversation,
   and go back to step 1.

That is `Agent.respond()`. Nobody taught the model to list a directory before reading a file, or
to re-read a file after a failed edit. That behavior emerges from the loop and the tool
descriptions alone.

## The four tools

| Tool | What it does | Guardrail |
|---|---|---|
| `list_files` | Snapshot of a directory | Capped at 200 entries |
| `read_file` | Return a file's contents | Capped at 100 KB |
| `edit_file` | Exact-match string replacement (creates the file when `old_str` is empty) | Refuses ambiguous matches |
| `delete_file` | Remove a file | Refuses a directory |

A tool is a name, a description the model reads, a JSON schema, and a function that runs when
the model asks for it. Adding a fifth means writing one class and registering it.

## Approval

konacode asks before it reads or writes outside this project. Inside this project it asks
nothing. In the rich interface the question looks like this:

```

read_file wants to read outside this project.

  /etc/passwd

  y  read it once
  n  refuse
  a  always, for read_file in /etc
```

Answer `y`, `n` or `a`. `y` runs the call once. `n` refuses it. `a` runs it, and every later
call the same tool makes in the same folder, for the rest of this session. A pipe cannot ask a
question, so it keeps the old behaviour and allows every call. `/policy` shows or changes the
setting.

## Skills

A skill is a folder inside `~/.konacode/skills/`, with a `SKILL.md` file that names it and
describes it.

```
/skill                    list the skills
/skill commit-message     load one into the conversation
```

`/skill` lists every skill, with its description. `/skill <name>` loads one skill into the
conversation. You can load several skills at the same time, and `/clear` removes them all. The
model reads a reference file in the skill folder with `read_file`, only when it needs it.

## Run it

You will need Java 21 and an OpenAI API key.

Take the jar from the [latest release](https://github.com/bbossola/konacode/releases/latest):

```bash
OPENAI_API_KEY=sk-... java -jar konacode.jar
```

Or build it:

```bash
git clone git@github.com:bbossola/konacode.git
cd konacode
mvn package
OPENAI_API_KEY=sk-... java -jar target/konacode.jar
```

Then chat:

```
Chat with konacode (use 'ctrl-c' to quit)

You: what does this project do?
tool: list_files({"path":"."})
tool: read_file({"path":"pom.xml"})
tool: read_file({"path":"src/main/java/dev/konacode/agent/Agent.java"})
konacode: It's a Java CLI agent that answers questions by calling tools...
```

Every `tool:` line is the model deciding, on its own, that it needs more context before it can
answer.

Things worth trying:

- *"Find the TODO comments in this codebase and tell me which one looks most urgent."*
- *"Write a Java class that plays an emoji guessing game, then improve the hints."*
- *"Rename this method across the project"* — and watch it chain list → read → edit without being
  told to.

## Configuration

| Variable | Required | Default |
|---|---|---|
| `OPENAI_API_KEY` | yes | — |
| `KONACODE_MODEL` | no | `gpt-5-mini` |
| `KONACODE_BASE_URL` | no | `https://api.openai.com/v1` |

Plus four system properties.

| Property | Values | Purpose |
|---|---|---|
| `konacode.maxIterations` | a whole number, default `8` | the ceiling on tool calls for one message |
| `konacode.ui` | `auto`, `plain`, `rich`, default `auto` | which interface to use |
| `konacode.trace` | `off`, `basic`, `full`, default `off` | how much the trace file records |
| `konacode.trace.maxFiles` | a whole number, default `100` | how many trace files konacode keeps |

konacode looks for a terminal. It uses the rich interface when it finds one, and the plain
interface otherwise. A pipe therefore gets the plain interface.

The rich interface gives line editing and history, with the arrow keys, `ctrl-a`, `ctrl-e` and
`ctrl-r`. It saves the history in `~/.konacode/chat_history`. Press `alt-enter` to add a second
line, and `enter` to send. It renders markdown, and it turns a spinner while the model thinks.

Press `esc` to stop a turn. konacode stops at the next safe point, prints `Stopped.`, and gives
you the prompt back. The conversation keeps what happened, so you can then ask konacode to undo
what it did.

`ctrl-c` behaves as it always has, and `esc` did not change it: at the prompt it clears the line,
and during a turn it ends konacode. `ctrl-d` quits.

Seven commands work in both interfaces.

| Command | Action |
|---|---|
| `/help` | show the commands |
| `/tools` | show the tools the model can call |
| `/skill` | show the skills, or load one by name |
| `/policy` | show or set what konacode asks before it acts |
| `/trace` | show or set how much the screen reports |
| `/clear` | forget the conversation and start again |
| `/exit` | end the session |

Set `konacode.trace=basic` or `konacode.trace=full` and konacode writes a trace of the session
to `~/.konacode/traces/`, one JSON line for each event. The default is `off`, and konacode then
writes no file. `basic` records the loop, the times, the outcome of each turn and the token
counts. `full` adds the request and the reply, so you can replay a call. `/trace basic` shows the
same events on the screen while the session runs, whether or not the file is on.

### Running without an API key

`KONACODE_BASE_URL` points at any OpenAI-compatible endpoint, so a local model works with no
code changes. With [Ollama](https://ollama.com):

```bash
ollama pull qwen2.5-coder:32b
export KONACODE_BASE_URL=http://localhost:11434/v1
export KONACODE_MODEL=qwen2.5-coder:32b
export OPENAI_API_KEY=ollama          # required non-empty; Ollama ignores it
java -jar target/konacode.jar
```

Pick a model that is genuinely good at function calling — `qwen2.5-coder` and `qwen3-coder` both
are. Smaller general-purpose models will emit a single tool call and then fail to chain, which
is the one thing an agent needs them to do well.

Note that a Claude Pro or Max subscription cannot be used here. Those do not grant API access;
the Anthropic API is billed separately and needs its own key.

## How it is put together

```
src/main/java/dev/konacode/
├── cli/        the two interfaces, the loop, the commands
│   └── markdown/   the renderer
├── agent/      the conversation and the tool loop
├── tools/      the Tool interface, the registry, the three tools
├── policy/     what the agent is allowed to do
└── llm/        the provider interface, and one implementation of it
    └── openai/
```

Dependencies run strictly downhill: `cli → agent → {llm, tools, policy}`.
[ARCHITECTURE.md](ARCHITECTURE.md) diagrams how a turn actually runs. Three things are
interfaces with a default implementation rather than hardcoded — the tools, the LLM provider,
and the tool policy — so extending any of them is a new class rather than a rewrite. The
conversation is a plain class, because reading all of the history and writing all of it back
covers every change to it. [CLAUDE.md](CLAUDE.md) defines every element; [CONTEXT.md](CONTEXT.md) records why the
design is shaped this way.

## Safety rails

A loop where the model decides when the loop ends deserves some skepticism. konacode keeps
things honest with:

- **A ceiling on tool iterations** per user message, so a confused model cannot spin forever.
- **Output caps** on reads and listings, so one stray `target/` does not flood the context
  window — or the bill.
- **Unambiguous edits only** — `edit_file` fails loudly rather than guessing when the search
  string matches more than once.
- **A policy hook** consulted before every tool call. It currently allows everything; the seam
  is there so that stops being true without touching the loop.

Production agents add sandboxing, token budgets, rate limiting and permission prompts on top.
Same skeleton, more armor. [FOLLOWUP.md](FOLLOWUP.md) tracks what is coming.

## License

MIT.

---

*Inspired by [Nimbo](https://github.com/gscalzo/Nimbo) by Gio Scalzo, and its companion essay
[Demystifying AI Coding Agents in Swift](https://gioscalzo.com/blog/demystifying-ai-coding-agents-in-swift/).*
