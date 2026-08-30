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

**A coding agent in Java 21.** No framework, no orchestration library, no magic — a loop, five
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

## The five tools

| Tool | What it does | Guardrail |
|---|---|---|
| `list_files` | Snapshot of a directory | Capped at 200 entries |
| `read_file` | Return a file's contents | Capped at 100 KB |
| `edit_file` | Exact-match string replacement (creates the file when `old_str` is empty) | Refuses ambiguous matches |
| `delete_file` | Remove a file | Refuses a directory |
| `run_command` | Run a shell line in the project directory | Stopped by the timeout, default 600 seconds |

A tool is a name, a description the model reads, a JSON schema, and a function that runs when
the model asks for it. Adding a sixth means writing one class and registering it.

## Approval

konacode reads and writes inside this project with no question. For a read or a write outside this
project, and for a command, a judge decides. The judge is a second agent. It reads the name of the
tool, what the call does, what the call acts on, where the project is, and the message you typed,
and it answers allow, ask or deny. It answers ask when it is not sure, and konacode then puts the
question to you. In the rich interface the question looks like this:

```

read_file wants to read outside this project.

  /etc/passwd

  y  read it once
  n  refuse
  a  always, for read_file in /etc
```

Answer `y`, `n` or `a`. `y` runs the call once. `n` refuses it, and konacode asks again the next
time, because one answer covers one call. `a` runs it, and every later call the same tool makes in
the same folder, for the rest of this session. `esc` refuses and stops the turn. A pipe cannot ask a
question, so it refuses every call outside this project, and every command, that the judge does not
allow.

**A call inside this project reaches no judge.** konacode reads, writes and deletes a file inside
this project with no question and no judgement, in a terminal and in a pipe. The judge sees a read
or a write outside this project, and a command. Read that sentence before you trust a piped
session.

konacode prints a `judged:` line for every call the judge answered, because a call the judge allowed
runs with no question. When the judge denies a call, konacode refuses it and tells the model why.
`/policy` shows or changes what konacode asks:

```
/policy allow-all    allow every call
/policy effect       ask about every read and write outside this project, and every command
/policy judge        ask the judge, and ask you about what it does not clear
```

`judge` is the setting konacode starts with, in a terminal and in a pipe.

`run_command` asks about the command line, and `a` then covers that exact line. A line that holds
`$`, `` ` ``, `*`, `?`, `[` or `~` means something else on another day, so konacode offers `y` and
`n` only.

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

## What is new in 0.2.0

0.1.0 was the loop, three tools and two interfaces. The loop is the same. Everything 0.2.0 adds is
a control layer around it.

- **konacode asks before it acts outside this project.** A tool states what one call does, a
  policy decides, and the loop asks you. `a` remembers the answer for the session.
- **A judge answers first.** A second agent reads every question the policy writes and answers
  allow, ask or deny. A piped session is no longer wide open.
- **Two more tools**: `delete_file` and `run_command`.
- **`esc` stops a turn**, and the tools stop between two steps of their work.
- **konacode says what it did.** Nine kinds of trace event, on the screen with `/trace` and in a
  file in `~/.konacode/traces/`.
- **Skills.** `/skill` loads reusable instructions from `~/.konacode/skills/`.

[CHANGELOG.md](CHANGELOG.md) has the whole list.

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
| `KONACODE_JUDGE_MODEL` | no | the value of `KONACODE_MODEL` |
| `KONACODE_BASE_URL` | no | `https://api.openai.com/v1` |

The judge uses the same key and the same base URL. It runs on every call outside this project and
on every command, so a large main model can have a small fast judge.

Plus five system properties.

| Property | Values | Purpose |
|---|---|---|
| `konacode.maxIterations` | a whole number, default `8` | the ceiling on tool calls for one message |
| `konacode.ui` | `auto`, `plain`, `rich`, default `auto` | which interface to use |
| `konacode.trace` | `off`, `basic`, `full`, default `off` | how much the trace file records |
| `konacode.trace.maxFiles` | a whole number, default `100` | how many trace files konacode keeps |
| `konacode.command.timeoutSeconds` | a whole number, default `600` | how long one command may run |

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
├── tools/      the Tool interface, the registry, the five tools
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
- **A policy** consulted before every tool call. The default policy allows a read and a write
  inside this project, and it asks a judge about everything else. See [Approval](#approval).

Production agents add sandboxing, token budgets, rate limiting and permission prompts on top.
Same skeleton, more armor. [FOLLOWUP.md](FOLLOWUP.md) tracks what is coming.

## License

MIT.

---

*Inspired by [Nimbo](https://github.com/gscalzo/Nimbo) by Gio Scalzo, and its companion essay
[Demystifying AI Coding Agents in Swift](https://gioscalzo.com/blog/demystifying-ai-coding-agents-in-swift/).*
