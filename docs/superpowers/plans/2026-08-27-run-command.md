# run_command Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the model a `run_command` tool, and change the approval seam so a command can carry its own kind of standing permission.

**Architecture:** A tool states one `Action` record, which holds the effect, the operand for the screen, and an optional `Permission`. `EffectPolicy` trades the effect for a sentence and copies the rest into `Decision.Ask`. `Approvals` holds a `Set<Permission>` and compares by equality. `RunCommand` runs one shell line with `sh -c`, and it offers a permission only when the line cannot expand.

**Tech Stack:** Java 21, Jackson, JUnit 5, `java.lang.ProcessBuilder`. No new dependency.

**Design:** [docs/superpowers/specs/2026-08-27-run-command-design.md](../specs/2026-08-27-run-command-design.md)

**Worktree:** `.worktree/feat-run-command`, branch `feat/run-command`.

**Build command:** `mvn -q test` after `sdk use java 21.0.2-open`. A single test runs with
`mvn -q test -Dtest=ClassName#methodName`.

---

## File Structure

**Part 1 — the seam**

| File | Responsibility | Task |
|---|---|---|
| `src/main/java/dev/konacode/tools/Permission.java` | New. A standing decision, as a value. | 1 |
| `src/main/java/dev/konacode/tools/Action.java` | New. What one call does, as the tool states it. | 2 |
| `src/main/java/dev/konacode/tools/Actions.java` | New, package-private. Builds the `Action` of a tool that acts on one path. | 3 |
| `src/main/java/dev/konacode/tools/Tool.java` | `effect` becomes `computeAction`. | 3 |
| `src/main/java/dev/konacode/tools/{ListFiles,ReadFile,EditFile,DeleteFile}.java` | Each answers `computeAction`. | 3 |
| `src/main/java/dev/konacode/policy/Decision.java` | `Ask` changes shape. | 4 |
| `src/main/java/dev/konacode/policy/EffectPolicy.java` | Becomes stateless. | 4 |
| `src/main/java/dev/konacode/agent/ToolApproval.java` | `ask` takes the `Ask` only. | 4 |
| `src/main/java/dev/konacode/agent/Approvals.java` | Holds a `Set<Permission>`. | 4 |
| `src/main/java/dev/konacode/agent/Agent.java` | Calls `approve(ask)`, and names the operand in the refusal. | 4 |
| `src/main/java/dev/konacode/cli/{RichUi,PlainUi}.java` | Draw the question from the new `Ask`. | 4 |
| `src/main/java/dev/konacode/cli/Main.java` | `new EffectPolicy()` takes no workspace. | 4 |

**Part 2 — the tool**

| File | Responsibility | Task |
|---|---|---|
| `src/main/java/dev/konacode/tools/CappedOutput.java` | New, package-private. Keeps the first part and the last part of a stream. | 5 |
| `src/main/java/dev/konacode/tools/RunCommand.java` | New. The tool: name, description, schema, `computeAction`. | 6 |
| `src/main/java/dev/konacode/tools/RunCommand.java` | The same file: `execute`. | 7 |
| `src/main/java/dev/konacode/tools/RunCommand.java` | The same file: the timeout and the stop. | 8 |
| `src/main/java/dev/konacode/cli/Main.java` | Registers `run_command`. | 9 |
| `CLAUDE.md`, `README.md`, `ARCHITECTURE.md` | Record the new types and the new property. | 9 |

**Test files**

`PermissionTest`, `ActionTest`, `ActionsTest`, `CappedOutputTest`, `RunCommandTest` are new. Every
other test file in the table above already exists, and the task says which tests to change.

---

## Task 1: `Permission`

A permission is a value the user gave. konacode compares two permissions and never examines one.

**Files:**
- Create: `src/main/java/dev/konacode/tools/Permission.java`
- Test: `src/test/java/dev/konacode/tools/PermissionTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/dev/konacode/tools/PermissionTest.java`:

```java
package dev.konacode.tools;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class PermissionTest {

    @Test
    void twoPermissionsForOneFolderAreEqual() {
        assertEquals(new Permission.InFolder("read_file", Path.of("/etc")),
                new Permission.InFolder("read_file", Path.of("/etc")));
    }

    @Test
    void twoSpellingsOfOneFolderAreOnePermission() {
        assertEquals(new Permission.InFolder("read_file", Path.of("/etc")),
                new Permission.InFolder("read_file", Path.of("/etc/ssl/..")));
    }

    @Test
    void anotherToolIsAnotherPermission() {
        assertNotEquals(new Permission.InFolder("read_file", Path.of("/etc")),
                new Permission.InFolder("delete_file", Path.of("/etc")));
    }

    @Test
    void anotherFolderIsAnotherPermission() {
        assertNotEquals(new Permission.InFolder("read_file", Path.of("/etc")),
                new Permission.InFolder("read_file", Path.of("/var")));
    }

    @Test
    void aFolderNeverEqualsACommand() {
        assertNotEquals(new Permission.InFolder("run_command", Path.of("/etc")),
                new Permission.ExactCommand("run_command", "/etc"));
    }

    @Test
    void twoSpellingsOfOneCommandAreTwoPermissions() {
        assertNotEquals(new Permission.ExactCommand("run_command", "mvn test"),
                new Permission.ExactCommand("run_command", "mvn  test"));
    }

    @Test
    void aFolderPermissionNamesTheToolAndTheFolder() {
        assertEquals("read_file in /etc",
                new Permission.InFolder("read_file", Path.of("/etc")).inWords());
    }

    @Test
    void aCommandPermissionNamesTheToolAndTheLine() {
        assertEquals("run_command exactly: mvn test",
                new Permission.ExactCommand("run_command", "mvn test").inWords());
    }
}
```

- [ ] **Step 2: Run the test and see it fail**

Run: `mvn -q test -Dtest=PermissionTest`
Expected: the build fails, because `Permission` does not exist.

- [ ] **Step 3: Write the implementation**

Create `src/main/java/dev/konacode/tools/Permission.java`:

```java
package dev.konacode.tools;

import java.nio.file.Path;
import java.util.Objects;

/**
 * A standing decision the user gave during this session.
 *
 * <p>konacode compares two permissions for equality, and it never examines one. A record gives
 * that equality. Two kinds are never equal, so a permission for a folder can never cover a
 * command. The interface is sealed, so a third kind is a compile error at {@link #inWords()} and
 * at no other place.
 */
public sealed interface Permission {

    /** The words the question shows on the "always" line. */
    String inWords();

    /** Every call the named tool makes on a path directly in the named folder. */
    record InFolder(String toolName, Path folder) implements Permission {

        public InFolder {
            Objects.requireNonNull(toolName, "toolName");
            Objects.requireNonNull(folder, "folder");
            // Path.equals compares the spelling, so two spellings of one folder must be one value.
            folder = folder.normalize();
        }

        @Override
        public String inWords() {
            return toolName + " in " + folder;
        }
    }

    /** One command line, character for character. */
    record ExactCommand(String toolName, String command) implements Permission {

        public ExactCommand {
            Objects.requireNonNull(toolName, "toolName");
            Objects.requireNonNull(command, "command");
        }

        @Override
        public String inWords() {
            return toolName + " exactly: " + command;
        }
    }
}
```

- [ ] **Step 4: Run the test and see it pass**

Run: `mvn -q test -Dtest=PermissionTest`
Expected: PASS.

- [ ] **Step 5: Prove the normalize line is tested**

Delete `folder = folder.normalize();` and run `mvn clean test -Dtest=PermissionTest`.
Expected: `twoSpellingsOfOneFolderAreOnePermission` fails. Put the line back.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/dev/konacode/tools/Permission.java src/test/java/dev/konacode/tools/PermissionTest.java
git commit -m "feat: add Permission, a standing decision as a value"
```

---

## Task 2: `Action`

**Files:**
- Create: `src/main/java/dev/konacode/tools/Action.java`
- Test: `src/test/java/dev/konacode/tools/ActionTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/dev/konacode/tools/ActionTest.java`:

```java
package dev.konacode.tools;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionTest {

    @Test
    void anActionWithNoPermissionOffersNothingStanding() {
        Action action = Action.once(Effect.RUNS, "rm *.log");

        assertEquals(Effect.RUNS, action.effect());
        assertEquals("rm *.log", action.operand());
        assertTrue(action.permission().isEmpty());
    }

    @Test
    void anActionCarriesThePermissionItWasGiven() {
        Permission permission = new Permission.InFolder("read_file", Path.of("/etc"));

        Action action = Action.of(Effect.READS_OUTSIDE, "/etc/hosts", permission);

        assertEquals(Optional.of(permission), action.permission());
    }

    @Test
    void anActionRefusesANullPermission() {
        assertThrows(NullPointerException.class,
                () -> new Action(Effect.RUNS, "ls", null));
    }

    @Test
    void anActionRefusesANullOperand() {
        assertThrows(NullPointerException.class,
                () -> Action.once(Effect.RUNS, null));
    }
}
```

- [ ] **Step 2: Run the test and see it fail**

Run: `mvn -q test -Dtest=ActionTest`
Expected: the build fails, because `Action` does not exist.

- [ ] **Step 3: Write the implementation**

Create `src/main/java/dev/konacode/tools/Action.java`:

```java
package dev.konacode.tools;

