# Approval — design

**Date:** 2026-08-24
**Status:** approved, pending implementation plan
**Issues:** [#15](https://github.com/bbossola/konacode/issues/15), [#16](https://github.com/bbossola/konacode/issues/16)

## Problem

`AllowAllPolicy` is the default and the only policy. The model can therefore edit any file the user
can edit, and delete any file the user can delete, with no question and no confinement. `delete_file`
shipped that way on purpose, and `CLAUDE.md` records the reason: the control layer belongs in
`ToolPolicy` and `Workspace`, and not in one tool.

This is that control layer. It is the first of three pieces.

| Piece | What it adds |
|---|---|
| **A, this design** | The approval seam. konacode asks before it reads or writes outside your project. |
| B | `run_command`, which answers `RUNS` and always asks. [#17](https://github.com/bbossola/konacode/issues/17) |
| C | A judge that answers run or ask, so the routine questions stop. |

Each piece works without the next.

## Decision

**The tool states a fact. The policy decides. The loop asks.** No part does two of those.

## The fact: `Effect`

`Tool` gains one abstract method. It is never a default, so a new tool does not compile until its
author answers.

```java
Effect effect(JsonNode args);
```

```java
public enum Effect { READS_INSIDE, READS_OUTSIDE, WRITES_INSIDE, WRITES_OUTSIDE, RUNS }
```

The tool answers per call, because the answer depends on the path in the arguments. Every tool
already holds a `Workspace`, so it resolves its own arguments and needs nothing new.

| Tool | Answer |
|---|---|
| `list_files` | `READS_INSIDE` or `READS_OUTSIDE` |
| `read_file` | `READS_INSIDE` or `READS_OUTSIDE` |
| `edit_file` | `WRITES_INSIDE` or `WRITES_OUTSIDE` |
| `delete_file` | `WRITES_INSIDE` or `WRITES_OUTSIDE` |

**A tool that cannot resolve its argument answers the `OUTSIDE` value.** A malformed path, a missing
key and a link that fails to resolve all mean the same thing: konacode does not know where this
goes, so it asks. "Unknown" needs no value of its own.

## The decision: `Decision` gains `Ask`

```java
public sealed interface Decision {
    record Allow() implements Decision {}
    record Deny(String reason) implements Decision {}
    record Ask(String action, String subject, Path alwaysFolder) implements Decision {}
}
```

`Decision` is sealed, so `Ask` is a compile error at every handling site. `CLAUDE.md` says that is
the intended behaviour and not an obstacle.

`Ask` carries what the question needs and nothing else.

- `action` — what the tool wants to do, for example `"write outside this project"`.
- `subject` — the absolute path the question is about.
- `alwaysFolder` — the folder that `always` covers. It is the parent of `subject`, with links
  resolved. It is `null` when konacode offers no `always`, and the question then shows `y` and `n`
  only.

**Piece B revisits this record.** A command has no path, so `run_command` cannot fill `subject` with
one and cannot offer a folder. B decides whether `alwaysFolder` becomes optional or whether a second
case joins the set.

## The policies

| Policy | How it decides |
|---|---|
| `AllowAllPolicy` | Always allows. Unchanged. It ignores `effect`. |
| `EffectPolicy` | The new default. |

```java
return switch (tool.effect(args)) {
    case READS_INSIDE, WRITES_INSIDE -> new Allow();
    case READS_OUTSIDE -> new Ask("read outside this project", path, path.getParent());
    case WRITES_OUTSIDE -> new Ask("write outside this project", path, path.getParent());
    case RUNS -> new Ask("run a command", tool.name(), null);
};
```

`RUNS` has no tool that answers it until piece B. `EffectPolicy` still handles the case, because the
switch is exhaustive over a closed set, and the subject is the name of the tool until B gives it a
command.

## Reading outside the project on purpose

A skill lives in `~/.konacode/skills/`, which is outside the launch directory. Without a rule, every
reference file a skill names would ask.

`Workspace` therefore holds one root and a list of folders that may be read.

```java
public Workspace(Path root, List<Path> alsoReadable)
public boolean insideRoot(Path path)     // the launch directory
public boolean readable(Path path)       // the launch directory, or a readable folder
```

`CLAUDE.md` already names `Workspace` as the place confinement hooks into. A read inside a readable
folder answers `READS_INSIDE`. A write there answers `WRITES_OUTSIDE`, because the list permits
reading and nothing else.

`Main` is the only place that names a folder, and it adds the skills folder to the list.

## The question

`Decision.Ask` reaches `Agent`, and `Agent` asks. The loop owns the question, because the loop owns
the turn and the interrupt.

```java
// in agent
public interface ToolApproval {
    enum Answer { YES, NO, ALWAYS }
    Answer ask(String toolName, Decision.Ask ask);
}

public final class Approvals {
    public boolean approve(String toolName, Decision.Ask ask);
}
```

`Ui` implements `ToolApproval`, in the way it already implements `ToolCallListener`. `Approvals`
holds the session memory, asks when it has no answer, and gives the loop a boolean. `Agent` holds
`Approvals` and consults it when a `Decision` is an `Ask`.

The memory sits outside the policy, so `/policy` changes the policy and the answers stay.

A refusal becomes a `ToolResult.Err` saying the user refused, in the way a `Deny` already does. The
model reads it and finds another way, and the turn continues.

### What the user sees

`RichUi` reads one key: `y`, `n` or `a`. It already enters raw mode for `EscapeWatcher`.

```
edit_file wants to write outside this project.

  /home/bbossola/notes/todo.md

  y  write it once
  n  refuse
  a  always, for edit_file under /home/bbossola/notes/
```

Three rules make the question honest. The path sits on its own line, unedited. The `a` line names
the tool and the folder in full, and it never says "always" alone. `esc` answers no.

### What `always` remembers

**The tool and the folder, for this session.** `Approvals` keys on the name of the tool and
`alwaysFolder`. One `a` for `read_file` under `~/.konacode/skills/git-flow/` does not open
`~/.ssh/`, and it does not cover `edit_file`.

The memory ends when konacode ends. Nothing is written to disk.

### `esc`

`esc` answers no and stops the turn. `EscapeWatcher` already calls `Cancellation.request()`, and the
loop already ends a stopped turn correctly, so the question needs no rule of its own.

## Choosing a policy

`/policy` joins the commands, and it works in the way `/trace` works.

```
/policy                    show the policy now in use, and the choices
/policy allow-all          allow every call
/policy effect             ask before a read or a write outside this project
```

`Agent` holds `private final ToolPolicy policy` and must not learn that the choice can change. One
small class solves it.

```java
public final class SelectedPolicy implements ToolPolicy {
    private volatile ToolPolicy current;
    public Decision check(Tool tool, JsonNode args) { return current.check(tool, args); }
    public void select(ToolPolicy policy);
    public ToolPolicy selected();
}
```

`Main` builds it, gives it to `Agent` as the policy, and gives the same object to `Commands`. The
field is `volatile`, because `EscapeWatcher` already proves another thread touches session state.

## The default, and the plain interface

`Main` chooses by the interface.

| Interface | Default policy |
|---|---|
| `RichUi` | `EffectPolicy` |
| `PlainUi` | `AllowAllPolicy` |

A pipe has no user to answer, so a question there would refuse every write. The behaviour of a piped
session therefore does not change. `PlainUi` still implements `ToolApproval` and answers `NO`, so a
user who types `/policy effect` into a pipe gets a safe answer rather than a lie.

## Rejected alternatives

**A list of tool names in the policy.** `AskUserPolicy` would hold `Set.of("delete_file")`. A new
dangerous tool is then unguarded until somebody remembers the list, and nothing fails. The abstract
method makes the same mistake a compile error.

**The policy asks the user.** `check` would block on a question and return `Allow` or `Deny`, and
`Decision` would need no new case. Rejected because of `esc`: `Cancellation` lives in `agent`, and
only the loop knows where an interrupt is safe. A policy that blocks inside `check` puts the wait
where nobody designed for it.

**A boolean beside `effect()`,** such as `staysInsideTheWorkspace(args)`. Two methods that the policy
must combine, and "outside" and "unknown" collapse into one answer with no place to say so.

**A record `Impact(Effect, boolean)`.** Honest about the two dimensions, and one more type for no
gain over a closed set of five.

**`Tool.paths(JsonNode)` returning a set.** The policy would then redo the confinement test that
`Workspace` already owns, and `Path` would cross a seam that does not need it.

**Keeping `READS` as one value.** konacode has no tool that transmits data today, so a read is
dangerous only when something can send the result. That argument ends when piece B lands. The split
therefore arrives before `run_command`, and not after it.

## Out of scope

`run_command` is piece B. The judge and `JudgedPolicy` are piece C. Neither appears here.

An approval written to disk. A `never` answer. A policy chosen by an environment variable or a system
property, because `/policy` covers it. Path confinement that **denies** rather than asks; konacode
asks, and the user decides.

## Tests

All offline, and a temporary folder holds every path.

- `Effect` for each of the four tools, inside and outside, and for a malformed argument.
- `Workspace` answers `insideRoot` and `readable`, including a link that leaves the root.
- `EffectPolicy` returns `Allow` or `Ask` for each of the five values.
- `Approvals` asks once, remembers `ALWAYS` for the tool and the folder, and asks again for another
  tool and for another folder.
- `Agent` turns a refusal into an `Err` and keeps the turn alive.
- `SelectedPolicy` delegates, and `/policy` changes what `Agent` sees.
- `RichUi` reads `y`, `n` and `a`, and `PlainUi` answers `NO`.

Every double is hand-written, because every type here is ours. Mockito covers JLine only.
