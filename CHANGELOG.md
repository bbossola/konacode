# Changelog

## 0.2.0 — 2026-08-30

konacode 0.1.0 was the loop, three tools and two interfaces. The loop is the same. Everything
new in 0.2.0 is a control layer around it: konacode asks before it acts outside this project, it
says what it did, and you can stop a turn.

### konacode asks before it acts

konacode reads and writes inside this project with no question. Every other call now goes
through one seam, and the seam keeps three jobs apart. A tool states a fact, a policy decides,
and the loop asks you.

- Every tool answers `computeAction`. The `Action` says what one call does, what it acts on, and
  what a standing "always" would cover. A tool decides nothing.
- `EffectPolicy` reads that fact. It allows a call inside the launch directory, and it asks about
  every other one.
- The rich interface asks with one key: `y` runs the call once, `n` refuses it, and `a` allows
  every later call the same permission covers. `esc` refuses.
- `Approvals` remembers each `a` for the session. Nothing is written to disk.
- `/policy` chooses the policy while a session runs. The answers you already gave stay.

A refusal reaches the model as an `<error>`, so the model reads a reason and tries another way.
It never reaches the model as a crash.

### A judge answers allow, ask or deny

A judge now reads every question the policy writes, and answers before you see it. The judge is a
second agent. It reads the name of the tool, what the call does, what the call acts on, where the
project is, and the message you typed. It answers `allow`, `ask` or `deny`, and it answers `ask`
when it is not sure. konacode then puts the question to you.

Both interfaces start with the judge. A piped session refuses what the judge does not allow,
where 0.1.0 allowed everything. `KONACODE_JUDGE_MODEL` gives the judge a cheaper model than the
loop. It has no tool of its own, and it starts a new conversation for each judgement.

### Two more tools

| Tool | What it does | Guardrail |
|---|---|---|
| `delete_file` | Removes one file | Refuses a directory. On a symbolic link it removes the link. |
| `run_command` | Runs one shell line with `sh -c` in the project directory | The timeout, `esc`, and the first and last 50 KB of the output |

`run_command` merges the two output streams, gives the command no standard input, and writes
`<exit N>` last. A non-zero exit code is a result and not a failure: konacode ran the command,
and the command answered.

### You can stop a turn

`esc` ends a turn in the rich interface. The tools stop between two steps of their work, so a
long read, a long listing and a long command all end. `edit_file` keeps one guarantee across a
stop: konacode applies the edit fully, or the file stays unchanged and the model reads why.

### konacode says what it did

A trace reports nine kinds of event: the turn, each iteration, each tool call and its result, the
request, the reply, the token counts, a retry, and a judgement.

- `/trace off|basic|full` sets what the screen shows.
- `-Dkonacode.trace` sets what the file keeps. konacode writes one JSON line for each event, in
  `~/.konacode/traces/`, one file for each session, and it keeps the newest 100 files.
- A line ends with the payload the model or the provider chose, and puts no delimiter around it.
  A payload with nothing after it cannot forge a line the reader trusts.

### Skills

A skill is a folder in `~/.konacode/skills/`, with a `SKILL.md` file that names it and describes
it. `/skill` lists the skills, and `/skill <name>` loads one into the conversation. You can load
several, and `/clear` removes them all.

### Smaller changes

- The system prompt states three facts: the working directory, that the model must read a file
  before it edits the file, and that an `<error>` is a failed tool call it can recover from.
- A transient transport failure buys three attempts, with a wait of 500 ms and then 1 s.
  konacode retries `429`, `502`, `503`, `504` and a request that did not arrive, and nothing
  else. The model cannot fix a 401.
- Every place that prints a string the model chose calls `Ansi.oneLine` first, and cuts the line
  to the width of the terminal. A user cannot approve what they cannot read.
- `read_file` decodes with replacement, so a cap that lands inside a character is not reported as
  a binary file.
- Commands: `/help`, `/tools`, `/skill`, `/trace`, `/policy`, `/clear` and `/exit`. 0.1.0 had
  three.

### Numbers

| | 0.1.0 | 0.2.0 |
|---|---|---|
| Tools | 3 | 5 |
| Commands | 3 | 7 |
| Tests, all offline | 184 | 641 |

## 0.1.0 — 2026-08-21

The first release. The loop, three tools (`list_files`, `read_file`, `edit_file`), two
interfaces, and three commands. See
[the release](https://github.com/bbossola/konacode/releases/tag/v0.1.0).