import java.util.Objects;
import java.util.Optional;

/**
 * What one call to a tool does, as the tool states it. The tool decides nothing; a
 * {@code ToolPolicy} reads this and decides.
 *
 * @param effect what the call does
 * @param operand what the call acts on, in words, for the screen. A path for a file tool, and the
 *     command line for a tool that runs a command.
 * @param permission what a standing "always" would cover. Empty means konacode offers no
 *     "always" for this call, because no standing permission can describe it honestly.
 */
public record Action(Effect effect, String operand, Optional<Permission> permission) {

    public Action {
        Objects.requireNonNull(effect, "effect");
        Objects.requireNonNull(operand, "operand");
        Objects.requireNonNull(permission, "permission");
    }

    /** A call the user may approve once only. */
    public static Action once(Effect effect, String operand) {
        return new Action(effect, operand, Optional.empty());
    }

    public static Action of(Effect effect, String operand, Permission permission) {
        return new Action(effect, operand, Optional.of(permission));
    }
}
```

- [ ] **Step 4: Run the test and see it pass**

Run: `mvn -q test -Dtest=ActionTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/konacode/tools/Action.java src/test/java/dev/konacode/tools/ActionTest.java
git commit -m "feat: add Action, the fact a tool states about one call"
```

---

## Task 3: `Tool.computeAction` replaces `Tool.effect`

The four file tools answer the new method. `EffectPolicy` reads `action.effect()` and keeps its
old question. The shape of the question changes in Task 4, and not here.

**Files:**
- Create: `src/main/java/dev/konacode/tools/Actions.java`
- Modify: `src/main/java/dev/konacode/tools/Tool.java`
- Modify: `src/main/java/dev/konacode/tools/ListFiles.java:84-95`
- Modify: `src/main/java/dev/konacode/tools/ReadFile.java:82-87`
- Modify: `src/main/java/dev/konacode/tools/EditFile.java:95-100`
- Modify: `src/main/java/dev/konacode/tools/DeleteFile.java:82-87`
- Modify: `src/main/java/dev/konacode/policy/EffectPolicy.java`
- Create: `src/test/java/dev/konacode/tools/ActionsTest.java`
- Modify: `src/test/java/dev/konacode/tools/EffectTest.java`
- Modify: `src/test/java/dev/konacode/policy/EffectPolicyTest.java:39-69` (the `Running` double)

- [ ] **Step 1: Write the failing test**

Create `src/test/java/dev/konacode/tools/ActionsTest.java`:

```java
package dev.konacode.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionsTest {

    @TempDir
    Path root;

    /** A second temporary folder. Never use {@code root.getParent()}: that is shared. */
    @TempDir
    Path outside;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ObjectNode path(String value) {
        ObjectNode args = MAPPER.createObjectNode();
        args.put("path", value);
        return args;
    }

    private Action read(ObjectNode args) {
        Workspace workspace = new Workspace(root);
        return Actions.onPath("read_file", workspace, args.path("path"),
                Effect.READS_INSIDE, Effect.READS_OUTSIDE,
                workspace::readable, workspace::readTarget);
    }

    @Test
    void aPathInsideTheRootReadsInside() {
        Action action = read(path("notes.txt"));

        assertEquals(Effect.READS_INSIDE, action.effect());
        assertEquals(root.resolve("notes.txt").toString(), action.operand());
        assertTrue(action.permission().isEmpty(), "a call inside is never asked about");
    }

    @Test
    void aPathOutsideTheRootOffersItsRealFolder() throws IOException {
        Path file = outside.toRealPath().resolve("secret.txt");

        Action action = read(path(file.toString()));

        assertEquals(Effect.READS_OUTSIDE, action.effect());
        assertEquals(file.toString(), action.operand());
        assertEquals(new Permission.InFolder("read_file", outside.toRealPath()),
                action.permission().orElseThrow());
    }

    @Test
    void aLinkInsideTheProjectOffersTheFolderItReaches() throws IOException {
        Path secret = Files.writeString(outside.resolve("secret.txt"), "x");
        Path link = Files.createSymbolicLink(root.resolve("a.txt"), secret);

        try {
            Action action = read(path(link.toString()));

            assertEquals(secret.toRealPath().toString(), action.operand());
            assertEquals(new Permission.InFolder("read_file", outside.toRealPath()),
                    action.permission().orElseThrow());
        } finally {
            Files.delete(link);
        }
    }

    @Test
    void aBrokenLinkOffersNoPermission() throws IOException {
        Path link = Files.createSymbolicLink(root.resolve("dangling"), outside.resolve("gone"));

        try {
            Action action = read(path(link.toString()));

            assertEquals(Effect.READS_OUTSIDE, action.effect());
            assertTrue(action.permission().isEmpty(), "a call that reaches nothing offers none");
        } finally {
            Files.delete(link);
        }
    }

    @Test
    void aPathThatIsNotTextNamesTheTool() {
        ObjectNode args = MAPPER.createObjectNode();
        args.put("path", 123);

        Action action = read(args);

        assertEquals(Effect.READS_OUTSIDE, action.effect());
        assertEquals("read_file", action.operand());
        assertTrue(action.permission().isEmpty());
    }
}
```

- [ ] **Step 2: Run the test and see it fail**

Run: `mvn -q test -Dtest=ActionsTest`
Expected: the build fails, because `Actions` does not exist.

- [ ] **Step 3: Write `Actions`**

Create `src/main/java/dev/konacode/tools/Actions.java`:

```java
package dev.konacode.tools;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Builds the {@link Action} of a tool that acts on one path.
 *
 * <p>Four tools share one rule, and the rule is subtle: a read is judged at the file the link
 * reaches, and a write is judged at the entry the write replaces. One copy of the rule keeps the
 * four tools in agreement.
 */
final class Actions {

    private Actions() {
    }

    /**
     * @param toolName the tool that asks
     * @param pathNode the {@code path} argument, which may be absent or not text
     * @param insideEffect the effect when the path stays inside the workspace
     * @param outsideEffect the effect when it does not
     * @param staysInside {@code Workspace::readable} for a read, {@code Workspace::writable} for
     *     a write
     * @param reaches {@code Workspace::readTarget} for a read, {@code Workspace::writeTarget} for
     *     a write
     */
    static Action onPath(String toolName,
                         Workspace workspace,
                         JsonNode pathNode,
                         Effect insideEffect,
                         Effect outsideEffect,
                         Predicate<Path> staysInside,
                         Function<Path, Optional<Path>> reaches) {
        Optional<Path> resolved = workspace.tryResolve(pathNode);
        if (resolved.isEmpty()) {
            return Action.once(outsideEffect,
                    pathNode.isTextual() ? pathNode.asText() : toolName);
        }
        Path path = resolved.get();
        if (staysInside.test(path)) {
            return Action.once(insideEffect, path.toString());
        }
        Optional<Path> target = reaches.apply(path);
        if (target.isEmpty()) {
            return Action.once(outsideEffect, path.toString());
        }
        Path reached = target.get();
        return workspace.folderOf(reached)
                .<Action>map(folder -> Action.of(outsideEffect, reached.toString(),
                        new Permission.InFolder(toolName, folder)))
                .orElseGet(() -> Action.once(outsideEffect, reached.toString()));
    }
}
```

- [ ] **Step 4: Run the test and see it pass**

Run: `mvn -q test -Dtest=ActionsTest`
Expected: PASS.

- [ ] **Step 5: Change `Tool`**

In `src/main/java/dev/konacode/tools/Tool.java`, replace the `effect` method and its javadoc with:

```java
    /**
     * What this call does. Abstract and never a default, so a new tool must answer it, the way
     * {@link #stopsOnInterrupt()} already does.
     *
     * <p>The answer must name the place that {@link #execute} will touch. A tool that cannot name
     * that place answers the {@code OUTSIDE} value of its kind, and konacode then asks the user.
     *
     * <p>A tool that gives no permission says that no standing "always" can describe this call.
     */
    Action computeAction(JsonNode args);
