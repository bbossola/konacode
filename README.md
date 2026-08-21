```
 █████                                                       █████
░░███                                                       ░░███
 ░███ █████  ██████  ████████    ██████    ██████   ██████   ░███ █████  ██████
 ░███░░███  ███░░███░░███░░███  ░░░░░███  ███░░███ ███░░███  ░███░░███  ███░░███
 ░██████░  ░███ ░███ ░███ ░███   ███████ ░███ ░░░ ░███ ░███  ░██████░  ░███████
 ░███░░███ ░███ ░███ ░███ ░███  ███░░███ ░███  ███░███ ░███  ░███░░███ ░███░░░
 ████ █████░░██████  ████ █████░░████████░░██████ ░░██████   ████ █████░░██████
░░░░ ░░░░░  ░░░░░░  ░░░░ ░░░░░  ░░░░░░░░  ░░░░░░   ░░░░░░   ░░░░ ░░░░░  ░░░░░░
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

## The three tools

| Tool | What it does | Guardrail |
|---|---|---|
| `list_files` | Snapshot of a directory | Capped at 200 entries |
| `read_file` | Return a file's contents | Capped at 100 KB |
| `edit_file` | Exact-match string replacement (creates the file when `old_str` is empty) | Refuses ambiguous matches |

A tool is a name, a description the model reads, a JSON schema, and a function that runs when
the model asks for it. Adding a fourth means writing one class and registering it.

## Run it

You will need Java 21 and an OpenAI API key.

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

Plus `-Dkonacode.maxIterations=8`, the ceiling on tool-call iterations per user message.

## How it is put together

```
src/main/java/dev/konacode/
├── cli/        the REPL: read a line, print the answer, repeat
├── agent/      conversation history + the tool loop
├── tools/      the Tool interface, the registry, the three tools
├── policy/     what the agent is allowed to do
└── llm/        the provider interface, and one implementation of it
    └── openai/
```

Dependencies run strictly downhill: `cli → agent → {llm, tools, policy}`. Four things are
interfaces with a default implementation rather than hardcoded — tools, the LLM provider,
conversation handling, and tool approval — so extending any of them is a new class rather than a
rewrite. [CLAUDE.md](CLAUDE.md) defines every element; [CONTEXT.md](CONTEXT.md) records why the
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
