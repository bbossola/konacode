# DeleteFile — design

**Date:** 2026-08-24
**Status:** approved, pending implementation plan

## Problem

konacode can create a file and it cannot remove one. `EditFile` creates a file when `old_str` is
empty, so the agent makes files. No tool deletes one.

This became visible in the interrupt design. See
[the interrupt design](2026-08-23-interrupt-design.md). A stopped turn keeps its record, so the
model can read what it did and write the reverse edit when the user says "undo that". The reverse
of a create is a delete, and the model has no way to write it.

## Decision

Add one tool. `delete_file` removes one file.

The tool reaches any path that the other tools reach. It does not confine the path, it does not
ask for confirmation, and it keeps no copy. A wrong `delete_file` is final.

That is a deliberate choice, and the reason is consistency. `ReadFile` and `EditFile` already
accept an absolute path and a `~` path. `Workspace` has no confinement, and `Decision` has no
`Ask`. A control layer is planned, and it belongs in those two places rather than in one tool.
See "The risk, stated plainly".

## The tool

| Element | Value |
|---|---|
| Name | `delete_file` |
| Schema | one required string, `path` |
| `stopsOnInterrupt()` | `false` |
| `StopCheck` | none |

One `Files.delete` is one uninterruptible step, so neither stop mechanism from the interrupt
design applies here.

### Description

The description is prompt text, not a comment. With no confinement and no confirmation, it is the
only thing that shapes what the model does:

```
Delete the file at a given relative path. Use this to remove a file that is no
longer wanted, for example one you created by mistake. The delete cannot be
undone. Do not use this with a directory.
```

It steers rather than forbids. A user who asks konacode to remove a file must still get the file
removed.

### Behaviour

| Case | Result |
|---|---|
| `path` missing, blank or not a string | `Err` "Invalid arguments for delete_file. Expected: {\"path\": \"...\"}" |
| The path does not exist | `Err` "Path not found: …" |
| The path is a directory | `Err` "Path is a directory, not a file: …" |
| The path is a symbolic link | Deletes the link. The target is untouched. |
| `Files.delete` throws | `Err` carrying the message |
| Otherwise | `Ok` "deleted file <path>" |

The error texts follow `ReadFile`, which reports "Path not found" and "Path is a directory, not a
file" with the resolved absolute path. The success text follows `EditFile`, which writes
"created file <path>" with the path as the model wrote it.

The directory test uses `NOFOLLOW_LINKS`. A link to a directory is therefore deleted as a link,
and is not refused. `Files.delete` on a symbolic link removes the link and never the target.
`ListFiles` already separates the two ideas, with `/` for a directory and `@` for a link.

A directory is refused because a recursive delete is a different tool, and a far more dangerous
one. It is not planned.

## `Workspace` gains one method

```java
public void delete(Path file) throws IOException {
    Files.delete(file);
}
```

It is a thin wrapper, and it earns its place for the reason CLAUDE.md gives: `Workspace` owns
every filesystem operation, and it is the single place where path confinement will hook in. The
control layer lands here and in `ToolPolicy`, not in the tool.

## Wiring

| Class | Change |
|---|---|
| `DeleteFile` | New. Implements `Tool`. Takes a `Workspace`. |
| `Workspace` | Gains `delete`. |
| `Main` | Registers `new DeleteFile(workspace)`. |
| `Commands` | No change. `/tools` enumerates the registry. |

## Testing

Test first, and offline. `DeleteFileTest` uses a temporary directory, as `ReadFileTest` and
`EditFileTest` do.

| Test | What it proves |
|---|---|
| Deletes a file | The result is `Ok`, and the file is gone. |
| A missing path | `Err` "Path not found". |
| A directory | `Err` "Path is a directory". The directory is still there. |
| Bad arguments | `Err` naming the expected shape. Nothing is deleted. |
| A symbolic link | The link is gone, and the target file still exists. |

## The risk, stated plainly

A wrong `EditFile` leaves the old text in the conversation, so the model can put it back. A wrong
`DeleteFile` leaves nothing. The model can delete any file that the user can delete.

Two pieces of work close this, and both are outside this design:

- **Path confinement in `Workspace`.** It refuses a path outside the root. Every tool gains it at
  once, because every tool resolves through `Workspace`.
- **`Decision.Ask` in `ToolPolicy`.** The human confirms before the tool runs. `Decision` is sealed
  for exactly this reason: adding `Ask` becomes a compile error at every handling site.

Until then, the description is the only guard, and it is a soft one.

## Rejected alternatives

**A trash directory.** The file moves to `~/.konacode/trash` and is never unlinked, so a mistake is
recoverable. It leaves litter that nothing cleans up, and a tool named "delete" that does not
delete misleads the model about what it did.

**Deleting only a file that the agent created.** konacode would have to remember which files those
are. The Conversation is the only state the loop keeps, and a set of paths beside it breaks that
invariant. The model already knows what it created, because the record is in the history.

**A recursive delete.** A different tool, and a far more dangerous one. Not planned.