```

- [ ] **Step 6: Change the four tools**

`ListFiles`, replacing lines 84-95:

```java
    @Override
    public Action computeAction(JsonNode args) {
        JsonNode pathNode = args.path("path");
        if (!pathNode.isTextual() || pathNode.asText().isBlank()) {
            // No path means the root, and a root is inside itself. execute() reaches the same
            // place through resolve("."), so the two agree while a Workspace has one root.
            return Action.once(Effect.READS_INSIDE, workspace.root().toString());
        }
        return Actions.onPath(name(), workspace, pathNode,
                Effect.READS_INSIDE, Effect.READS_OUTSIDE,
                workspace::readable, workspace::readTarget);
    }
```

`ReadFile`, replacing lines 82-87:

```java
    @Override
    public Action computeAction(JsonNode args) {
        return Actions.onPath(name(), workspace, args.path("path"),
                Effect.READS_INSIDE, Effect.READS_OUTSIDE,
                workspace::readable, workspace::readTarget);
    }
```

`EditFile`, replacing lines 95-100:

```java
    @Override
    public Action computeAction(JsonNode args) {
        // An edit reads the file and then writes it. Both tests must pass, or a link that
        // points into the project would give one call disclosure of any file on the disk.
        return Actions.onPath(name(), workspace, args.path("path"),
                Effect.WRITES_INSIDE, Effect.WRITES_OUTSIDE,
                path -> workspace.writable(path) && workspace.readable(path),
                workspace::writeTarget);
    }
```

`DeleteFile`, replacing lines 82-87:

```java
    @Override
    public Action computeAction(JsonNode args) {
        return Actions.onPath(name(), workspace, args.path("path"),
                Effect.WRITES_INSIDE, Effect.WRITES_OUTSIDE,
                workspace::writable, workspace::writeTarget);
    }
```

Each file needs no new import beyond `Action` and `Effect`, which are in the same package.
Remove an import of `java.util.Optional` or `java.nio.file.Path` only when the compiler reports
it as unused.

- [ ] **Step 7: Change `EffectPolicy` to read the action**

In `src/main/java/dev/konacode/policy/EffectPolicy.java`, change the first line of `check`:

```java
    @Override
    public Decision check(Tool tool, JsonNode args) {
        return switch (tool.computeAction(args).effect()) {
            case READS_INSIDE, WRITES_INSIDE -> Decision.allow();
            case READS_OUTSIDE -> ask("read outside this project", tool, args, workspace::readTarget);
            case WRITES_OUTSIDE -> ask("write outside this project", tool, args, workspace::writeTarget);
            case RUNS -> Decision.ask("run a command", tool.name(), null);
        };
    }
```

Nothing else in the class changes in this task.

- [ ] **Step 8: Change the tests that call `effect`**

In `src/test/java/dev/konacode/tools/EffectTest.java`, change every call of `tool.effect(args)`
to `tool.computeAction(args).effect()`. The assertions do not change.

In `src/test/java/dev/konacode/policy/EffectPolicyTest.java`, the `Running` double at lines 39-69
must answer the new method:

```java
        @Override
        public Action computeAction(JsonNode args) {
            return Action.once(Effect.RUNS, "run_command");
        }
```

Add `import dev.konacode.tools.Action;`.

- [ ] **Step 9: Run the whole suite**

Run: `mvn -q test`
Expected: PASS. Every test that passed before passes now, because the question did not change.

- [ ] **Step 10: Prove the read rule and the write rule differ**

In `EditFile.computeAction`, change the predicate to `workspace::writable` and run
`mvn clean test -Dtest=EffectTest`.
Expected: `editReadsBeforeItWritesAndDeleteDoesNot` fails. A link inside the project that points
to a file outside it would then read as inside, and one call would disclose any file on the disk.
Put the predicate back.

- [ ] **Step 11: Commit**

```bash
git add -A
git commit -m "refactor: a tool states an Action, and not an Effect"
```
**As built (Task 3).** The review found that `onPath` took two lambdas that must agree, and that
nothing forced the pairing. `onPath` is now private. Three named entry points are the only surface:
`Actions.read`, `Actions.write` and `Actions.readThenWrite`. Each one fixes its own pair, and
`readThenWrite` carries the reason `edit_file` tests both `writable` and `readable`. Four tests were
added: three that drive the write side, and one for a path the filesystem refuses. Commits `8aaec37`
and `99f004c`.

---

## Task 4: the question changes shape

`Decision.Ask` carries the tool name, the sentence, the operand and the permission. `EffectPolicy`
becomes stateless. `Approvals` holds a `Set<Permission>`.

**Files:**
- Modify: `src/main/java/dev/konacode/policy/Decision.java`
- Modify: `src/main/java/dev/konacode/policy/EffectPolicy.java`
- Modify: `src/main/java/dev/konacode/agent/ToolApproval.java`
- Modify: `src/main/java/dev/konacode/agent/Approvals.java`
- Modify: `src/main/java/dev/konacode/agent/Agent.java:234-256`
- Modify: `src/main/java/dev/konacode/cli/RichUi.java:126-180`
- Modify: `src/main/java/dev/konacode/cli/PlainUi.java:71-73`
- Modify: `src/main/java/dev/konacode/cli/Main.java:114-116`
- Modify: `src/test/java/dev/konacode/policy/EffectPolicyTest.java`
- Modify: `src/test/java/dev/konacode/agent/ApprovalsTest.java`
- Modify: `src/test/java/dev/konacode/agent/AgentTest.java`
- Modify: `src/test/java/dev/konacode/cli/{RichUiTest,PlainUiTest,RecordingUi,MainTest}.java`

- [ ] **Step 1: Write the failing test**

Replace the body of `src/test/java/dev/konacode/policy/EffectPolicyTest.java` from
`aReadOutsideAsksAndNamesThePathAndTheFolder` down, and add the tests below. Keep the `@TempDir`
fields, the `Running` double and the `path` helper.

The `policy()` helper takes no workspace now:

```java
    private EffectPolicy policy() {
        return new EffectPolicy();
    }
```

Replace every test that names `ask.action()`, `ask.subject()` or `ask.alwaysFolder()`:

```java
    @Test
    void aReadOutsideAsksAndNamesTheToolThePathAndThePermission() throws IOException {
        Path file = outside.toRealPath().resolve("secret.txt");

        Decision.Ask ask = assertInstanceOf(Decision.Ask.class,
                policy().check(new ReadFile(workspace(), StopCheck.NEVER),
                        path(file.toString())));

        assertEquals("read_file", ask.toolName());
        assertEquals("read outside this project", ask.intent());
        assertEquals(file.toString(), ask.operand());
        assertEquals(new Permission.InFolder("read_file", outside.toRealPath()),
                ask.permission().orElseThrow());
    }

    @Test
    void aWriteOutsideAsks() throws IOException {
        Path file = outside.toRealPath().resolve("notes.txt");

        Decision.Ask ask = assertInstanceOf(Decision.Ask.class,
                policy().check(new EditFile(workspace(), StopCheck.NEVER),
                        path(file.toString())));

        assertEquals("write outside this project", ask.intent());
        assertEquals(new Permission.InFolder("edit_file", outside.toRealPath()),
                ask.permission().orElseThrow());
    }

    @Test
    void aDeleteOutsideAsks() {
        Decision.Ask ask = assertInstanceOf(Decision.Ask.class,
                policy().check(new DeleteFile(workspace()),
                        path(outside.resolve("old.txt").toString())));

        assertEquals("write outside this project", ask.intent());
    }

    @Test
    void aCommandAsksAndOffersNoPermission() {
        Decision.Ask ask = assertInstanceOf(Decision.Ask.class,
                policy().check(new Running(), MAPPER.createObjectNode()));

        assertEquals("run_command", ask.toolName());
        assertEquals("run a command", ask.intent());
        assertEquals("run_command", ask.operand());
        assertTrue(ask.permission().isEmpty());
    }

    @Test
    void aCallWithNoUsablePathOffersNoPermission() {
        Decision.Ask ask = assertInstanceOf(Decision.Ask.class,
                policy().check(new EditFile(workspace(), StopCheck.NEVER),
                        MAPPER.createObjectNode()));

        assertEquals("edit_file", ask.operand());
        assertTrue(ask.permission().isEmpty());
    }

    @Test
    void thePolicyHoldsNoStateAndAnswersTheSameTwice() throws IOException {
        Path file = outside.toRealPath().resolve("secret.txt");
        EffectPolicy policy = policy();
        ReadFile tool = new ReadFile(workspace(), StopCheck.NEVER);

        assertEquals(policy.check(tool, path(file.toString())),
                policy.check(tool, path(file.toString())));
    }

    @Test
    void thePolicyCopiesTheOperandAndThePermissionOfTheAction() throws IOException {
        Path file = outside.toRealPath().resolve("secret.txt");
        ReadFile tool = new ReadFile(workspace(), StopCheck.NEVER);
        Action action = tool.computeAction(path(file.toString()));

        Decision.Ask ask = assertInstanceOf(Decision.Ask.class,
                policy().check(tool, path(file.toString())));

        assertEquals(action.operand(), ask.operand());
        assertEquals(action.permission(), ask.permission());
    }
