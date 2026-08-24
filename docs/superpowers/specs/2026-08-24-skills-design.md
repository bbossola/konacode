# Skills — design

**Date:** 2026-08-24
**Status:** approved, pending implementation plan
**Issue:** [#25](https://github.com/bbossola/konacode/issues/25)
**Plan:** [The skills plan](../plans/2026-08-24-skills.md)

## Problem

A user repeats the same instructions. "Write the commit message this way." "Review a change against
this list." Today the only place for those instructions is the system prompt, which is a
compile-time constant in `Main`, or the line the user types every time.

A skill holds those instructions in a file. The user loads the file when the task needs it, and the
instructions leave the conversation when the session restarts.

## Decision

**The user chooses the skill.** konacode adds a `/skill` command. The model does not discover a
skill, and it does not load one.

The model-chooses design stays possible, and "Rejected alternatives" records what it would cost.

## The command

`/skill` joins `/help`, `/tools`, `/clear` and `/exit` in `Commands`.

### `/skill` with no name

It prints one line for each skill: the name and the description. `/tools` prints its list the same
way. konacode reads the header of each `SKILL.md` and keeps no body, because `Skill` holds the
header and the folder.

`all()` skips a folder that it cannot read. The list stays short and correct. The user who asks for
that skill by name then reads the error, because `lookup` throws `SkillException`.

### `/skill <name>`

It appends two messages to the conversation, and it makes no provider call.

1. A `UserMessage`. It names the skill, it gives the absolute path of the folder, and it carries the
   body of `SKILL.md`.
2. An `AssistantMessage` that konacode writes: "The skill `<name>` is loaded."

A second `/skill` with the same name appends a second pair. konacode keeps no record of what is
loaded, so it makes no check. The cost is one repeated body, and the model reads the same
instructions twice.

The text of the first message is prompt text, so it is part of this design:

```
The skill `commit-message` is now active. Its folder is
/home/bbossola/.konacode/skills/commit-message. A path in the text below is relative
to that folder. Use read_file to read it.

<the body of SKILL.md>
```

### Errors

Each one prints through `ui.showError`, and changes no message in the conversation.

| Error | Message |
|---|---|
| The name matches no folder | Unknown skill, and the list of known names |
| The folder holds no `SKILL.md` | The path that konacode looked for |
| The front matter has no `name` or no `description` | The file, and the missing key |

## Why konacode writes the second message

`/skill` appends a `UserMessage` and then stops. The next line from the user makes `Agent.respond`
append a second `UserMessage`. The history then holds two user turns in a row, and a provider that
enforces strict alternation rejects it. The Anthropic Messages API enforces it.

`Agent.fail` and `Agent.closeStoppedTurn` already write an `AssistantMessage` for this reason. Both
speak in the voice of konacode, and neither pretends to be the model. `/skill` does the same.

The other option was one real round trip. It was rejected, and the reason is in "Rejected
alternatives".

## Additive, and cleared

Each `/skill` appends one more pair. Two skills give two pairs, in the order the user loaded them.
The model reads all of them, because konacode sends the whole conversation every turn.

`/clear` removes every skill. It calls `conversation.restart(List.of(systemMessage))`, which keeps
the system message and nothing else. A skill in the `SystemMessage` would survive `/clear`, and that
is why the body is a `UserMessage`.

## The format

konacode reads the Claude Code format. A folder for each skill, and a `SKILL.md` inside it.

```markdown
---
name: commit-message
description: Use when writing a commit message.
---

Write the subject in the imperative...
```

The reason is reuse. The format already holds many skills on the machine, and a format nobody else
writes gives the user an empty folder.

The front matter is YAML, and konacode has no YAML parser. `jackson-databind` is the only Jackson
module, and a new dependency is a conversation. A real skill file uses two keys with single-line
values, so `FrontMatter` reads them without YAML. It is not a YAML parser, and it reports an error
for a value it cannot read.

**Location:** `~/.konacode/skills/`, beside the existing `~/.konacode/chat_history`. konacode does
not read `~/.claude/`, because that would tie it to another product.

## Sub-documents

A skill body names a reference file, and the model reads it with `read_file`. konacode adds nothing
for this. The body loads always. A reference file loads only when the model needs it, which is the
reason the format is worth copying.

`Workspace` resolves an absolute path today, and `read_file` caps a file at 100 KB.

### Consequence for issue #15

[#15](https://github.com/bbossola/konacode/issues/15) confines every path to the launch directory,
and `~/.konacode/skills/` sits outside it. So `WorkspaceConfinedPolicy` must also allow the skills
folder, and allow it for reading only. `edit_file` and `delete_file` must not reach a skill.

Add this to #15 when this design is approved.

## The loader

A new package, `dev.konacode.skills`. The dependency runs `cli -> skills -> tools`, which is
downhill and adds no cycle.

| Type | Kind | Definition |
|---|---|---|
| `Skill` | record `(String name, String description, Path folder)` | The front matter and the folder. It holds no body, so a list costs no read. |
| `SkillRegistry` | final class | `all()` and `lookup(String)`, which is the shape of `ToolRegistry`. `body(Skill)` reads `SKILL.md` through a `Workspace` rooted at the skills folder, capped at 100 KB, with a `StopCheck`. |
| `FrontMatter` | record, package-private, pure | The two keys of the header, and the body below them. It reads the lines between the two `---` markers, and splits each one at the first `:`. It touches no filesystem. |
| `SkillException` | RuntimeException | konacode found the folder and could not read the skill. The user fixes the file, and the message never reaches the model. |

`SkillRegistry` reads the folder on each call. A new skill therefore appears without a restart, and
a missing folder gives an empty list instead of a failure at startup.

`Main` builds the `SkillRegistry` and gives it to `Commands`, in the way it gives `ToolRegistry`.

A future tool that lets the model choose a skill implements `Tool`, and it lives in
`dev.konacode.skills` beside the loader. `ToolRegistry.of` takes any `Tool`, so no cycle appears.

## Rejected alternatives

**The model chooses.** A `load_skill` tool. Its description lists the name and the description of
every skill, and `ToolSpecs` sends that text on every turn. The schema puts the known names in an
`enum`. The body returns as a `ToolResult.Ok`, because the loop has no other place for it: a `Tool`
holds no `Conversation`, since `tools` must not depend on `llm`. Rejected for the first version. The
loader is shared, so this stays cheap to add.

**Append the body to the `SystemMessage`.** The skill would survive `/clear`, and the user asked for
the opposite.

**A new `Message` case.** It changes the sealed hierarchy, the codec and every future provider.

**One real round trip on `/skill`.** The model would write its own acknowledgement. It costs a
request that resends the whole history, it makes `/skill` fail on a transport error, and it makes
`Commands` hold the `Agent` and join the stop. konacode sends the whole conversation every turn, so
the round trip adds no comprehension.

**A `read_skill_file` tool.** A fifth tool that does what `read_file` does. It would save one line
in #15, and cost a class and a tool description for ever.

**Read every file in the skill folder at load.** It removes progressive disclosure, and a large
skill fills the context on load.

## Out of scope

A skill that carries a script. A skill that registers a tool. A project folder for skills, next to
the user folder. A `/skills` command that removes one loaded skill.

`/compact` in [#18](https://github.com/bbossola/konacode/issues/18) can summarise a skill away. The
rule belongs in #18: keep the skill messages, and summarise the rest.

## Tests

All offline, and a temporary folder holds the skills.

- `FrontMatter` reads two keys, reports a missing key, and reports a value it cannot read.
- `SkillRegistry` lists two skills, finds one by name, returns empty for an unknown name, and
  returns an empty list for a missing folder.
- `Commands` appends the pair, prints the list, and prints each of the three errors.
- One test proves that `/clear` removes a loaded skill.

Every double is hand-written, because every type here is ours.