```

Keep `listingAFolderRemembersThatFolderAndNotItsParent`,
`aLinkInsideTheProjectDoesNotOfferTheProject`, `aBrokenLinkOffersNoFolder`,
`aWriteThroughALinkAsksAboutTheEntry` and `everyActionBeginsWithAnImperativeVerb`, and change each
assertion from `alwaysFolder()` to `permission()` and from `action()` to `intent()`. For example:

```java
    @Test
    void aBrokenLinkOffersNoPermission() throws IOException {
        Path link = Files.createSymbolicLink(root.resolve("dangling"), outside.resolve("gone"));

        try {
            Decision.Ask ask = assertInstanceOf(Decision.Ask.class,
                    policy().check(new ReadFile(workspace(), StopCheck.NEVER),
                            path(link.toString())));

            assertTrue(ask.permission().isEmpty(), "a call that reaches nothing offers no always");
        } finally {
            Files.delete(link);
        }
    }

    private static String verbOf(Decision.Ask ask) {
        return ask.intent().split(" ", 2)[0];
    }
```

Add `import dev.konacode.tools.Action;`, `import dev.konacode.tools.Permission;` and
`import static org.junit.jupiter.api.Assertions.assertTrue;`.

- [ ] **Step 2: Run the test and see it fail**

Run: `mvn -q test -Dtest=EffectPolicyTest`
Expected: the build fails, because `Ask` has no `toolName`, `intent` or `permission`.

- [ ] **Step 3: Change `Decision`**

Replace `src/main/java/dev/konacode/policy/Decision.java`:

```java
package dev.konacode.policy;

import dev.konacode.tools.Permission;

import java.util.Optional;

/**
 * Whether a tool call may proceed.
 *
 * <p>Sealed deliberately: a new case is a compile error at every handling site.
 */
public sealed interface Decision {

    record Allow() implements Decision {}

    record Deny(String reason) implements Decision {}

    /**
     * A question, written and not yet put. The policy needs the user to decide.
     *
     * @param toolName the tool that wants to act. The question begins with it, and it is present
     *     even when the permission is empty.
     * @param intent what the tool wants to do, for example "write outside this project". The
     *     first word is an imperative verb, because the question builds a line from it.
     * @param operand what the call acts on, in words
     * @param permission what an "always" answer covers, or empty when konacode offers no
     *     "always". The question then shows yes and no only.
     */
    record Ask(String toolName, String intent, String operand, Optional<Permission> permission)
            implements Decision {}

    static Decision allow() {
        return new Allow();
    }

    static Decision deny(String reason) {
        return new Deny(reason);
    }

    static Decision ask(String toolName, String intent, String operand,
                        Optional<Permission> permission) {
        return new Ask(toolName, intent, operand, permission);
    }
}
```

- [ ] **Step 4: Change `EffectPolicy`**

Replace `src/main/java/dev/konacode/policy/EffectPolicy.java`:

```java
package dev.konacode.policy;

import com.fasterxml.jackson.databind.JsonNode;
import dev.konacode.tools.Action;
import dev.konacode.tools.Tool;

/**
 * Allows a call inside the launch directory, and asks about every other one.
 *
 * <p>The tool states what the call does and what it acts on. This class decides what to do about
 * the answer, and it writes the words. "Outside this project" is not a fact about the call: it is
 * this policy that names its own boundary. A policy with another boundary writes another sentence,
 * so a tool must never write it.
 *
 * <p>This class holds no state. It reads the tool on every call.
 */
public final class EffectPolicy implements ToolPolicy {

    @Override
    public Decision check(Tool tool, JsonNode args) {
        Action action = tool.computeAction(args);
        return switch (action.effect()) {
            case READS_INSIDE, WRITES_INSIDE -> Decision.allow();
            case READS_OUTSIDE -> ask("read outside this project", tool, action);
            case WRITES_OUTSIDE -> ask("write outside this project", tool, action);
            case RUNS -> ask("run a command", tool, action);
        };
    }

    private static Decision ask(String intent, Tool tool, Action action) {
        return Decision.ask(tool.name(), intent, action.operand(), action.permission());
    }
}
```

- [ ] **Step 5: Change `ToolApproval`**

In `src/main/java/dev/konacode/agent/ToolApproval.java`, replace the `ask` method and its javadoc:

```java
    /**
     * @param ask what the policy needs decided. A caller that draws the question offers
     *     {@code ALWAYS} only when {@code ask.permission()} is present. An {@code ALWAYS} with an
     *     empty permission is legal, and it approves this call only.
     */
    Answer ask(Decision.Ask ask);
```

Change the javadoc of `ALWAYS` to: *"Runs this call, and every later call the permission covers."*

- [ ] **Step 6: Change `Approvals`**

Replace `src/main/java/dev/konacode/agent/Approvals.java`:

```java
package dev.konacode.agent;

import dev.konacode.policy.Decision;
import dev.konacode.tools.Permission;

import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The answers the user gave during this session.
 *
 * <p>The memory sits here and not in the policy, so {@code /policy} changes the policy and the
 * answers stay. Nothing is written to disk, so the memory ends when konacode ends.
 *
 * <p>Coverage is equality. This class compares two permissions, and it never examines one.
 */
public final class Approvals {

    private final ToolApproval approval;
    private final Set<Permission> given = new HashSet<>();

    public Approvals(ToolApproval approval) {
        this.approval = Objects.requireNonNull(approval, "approval");
    }

    /** True when the call may run. */
    public boolean approve(Decision.Ask ask) {
        Optional<Permission> permission = ask.permission();
        if (permission.isPresent() && given.contains(permission.get())) {
            return true;
        }
        return switch (approval.ask(ask)) {
            case YES -> true;
            case NO -> false;
            case ALWAYS -> {
                // With no permission there is nothing to remember, so the answer counts once.
                permission.ifPresent(given::add);
                yield true;
            }
        };
    }
}
```

- [ ] **Step 7: Change `Agent`**

In `src/main/java/dev/konacode/agent/Agent.java`, inside the `case Decision.Ask ask` branch,
change the call and the message:

```java
                    approved = approvals.approve(ask);
```

```java
                    return ToolResult.err("konacode has no approval for this call: " + call.name()
                            + " on " + ask.operand() + ". This answers one call and sets no rule."
                            + " Call the tool again when the user asks, and let konacode put the"
                            + " question.");
```

The comment above the message does not change.

- [ ] **Step 8: Change `RichUi`**

In `src/main/java/dev/konacode/cli/RichUi.java`, replace lines 126-180:

```java
    @Override
    public Answer ask(Decision.Ask ask) {
        spinner.stop();
        watcher.stop();
        try {
            show(ask);
            return answer(read(), ask.permission().isPresent());
        } finally {
            watcher.start();
        }
    }

    private void show(Decision.Ask ask) {
        String verb = ask.intent().split(" ", 2)[0];
        out.println();
        out.println(ask.toolName() + " wants to " + ask.intent() + ".");
        out.println();
        out.println("  " + ask.operand());
        out.println();
        out.println("  y  " + verb + " it once");
        out.println("  n  refuse");
        ask.permission().ifPresent(
                permission -> out.println("  a  always, for " + permission.inWords()));
        out.flush();
    }
```

`read()` does not change. In `answer`, rename the parameter and use it unchanged:

```java
    private Answer answer(int key, boolean alwaysOffered) {
        if (key == -1) {
            out.println();
            out.println("Could not read the answer. konacode refuses.");
            out.flush();
            return Answer.NO;
        }
        if (key == EscapeWatcher.ESCAPE) {
            cancellation.request();
            return Answer.NO;
        }
        if (key == 'y' || key == 'Y') {
            return Answer.YES;
        }
        if (alwaysOffered && (key == 'a' || key == 'A')) {
            return Answer.ALWAYS;
        }
        return Answer.NO;
    }
```

- [ ] **Step 9: Change `PlainUi`**

In `src/main/java/dev/konacode/cli/PlainUi.java`, replace lines 71-73:

```java
    @Override
    public Answer ask(Decision.Ask ask) {
        return Answer.NO;
    }
```

- [ ] **Step 10: Change `Main`**

In `src/main/java/dev/konacode/cli/Main.java`, replace `defaultPolicy`:

```java
    static ToolPolicy defaultPolicy(boolean canAsk, Workspace workspace) {
        return canAsk ? new EffectPolicy() : new AllowAllPolicy();
    }
```

Keep the `workspace` parameter. `MainTest` calls this method, and a caller that passes a workspace
still reads correctly. If the compiler warns about the unused parameter, leave it: the signature is
what `MainTest` pins.

- [ ] **Step 11: Change the remaining tests**

- `src/test/java/dev/konacode/agent/ApprovalsTest.java`: build each `Decision.Ask` with the four
  new fields, and replace every `approve(name, ask)` with `approve(ask)`. Add a test that an
  `InFolder` permission never covers an `ExactCommand` permission:

```java
    @Test
    void aFolderApprovalNeverCoversACommand() {
        Approvals approvals = new Approvals(recording(Answer.ALWAYS));
        approvals.approve(new Decision.Ask("run_command", "run a command", "mvn test",
                Optional.of(new Permission.InFolder("run_command", Path.of("/tmp")))));

        approvals.approve(new Decision.Ask("run_command", "run a command", "mvn test",
                Optional.of(new Permission.ExactCommand("run_command", "mvn test"))));

        assertEquals(2, asked, "a different kind of permission must ask again");
    }
```

  Adapt `recording` and `asked` to the double the file already uses.
- `src/test/java/dev/konacode/agent/AgentTest.java`: every `Decision.Ask` and every
  `ToolApproval` double takes the new shape. A tool double must answer `computeAction`.
- `src/test/java/dev/konacode/cli/RecordingUi.java`: `ask(Decision.Ask)`.
- `src/test/java/dev/konacode/cli/RichUiTest.java`: build the `Ask` with a permission, and assert
  the `a` line reads `always, for read_file in /etc`.
- `src/test/java/dev/konacode/cli/PlainUiTest.java`: `ask(Decision.Ask)`.
- `src/test/java/dev/konacode/cli/MainTest.java`: `defaultPolicy` still takes the workspace.

- [ ] **Step 12: Run the whole suite**

Run: `mvn -q test`
Expected: PASS.

- [ ] **Step 13: Prove the memory is tested**

In `Approvals.approve`, delete the two lines that test `given.contains`. Run
`mvn clean test -Dtest=ApprovalsTest`.
Expected: a test fails, because konacode asks a second time. Put the lines back.

- [ ] **Step 14: Commit**

```bash
git add -A
git commit -m "refactor: the question carries a Permission, and the policy is stateless"
```
**As built (Task 4).** `cli/Commands.java` also changed, because it built `new EffectPolicy(workspace)`.
The `Workspace` it held then read nowhere, so the field, the constructor parameter and every caller
argument were removed. Step 10 above told the implementer to keep the `Workspace` parameter of
`Main.defaultPolicy`; that was wrong for the same reason, and the parameter was removed too, so the
method is now `defaultPolicy(boolean canAsk)`. `Decision.Ask` gained the compact constructor its
sibling `Action` already had. Commits `0a15bc5` and `19b953e`.

**Open, deferred.** When a tool cannot resolve its `path` argument, `Actions` names the tool as the
operand, so the question shows the tool name twice. It is true and it tells the user nothing. It
fires only on a malformed call from the model. This is a follow-up issue, and not a task here.

---

## Task 5: `CappedOutput`

Keeps the first part and the last part of a stream, and reports what it removed.

**Files:**
- Create: `src/main/java/dev/konacode/tools/CappedOutput.java`
- Test: `src/test/java/dev/konacode/tools/CappedOutputTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/dev/konacode/tools/CappedOutputTest.java`:

```java
package dev.konacode.tools;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CappedOutputTest {

    private static CappedOutput write(int head, int tail, String text) {
        CappedOutput output = new CappedOutput(head, tail);
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        output.write(bytes, bytes.length);
        return output;
    }

    @Test
    void outputBelowTheCapComesBackWhole() {
        assertEquals("hello", write(10, 10, "hello").text());
    }

    @Test
    void outputAtTheCapComesBackWhole() {
        assertEquals("abcdefghij", write(5, 5, "abcdefghij").text());
    }

    @Test
    void theCapKeepsTheFirstPartAndTheLastPart() {
        String text = write(4, 4, "AAAA1111222233334444BBBB").text();

        assertTrue(text.startsWith("AAAA"), text);
        assertTrue(text.endsWith("BBBB"), text);
    }

    @Test
    void theCapNamesTheLinesAndTheBytesItRemoved() {
        assertEquals("A\n… 3 lines (7 bytes) removed …\nB",
                write(1, 1, "A\nxx\nyy\nB").text());
    }

    @Test
    void aRemovedPartWithNoLineBreakReportsNoLines() {
        assertEquals("A\n… 0 lines (3 bytes) removed …\nB", write(1, 1, "AxyzB").text());
    }

    @Test
    void severalWritesBehaveAsOneStream() {
        CappedOutput output = new CappedOutput(1, 1);
        output.write("Ax".getBytes(StandardCharsets.UTF_8), 2);
        output.write("yB".getBytes(StandardCharsets.UTF_8), 2);

        assertEquals("A\n… 0 lines (2 bytes) removed …\nB", output.text());
    }

    @Test
    void writeUsesTheLengthAndNotTheWholeBuffer() {
        CappedOutput output = new CappedOutput(10, 10);
        output.write(new byte[] {'a', 'b', 'c', 0, 0}, 3);

        assertEquals("abc", output.text());
    }

    @Test
    void aCapOfZeroIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new CappedOutput(0, 10));
        assertThrows(IllegalArgumentException.class, () -> new CappedOutput(10, 0));
    }
}
```

- [ ] **Step 2: Run the test and see it fail**

Run: `mvn -q test -Dtest=CappedOutputTest`
Expected: the build fails, because `CappedOutput` does not exist.

- [ ] **Step 3: Write the implementation**

Create `src/main/java/dev/konacode/tools/CappedOutput.java`:

```java
package dev.konacode.tools;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Keeps the first part and the last part of a stream, and counts what it removed between them.
 *
 * <p>A build prints its command line at the start and its error at the end. A cap that kept the
 * first part only would remove the answer.
 *
 * <p>This class is not thread safe. A caller that writes from one thread and reads from another
 * must hold one lock across both.
 */
final class CappedOutput {

    private final ByteArrayOutputStream head = new ByteArrayOutputStream();
    private final int headBytes;
    private final byte[] tail;
    private int tailStart;
    private int tailLength;
    private long removedBytes;
    private long removedLines;

    CappedOutput(int headBytes, int tailBytes) {
        if (headBytes < 1 || tailBytes < 1) {
            throw new IllegalArgumentException("Each part must keep at least one byte.");
        }
        this.headBytes = headBytes;
        this.tail = new byte[tailBytes];
    }

    /** Adds the first {@code length} bytes of {@code buffer}. */
    void write(byte[] buffer, int length) {
        for (int index = 0; index < length; index++) {
            add(buffer[index]);
        }
    }

    private void add(byte value) {
        if (head.size() < headBytes) {
            head.write(value);
            return;
        }
        if (tailLength < tail.length) {
            tail[(tailStart + tailLength) % tail.length] = value;
            tailLength++;
            return;
        }
        byte dropped = tail[tailStart];
        if (dropped == '\n') {
            removedLines++;
        }
        removedBytes++;
        tail[tailStart] = value;
        tailStart = (tailStart + 1) % tail.length;
    }

    /** What the model reads. The bytes are decoded as UTF-8, and a bad byte becomes U+FFFD. */
    String text() {
        String first = head.toString(StandardCharsets.UTF_8);
        byte[] last = new byte[tailLength];
        for (int index = 0; index < tailLength; index++) {
            last[index] = tail[(tailStart + index) % tail.length];
        }
        String second = new String(last, StandardCharsets.UTF_8);
        if (removedBytes == 0) {
            return first + second;
        }
        return first + "\n… " + removedLines + " lines (" + removedBytes + " bytes) removed …\n"
                + second;
    }
}
```

- [ ] **Step 4: Run the test and see it pass**

Run: `mvn -q test -Dtest=CappedOutputTest`
Expected: PASS.

- [ ] **Step 5: Prove the ring buffer is tested**

Change `tailStart = (tailStart + 1) % tail.length;` to `tailStart = 0;` and run
`mvn clean test -Dtest=CappedOutputTest`.
Expected: `theCapKeepsTheFirstPartAndTheLastPart` fails. Put the line back.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/dev/konacode/tools/CappedOutput.java src/test/java/dev/konacode/tools/CappedOutputTest.java
git commit -m "feat: add CappedOutput, which keeps the first and the last part of a stream"
```

**As built (Task 5), the marker.** The line between the two parts was `… N lines (M bytes)
removed …`. A build tool prints its own truncation notice in that shape, so a model can read the
line as command output. The marker is now `<removed 3 lines, 7 bytes from the middle>`, in the
family of `<error>`, which is konacode's one existing token for "konacode is speaking, not the
tool". A count of one reads correctly. `outputAtTheCapComesBackWhole` did not pin the boundary it
named, so `theCapSplitsExactlyAtTheTwoLimits` was added. Commit `9fd0fee`.

**Declined.** The review measured the per-byte loop in `write` at about 980 ms for 100 MB, against
about 340 ms for a block copy. The loop stays. A block copy adds complexity inside a ring buffer,
and one second of processor time for 100 MB, on a daemon thread, is a fair price for code that a
reader can check.

**As built (Task 5).** The expected value above was wrong when this plan was written. It said
2 lines and 6 bytes. The input `A\nxx\nyy\nB` is 9 bytes, the head keeps 1 and the tail keeps 1, so
7 bytes leave the tail, and 3 of them are a line break. The byte that `B` pushes out is a removal
like every other one. The value above is now correct. Commit `05fad7f`.

---

## Task 6: `RunCommand` states its action

The tool exists, it describes itself, and it answers `computeAction`. `execute` comes in Task 7.

**Files:**
- Create: `src/main/java/dev/konacode/tools/RunCommand.java`
- Test: `src/test/java/dev/konacode/tools/RunCommandTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/dev/konacode/tools/RunCommandTest.java`:

```java
package dev.konacode.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunCommandTest {

    @TempDir
    Path root;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ObjectNode command(String line) {
        ObjectNode args = MAPPER.createObjectNode();
        args.put("command", line);
        return args;
    }

    private RunCommand tool() {
        return new RunCommand(new Workspace(root), StopCheck.NEVER, Duration.ofSeconds(10));
    }

    @Test
    void theToolIsNamedRunCommand() {
        assertEquals("run_command", tool().name());
    }

    @Test
    void theSchemaRequiresACommand() {
        assertEquals("[\"command\"]", tool().inputSchema().get("required").toString());
    }

    @Test
    void aCommandAlwaysRuns() {
        assertEquals(Effect.RUNS, tool().computeAction(command("ls")).effect());
    }

    @Test
    void theOperandIsTheCommandLine() {
        assertEquals("mvn -q test", tool().computeAction(command("mvn -q test")).operand());
    }

    @Test
    void aPlainLineOffersTheExactLine() {
        Action action = tool().computeAction(command("mvn -q test"));

        assertEquals(new Permission.ExactCommand("run_command", "mvn -q test"),
                action.permission().orElseThrow());
    }

    @Test
    void aLineThatJoinsCommandsStillOffersTheExactLine() {
        Action action = tool().computeAction(command("git add -A && git status | head -5; true"));

        assertTrue(action.permission().isPresent(),
                "a pipe, && and ; mean the same thing on the next day");
    }

    @Test
    void aLineThatExpandsOffersNoPermission() {
        for (String line : new String[] {
                "echo $HOME", "echo `date`", "rm *.log", "ls file?.txt",
                "ls file[12].txt", "ls ~/notes"}) {
            assertTrue(tool().computeAction(command(line)).permission().isEmpty(),
                    "this line means something else on another day: " + line);
        }
    }

    @Test
    void aMissingCommandOffersNoPermissionAndNamesTheTool() {
        Action action = tool().computeAction(MAPPER.createObjectNode());

        assertEquals(Effect.RUNS, action.effect());
        assertEquals("run_command", action.operand());
        assertTrue(action.permission().isEmpty());
    }

    @Test
    void aBlankCommandOffersNoPermission() {
        assertTrue(tool().computeAction(command("   ")).permission().isEmpty());
    }

    @Test
    void theUserCanStopTheCommand() {
        assertTrue(tool().stopsOnInterrupt());
    }

    @Test
    void theDescriptionTellsTheModelThatANonZeroExitIsNormal() {
        assertTrue(tool().description().contains("exit code"),
                "the description is prompt text, and it must name the exit code");
    }
}
```

- [ ] **Step 2: Run the test and see it fail**

Run: `mvn -q test -Dtest=RunCommandTest`
Expected: the build fails, because `RunCommand` does not exist.

- [ ] **Step 3: Write the implementation**

Create `src/main/java/dev/konacode/tools/RunCommand.java`:

```java
package dev.konacode.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.Objects;

/**
 * Runs one shell line with {@code sh -c}, and gives back what it printed.
 *
 * <p>A shell line, and not an argument list. The model writes a shell line, because every example
 * it read is a shell line. A list would also refuse a pipe and refuse {@code &&}, and the model
 * would then write {@code sh -c} inside the list.
 */
public final class RunCommand implements Tool {

    static final int HEAD_BYTES = 50_000;
    static final int TAIL_BYTES = 50_000;
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(600);

    /** Each character makes the line mean something else on another day. */
    private static final String EXPANDING = "$`*?[~";

    private final Workspace workspace;
    private final StopCheck stop;
    private final Duration timeout;

    public RunCommand(Workspace workspace, StopCheck stop, Duration timeout) {
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.stop = Objects.requireNonNull(stop, "stop");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    @Override
    public String name() {
        return "run_command";
    }

    @Override
    public String description() {
        return """
                Run a shell command in the project directory and give back what it printed. \
                The command runs with `sh -c`, so a pipe, `&&` and `;` all work. \
                Standard output and standard error come back together, and the last line gives \
                the exit code. A command that ends with a non-zero exit code is normal output, \
                and not an error: read the output and decide what to do. \
                The command gets no standard input, so a command that waits for input fails at \
                once. Long output keeps the first part and the last part.""";
    }

    @Override
    public ObjectNode inputSchema() {
        return Schemas.object()
                .requiredString("command", "The shell command line to run, for example 'mvn -q test'.")
                .build();
    }

    @Override
    public boolean stopsOnInterrupt() {
        return true;
    }

    @Override
    public Action computeAction(JsonNode args) {
        String line = line(args);
        if (line == null) {
            return Action.once(Effect.RUNS, name());
        }
        if (expands(line)) {
            // The line means something else on another day, so no standing permission is honest.
            return Action.once(Effect.RUNS, line);
        }
        return Action.of(Effect.RUNS, line, new Permission.ExactCommand(name(), line));
    }

    @Override
    public ToolResult execute(JsonNode args) {
        return ToolResult.err("Not implemented yet.");
    }

    /** The command line, or null when the argument is absent, not text, or blank. */
    private static String line(JsonNode args) {
        JsonNode command = args.path("command");
        if (!command.isTextual() || command.asText().isBlank()) {
            return null;
        }
        return command.asText();
    }

    static boolean expands(String line) {
        for (int index = 0; index < line.length(); index++) {
            if (EXPANDING.indexOf(line.charAt(index)) >= 0) {
                return true;
            }
        }
        return false;
    }
}
```

- [ ] **Step 4: Run the test and see it pass**

Run: `mvn -q test -Dtest=RunCommandTest`
Expected: PASS.

- [ ] **Step 5: Prove the expansion rule is tested**

Change `EXPANDING` to `"$"` and run `mvn clean test -Dtest=RunCommandTest`.
Expected: `aLineThatExpandsOffersNoPermission` fails. Put the value back.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/dev/konacode/tools/RunCommand.java src/test/java/dev/konacode/tools/RunCommandTest.java
git commit -m "feat: add run_command, which states its action"
```

**As built (Task 6).** The description above told the model what the tool does, and not what it is
for. Every other tool in konacode names the work it is not for. Without that, a model has no reason
to prefer `read_file` over `run_command("cat notes.txt")`, and konacode cannot judge a path inside
a shell line. Two sentences were added, and one test pins them. Four tests proved less than their
names claimed, and each one was tightened: the expansion loop now asserts the effect, the
description test asserts the claim and not one substring, `aCommandAlwaysRuns` drives all three
branches, and a command that is not text has a test of its own. Commit `19d7219`.

**Checked, and left alone.** The review tested the six characters against `{`, `}`, `!`, a
backslash, both quote characters, and `<(`. None of them belongs in the set. Brace expansion is
deterministic, so a line that holds it means the same thing on another day. `/bin/sh` on this
machine is dash, which does not expand a brace at all.

---

## Task 7: `RunCommand` runs the command

**Files:**
- Modify: `src/main/java/dev/konacode/tools/RunCommand.java`
- Modify: `src/test/java/dev/konacode/tools/RunCommandTest.java`

- [ ] **Step 1: Write the failing test**

Add to `src/test/java/dev/konacode/tools/RunCommandTest.java`:

```java
    private String run(String line) {
        ToolResult result = tool().execute(command(line));
        return assertInstanceOf(ToolResult.Ok.class, result).text();
    }

    @Test
    void aCommandGivesBackWhatItPrinted() {
        assertTrue(run("echo hello").startsWith("hello"), "the output must come first");
    }

    @Test
    void aCommandThatSucceedsReportsExitZero() {
        assertTrue(run("echo hello").endsWith("\nexit 0"), run("echo hello"));
    }

    @Test
    void aCommandThatFailsIsNotAToolFailure() {
        assertTrue(run("exit 3").endsWith("\nexit 3"), "konacode ran it, so the result is Ok");
    }

    @Test
    void standardErrorComesBackWithStandardOutput() {
        assertTrue(run("echo bad >&2").contains("bad"));
    }

    @Test
    void theCommandRunsInTheProjectDirectory() throws IOException {
        assertTrue(run("pwd").startsWith(root.toRealPath().toString()), run("pwd"));
    }

    @Test
    void theCommandGetsNoStandardInput() {
        assertTrue(run("cat; echo done").contains("done"),
                "cat must reach the end of input at once and not hold the turn");
    }

    @Test
    void longOutputKeepsTheFirstPartAndTheLastPart() {
        String text = run("seq 1 200000");

        assertTrue(text.startsWith("1\n"), "the first line must survive");
        assertTrue(text.contains("<removed "), "the cap must say what it removed");
        assertTrue(text.contains("200000"), "the last line must survive");
    }

    @Test
    void aMissingCommandIsAToolFailure() {
        ToolResult result = tool().execute(MAPPER.createObjectNode());

        assertInstanceOf(ToolResult.Err.class, result);
    }
```

Add these imports:

```java
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
```

- [ ] **Step 2: Run the test and see it fail**

Run: `mvn -q test -Dtest=RunCommandTest`
Expected: every new test fails with `Not implemented yet.`

- [ ] **Step 3: Write the implementation**

In `src/main/java/dev/konacode/tools/RunCommand.java`, replace `execute` and add the helpers:

```java
    @Override
    public ToolResult execute(JsonNode args) {
        String line = line(args);
        if (line == null) {
            return ToolResult.err("Give a command as a non-empty string in the field 'command'.");
        }
        Process process;
        try {
            process = new ProcessBuilder("sh", "-c", line)
                    .directory(workspace.root().toFile())
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException e) {
            return ToolResult.err("Could not start a shell for: " + line + ". " + e.getMessage());
        }
        closeInput(process);
        CappedOutput output = new CappedOutput(HEAD_BYTES, TAIL_BYTES);
        Thread drain = drain(process, output);
        return waitFor(process, drain, output, line);
    }

    private ToolResult waitFor(Process process, Thread drain, CappedOutput output, String line) {
        try {
            process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return kill(process, drain, "Interrupted while this command ran: " + line);
        }
        join(drain);
        synchronized (output) {
            return ToolResult.ok(output.text() + "\nexit " + process.exitValue());
        }
    }

    /**
     * A command with an open input holds the turn until it is stopped. A closed input makes it
     * fail at once instead.
     */
    private static void closeInput(Process process) {
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
            // The process is already gone, so there is no input to close.
        }
    }

    /**
     * Reads the output on its own thread. A pipe that fills would stop the process, so somebody
     * must read it while the process runs.
     */
    private static Thread drain(Process process, CappedOutput output) {
        Thread thread = new Thread(() -> {
            byte[] buffer = new byte[8192];
            try (InputStream in = process.getInputStream()) {
                int read;
                while ((read = in.read(buffer)) != -1) {
                    synchronized (output) {
                        output.write(buffer, read);
                    }
                }
            } catch (IOException ignored) {
                // The process died. What arrived is what the model reads.
            }
        }, "konacode-command-output");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void join(Thread drain) {
        try {
            drain.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static ToolResult kill(Process process, Thread drain, String message) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        join(drain);
        return ToolResult.err(message);
    }
```

Add these imports:

```java
import java.io.IOException;
import java.io.InputStream;
```

- [ ] **Step 4: Run the test and see it pass**

Run: `mvn -q test -Dtest=RunCommandTest`
Expected: PASS.

- [ ] **Step 5: Prove the drain thread is needed**

Delete the `drain` call and read the stream after `waitFor` instead. Run
`mvn clean test -Dtest=RunCommandTest#longOutputKeepsTheFirstPartAndTheLastPart`.
Expected: the test hangs or fails, because the pipe fills. Put the drain thread back.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: run_command runs a shell line and reports the exit code"
```

---

## Task 8: the timeout and the stop

**Files:**
- Modify: `src/main/java/dev/konacode/tools/RunCommand.java`
- Modify: `src/test/java/dev/konacode/tools/RunCommandTest.java`

- [ ] **Step 1: Write the failing test**

Add to `src/test/java/dev/konacode/tools/RunCommandTest.java`:

```java
    /** Answers "stopped" from the first question. */
    private static final StopCheck STOPPED = () -> true;

    @Test
    void aCommandThatPassesTheTimeoutIsStopped() {
        RunCommand tool = new RunCommand(new Workspace(root), StopCheck.NEVER,
                Duration.ofMillis(200));

        ToolResult result = tool.execute(command("sleep 30"));

        ToolResult.Err err = assertInstanceOf(ToolResult.Err.class, result);
        assertTrue(err.message().contains("sleep 30"), err.message());
        assertTrue(err.message().contains("did not finish"), err.message());
    }

    @Test
    void theUserStopsACommandThatRuns() {
        RunCommand tool = new RunCommand(new Workspace(root), STOPPED, Duration.ofSeconds(30));

        ToolResult result = tool.execute(command("sleep 30"));

        ToolResult.Err err = assertInstanceOf(ToolResult.Err.class, result);
        assertTrue(err.message().contains("Stopped by the user"), err.message());
        assertTrue(err.message().contains("sleep 30"), err.message());
    }

    @Test
    void aStopEndsTheCommandQuickly() {
        RunCommand tool = new RunCommand(new Workspace(root), STOPPED, Duration.ofSeconds(30));
        long started = System.nanoTime();

        tool.execute(command("sleep 30"));

        long millis = (System.nanoTime() - started) / 1_000_000;
        assertTrue(millis < 5_000, "the command must end at once, and it took " + millis + " ms");
    }

    @Test
    void theDefaultTimeoutIsTenMinutes() {
        assertEquals(Duration.ofSeconds(600), RunCommand.DEFAULT_TIMEOUT);
    }

    @Test
    void aConfiguredTimeoutIsUsed() {
        System.setProperty("konacode.command.timeoutSeconds", "5");
        try {
            assertEquals(Duration.ofSeconds(5), RunCommand.configuredTimeout());
        } finally {
            System.clearProperty("konacode.command.timeoutSeconds");
        }
    }

    @Test
    void aWrongTimeoutFailsLoudly() {
        System.setProperty("konacode.command.timeoutSeconds", "soon");
        try {
            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    RunCommand::configuredTimeout);
            assertTrue(thrown.getMessage().contains("konacode.command.timeoutSeconds"));
        } finally {
            System.clearProperty("konacode.command.timeoutSeconds");
        }
    }

    @Test
    void aTimeoutBelowOneSecondFailsLoudly() {
        System.setProperty("konacode.command.timeoutSeconds", "0");
        try {
            assertThrows(IllegalArgumentException.class, RunCommand::configuredTimeout);
        } finally {
            System.clearProperty("konacode.command.timeoutSeconds");
        }
    }
```

Add `import static org.junit.jupiter.api.Assertions.assertThrows;`.

- [ ] **Step 2: Run the test and see it fail**

Run: `mvn -q test -Dtest=RunCommandTest`
Expected: `configuredTimeout` does not compile, and the two `sleep 30` tests wait 30 seconds.

- [ ] **Step 3: Write the implementation**

In `src/main/java/dev/konacode/tools/RunCommand.java`, replace `waitFor` and add
`configuredTimeout`:

```java
    private static final long POLL_MILLIS = 50;

    private ToolResult waitFor(Process process, Thread drain, CappedOutput output, String line) {
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            while (!process.waitFor(POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                if (stop.stopped()) {
                    return kill(process, drain,
                            "Stopped by the user before this command finished: " + line);
                }
                if (System.nanoTime() - deadline >= 0) {
                    return kill(process, drain, "This command did not finish in "
                            + timeout.toSeconds() + " seconds and was stopped: " + line);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return kill(process, drain, "Interrupted while this command ran: " + line);
        }
        join(drain);
        synchronized (output) {
            return ToolResult.ok(output.text() + "\nexit " + process.exitValue());
        }
    }

    /**
     * How long konacode waits for one command.
     *
     * <p>A wrong value is an error, for the reason {@code konacode.maxIterations} gives. The user
     * owns this value, and the model does not: a model that could raise it would escape the limit.
     */
    public static Duration configuredTimeout() {
        String configured = System.getProperty("konacode.command.timeoutSeconds");
        if (configured == null) {
            return DEFAULT_TIMEOUT;
        }
        long seconds;
        try {
            seconds = Long.parseLong(configured.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("konacode.command.timeoutSeconds must be a whole"
                    + " number of seconds, but was: " + configured);
        }
        if (seconds < 1) {
            throw new IllegalArgumentException("konacode.command.timeoutSeconds must be at least"
                    + " 1, but was: " + configured);
        }
        return Duration.ofSeconds(seconds);
    }
```

Add `import java.util.concurrent.TimeUnit;`.

The timeout message reports whole seconds. A timeout below one second therefore reports
`0 seconds`, and only a test sets a value that small.

- [ ] **Step 4: Run the test and see it pass**

Run: `mvn -q test -Dtest=RunCommandTest`
Expected: PASS, and the class finishes in a few seconds.

- [ ] **Step 5: Prove the process is really gone**

Add this test and keep it:

```java
    @Test
    void aStoppedCommandLeavesNoChildBehind() throws Exception {
        Path marker = root.resolve("marker.txt");
        RunCommand tool = new RunCommand(new Workspace(root), STOPPED, Duration.ofSeconds(30));

        tool.execute(command("sh -c 'sleep 2; echo late > " + marker + "'"));
        Thread.sleep(3000);

        assertFalse(Files.exists(marker), "the child of the shell must be destroyed too");
    }
```

Add `import java.nio.file.Files;` and
`import static org.junit.jupiter.api.Assertions.assertFalse;`.

Run: `mvn -q test -Dtest=RunCommandTest#aStoppedCommandLeavesNoChildBehind`
Expected: PASS.

Then delete `process.descendants().forEach(ProcessHandle::destroyForcibly);` and run
`mvn clean test -Dtest=RunCommandTest#aStoppedCommandLeavesNoChildBehind`.
Expected: the test fails. Put the line back.

Note: this test uses a command line that holds a `'` and no expanding character, so
`computeAction` is not involved. `execute` is called directly.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: run_command stops on ESC and on a timeout"
```

---

## Task 9: wire it in, and write it down

**Files:**
- Modify: `src/main/java/dev/konacode/cli/Main.java:85-89`
- Modify: `src/test/java/dev/konacode/cli/MainTest.java`
- Modify: `CLAUDE.md`
- Modify: `README.md`
- Modify: `ARCHITECTURE.md`

- [ ] **Step 1: Write the failing test**

Add to `src/test/java/dev/konacode/cli/MainTest.java`:

`Main.build` does not give the registry back, so the test reads it through `/tools`, in the way
`anAlwaysAnswerSurvivesAChangeOfPolicy` reads `/policy`.

```java
    @Test
    void theRegistryHoldsRunCommand() throws IOException {
        Workspace workspace = new Workspace(root);
        SkillRegistry skills = new SkillRegistry(new Workspace(root.resolve("skills")));
        RecordingUi ui = new RecordingUi("/tools");

        Main.build(new ScriptedClient(), skills, ui, Level.OFF, new Cancellation(), 8, Trace.NONE,
                workspace).run();

        assertTrue(ui.answers.get(0).contains("run_command"), ui.answers.get(0));
    }
```

- [ ] **Step 2: Run the test and see it fail**

Run: `mvn -q test -Dtest=MainTest` or `mvn -q test -Dtest=CommandsTest`
Expected: FAIL, because the registry holds four tools.

- [ ] **Step 3: Register the tool**

In `src/main/java/dev/konacode/cli/Main.java`, replace lines 85-89:

```java
        ToolRegistry registry = ToolRegistry.of(
                new ListFiles(workspace, cancellation),
                new ReadFile(workspace, cancellation),
                new EditFile(workspace, cancellation),
                new DeleteFile(workspace),
                new RunCommand(workspace, cancellation, RunCommand.configuredTimeout()));
```

Add `import dev.konacode.tools.RunCommand;`.

- [ ] **Step 4: Run the whole suite**

Run: `mvn -q test`
Expected: PASS.

- [ ] **Step 5: Update `CLAUDE.md`**

In the `dev.konacode.tools` table:

- Change the `Tool` row: `Effect effect(JsonNode args)` becomes `Action computeAction(JsonNode args)`, and the sentence about `stopsOnInterrupt` and `effect` names `computeAction` instead.
- Add a row for `Action`: record `(Effect effect, String operand, Optional<Permission> permission)`. What one call does, what it acts on, and what a standing "always" would cover. An empty permission says that no standing "always" can describe this call.
- Add a row for `Permission`: sealed interface, `InFolder(toolName, folder)` or `ExactCommand(toolName, command)`. konacode compares two permissions and never examines one, so a record gives the whole lookup.
- Add a row for `RunCommand`: runs one shell line with `sh -c` in the project directory. Merges the two output streams, reports the exit code, and gives back `Ok` for a non-zero exit, because konacode ran the command. Offers an `ExactCommand` permission only when the line holds none of `$ ` ` * ? [ ~`.
- Add a row for `CappedOutput`: keeps the first 50 KB and the last 50 KB of a stream, and names the lines and the bytes it removed.
- Add a row for `Actions`: static helper, package-private. `read`, `write` and `readThenWrite` build the `Action` of a tool that acts on one path. Three named entry points, because the two questions a path needs must agree, and two loose lambdas let a caller pair them wrongly.

In the `dev.konacode.policy` table, change the `Decision` row: `Ask(String toolName, String intent, String operand, Optional<Permission> permission)`. Change the `EffectPolicy` row: it holds no state, and it reads the `Action` the tool states.

In the `dev.konacode.agent` table, change the `ToolApproval` row to `Answer ask(Decision.Ask ask)`, and the `Approvals` row to say the memory is a set of permissions.

In the `dev.konacode.cli` table, change the `Commands` row if it names a `Workspace`. `Commands` holds none now.

In the configuration table, add a row:

| `konacode.command.timeoutSeconds` | property | no | `600` |

Update the test count on the `mvn test` line to the number the suite reports.

- [ ] **Step 6: Update `README.md`**

Add `run_command` to the list of tools, and add `konacode.command.timeoutSeconds` to the
configuration table. Keep the words of the existing rows.

- [ ] **Step 7: Update `ARCHITECTURE.md`**

Find the section that describes a turn and the approval seam. Change every mention of `effect` to
`computeAction`, and every mention of `alwaysFolder` to `permission`. Add one paragraph:

> A tool states one `Action`: what the call does, what it acts on, and what a standing "always"
> would cover. A tool that gives no permission says that no standing "always" can describe this
> call. `run_command` gives none for a line that expands, because that line means something else
> on another day.

- [ ] **Step 8: Run the whole suite one more time**

Run: `mvn -q test`
Expected: PASS. Record the test count, and check that `CLAUDE.md` gives the same number.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "feat: register run_command, and record the new seam in the docs"
```

---

## Manual test

Run this after Task 9, from a real terminal.

```bash
sdk use java 21.0.2-open
mvn -q package
mkdir -p /tmp/konacode-run-test && cd /tmp/konacode-run-test
OPENAI_API_KEY=sk-... java -jar ~/projects/ai/konacode/target/konacode.jar
```

| # | Type this | Expect this |
|---|---|---|
| 1 | `run ls in this folder` | The question shows `run_command wants to run a command.`, the line, and three choices. |
| 2 | Press `a` | The command runs. |
| 3 | `run ls again` | No question. The permission covers the exact line. |
| 4 | `run ls -la` | The question comes back. Another line is another permission. |
| 5 | `run echo $HOME` | The question shows `y` and `n`, and no `a`. |
| 6 | `run sleep 300`, then press ESC | The turn stops within a second, and the model reads that the user stopped it. |
| 7 | `run a command that fails, for example exit 7` | The model reads `exit 7` and does not report a tool failure. |
| 8 | `/policy allow-all`, then `run ls` | No question. |
