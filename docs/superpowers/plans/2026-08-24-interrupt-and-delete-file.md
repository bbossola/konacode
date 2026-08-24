# Interrupt, tool stop and DeleteFile — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The user presses ESC and the turn stops, the tools stop themselves, and a new `delete_file` tool lets the model remove a file it created.

**Architecture:** A `Cancellation` object joins the ESC watcher to the agent loop. The loop arms a thread interrupt around the provider call, and around a tool that declares `stopsOnInterrupt()`. A tool that works in steps reads `StopCheck` between them and reports what it changed. The conversation keeps the whole stopped turn, and every tool call that never ran gets a `ToolMessage` that says so.

**Tech Stack:** Java 21, Maven, JUnit 5, Mockito, JLine 4.3.1, Jackson.

**Specs:** [the interrupt design](../specs/2026-08-23-interrupt-design.md) and [the DeleteFile design](../specs/2026-08-24-delete-file-design.md).

---

## Before you start

konacode needs Java 21. The default java on this machine is 11.

```bash
sdk use java 21.0.2-open
mvn test
```

Expected: `BUILD SUCCESS`, 186 tests, 0 failures. If that fails, stop and fix the environment first.

Every test in this repository is offline. No test you write may touch the network.

---

## File structure

**New files**

| File | Responsibility |
|---|---|
| `src/main/java/dev/konacode/tools/StopCheck.java` | One question: has the user asked to stop? |
| `src/main/java/dev/konacode/agent/Cancellation.java` | The request to stop one turn. Owns the flag and the armed thread. |
| `src/main/java/dev/konacode/cli/EscapeWatcher.java` | Reads the terminal during a turn and calls `request()` on ESC. |
| `src/main/java/dev/konacode/tools/DeleteFile.java` | The `delete_file` tool. |
| `src/test/java/dev/konacode/agent/CancellationTest.java` | |
| `src/test/java/dev/konacode/cli/EscapeWatcherTest.java` | |
| `src/test/java/dev/konacode/tools/DeleteFileTest.java` | |

**Modified files**

| File | Change |
|---|---|
| `src/main/java/dev/konacode/tools/Tool.java` | Gains `boolean stopsOnInterrupt()`. |
| `src/main/java/dev/konacode/tools/Workspace.java` | `listSorted` and `readUtf8Capped` take a `StopCheck`. Gains `delete`. |
| `src/main/java/dev/konacode/tools/ListFiles.java` | Takes a `StopCheck`. Stops between entries. |
| `src/main/java/dev/konacode/tools/ReadFile.java` | Takes a `StopCheck`. Reads in chunks. |
| `src/main/java/dev/konacode/tools/EditFile.java` | Takes a `StopCheck`. Stops before the write, never inside it. |
| `src/main/java/dev/konacode/agent/Agent.java` | Takes a `Cancellation`. Arms, checks, and closes a stopped turn. |
| `src/main/java/dev/konacode/cli/RichUi.java` | Takes an `EscapeWatcher`. Starts and stops it. |
| `src/main/java/dev/konacode/cli/Main.java` | Builds the `Cancellation` and wires it everywhere. |
| `src/main/java/dev/konacode/cli/Commands.java` | `/help` mentions ESC. |
| The tests for each of the above | |

---

## Task 0: Prove the terminal can be read during a turn

This is the one unproved assumption in the design. Do it first. Nothing here is committed.

**Files:**
- Create: `/tmp/EscSpike.java` (throwaway)

- [ ] **Step 1: Write the spike**

```java
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.NonBlockingReader;

public class EscSpike {
    public static void main(String[] args) throws Exception {
        Terminal terminal = TerminalBuilder.builder().system(true).build();
        Attributes saved = terminal.enterRawMode();
        Attributes signals = terminal.getAttributes();
        signals.setLocalFlag(Attributes.LocalFlag.ISIG, true);
        terminal.setAttributes(signals);
        System.out.println("Press ESC within 10 seconds. Ctrl-C must still kill this program.");
        try {
            NonBlockingReader reader = terminal.reader();
            long deadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < deadline) {
                int c = reader.read(200);
                if (c == 27) {
                    System.out.println("\nESC SEEN");
                    return;
                }
            }
            System.out.println("\nNO ESC SEEN");
        } finally {
            terminal.setAttributes(saved);
            terminal.close();
        }
    }
}
```

- [ ] **Step 2: Run it in a real terminal**

```bash
mvn -q dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt
java -cp "$(cat /tmp/cp.txt)" /tmp/EscSpike.java
```

Press ESC. Expected: `ESC SEEN`.
Run it again and press ctrl-C. Expected: the program dies.

- [ ] **Step 3: Decide**

Both work: continue to Task 1.
Either fails: **stop and report.** The fallback is a worker thread for the turn with `readLine` left running, which is a different design and needs a new decision.

- [ ] **Step 4: Delete the spike**

```bash
rm /tmp/EscSpike.java /tmp/cp.txt
```

---

## Task 1: `StopCheck`

**Files:**
- Create: `src/main/java/dev/konacode/tools/StopCheck.java`

- [ ] **Step 1: Write the interface**

There is no test. The interface has one method and one constant, and every later task exercises it.

```java
package dev.konacode.tools;

/**
 * Asks whether the user stopped the turn.
 *
 * <p>A tool that works in many steps reads this between the steps, and returns a
 * {@link ToolResult.Err} that says what it changed before it stopped. The interface lives here
 * and not in {@code dev.konacode.agent} because {@code agent} already depends on {@code tools},
 * so the reverse import would close a cycle.
 */
@FunctionalInterface
public interface StopCheck {

    boolean stopped();

    /** For a tool built without one, and for every test that does not test stopping. */
    StopCheck NEVER = () -> false;
}
```

- [ ] **Step 2: Compile**

Run: `mvn -q compile`
Expected: no output, exit 0.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/dev/konacode/tools/StopCheck.java
git commit -m "feat: add StopCheck, the question a tool asks between steps"
```

---

## Task 2: `Cancellation` — the flag

**Files:**
- Create: `src/main/java/dev/konacode/agent/Cancellation.java`
- Test: `src/test/java/dev/konacode/agent/CancellationTest.java`

- [ ] **Step 1: Write the failing test**

```java
package dev.konacode.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CancellationTest {

    @Test
    void startsUnstopped() {
        assertFalse(new Cancellation().stopped());
    }

    @Test
    void requestStopsIt() {
        Cancellation cancellation = new Cancellation();

        cancellation.request();

        assertTrue(cancellation.stopped());
    }

    @Test
    void clearResetsIt() {
        Cancellation cancellation = new Cancellation();
        cancellation.request();

        cancellation.clear();

        assertFalse(cancellation.stopped());
    }
}
```

- [ ] **Step 2: Run the test to see it fail**

Run: `mvn -q test -Dtest=CancellationTest`
Expected: a compilation failure, `cannot find symbol: class Cancellation`.

- [ ] **Step 3: Write the class**

```java
package dev.konacode.agent;

import dev.konacode.tools.StopCheck;

/**
 * The user's request to stop one turn.
 *
 * <p>{@code request} is public and {@code arm} is not. The user interface may ask for a stop.
 * Only the loop may decide where an interrupt is safe.
 */
public final class Cancellation implements StopCheck {

    private volatile boolean requested;

    public void request() {
        requested = true;
    }

    @Override
    public boolean stopped() {
        return requested;
    }

    public void clear() {
        requested = false;
    }
}
```

- [ ] **Step 4: Run the test to see it pass**

Run: `mvn -q test -Dtest=CancellationTest`
Expected: 0 failures.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/konacode/agent/Cancellation.java src/test/java/dev/konacode/agent/CancellationTest.java
git commit -m "feat: add Cancellation, the flag one turn reads"
```

---

## Task 3: `Cancellation` — arm and disarm

**Files:**
- Modify: `src/main/java/dev/konacode/agent/Cancellation.java`
- Test: `src/test/java/dev/konacode/agent/CancellationTest.java`

- [ ] **Step 1: Write the failing tests**

Add these to `CancellationTest`. `arm` and `disarm` are package private, and the test is in the same package, so it can call them.

```java
    @Test
    void requestInterruptsTheArmedThread() {
        Cancellation cancellation = new Cancellation();
        cancellation.arm();
        try {
            cancellation.request();

            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            cancellation.disarm();
        }
    }

    @Test
    void requestDoesNotInterruptWhenNothingIsArmed() {
        Cancellation cancellation = new Cancellation();

        cancellation.request();

        assertFalse(Thread.interrupted());
    }

    @Test
    void disarmClearsTheInterruptStatus() {
        Cancellation cancellation = new Cancellation();
        cancellation.arm();
        cancellation.request();

        cancellation.disarm();

        assertFalse(Thread.interrupted());
    }

    @Test
    void disarmClearsTheStatusEvenWhenTheRequestRacesIt() throws Exception {
        // Runs the race many times. Without the lock, a request that reads the armed thread just
        // before disarm nulls it delivers the interrupt after the clear, and the status survives.
        for (int attempt = 0; attempt < 2000; attempt++) {
            Cancellation cancellation = new Cancellation();
            cancellation.arm();

            Thread requester = new Thread(cancellation::request);
            requester.start();
            cancellation.disarm();
            requester.join();

            assertFalse(Thread.interrupted(), "the interrupt status leaked on attempt " + attempt);
        }
    }
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `mvn -q test -Dtest=CancellationTest`
Expected: a compilation failure, `cannot find symbol: method arm()`.

- [ ] **Step 3: Write the implementation**

Replace the body of `Cancellation` with this. The class keeps its Javadoc.

```java
package dev.konacode.agent;

import dev.konacode.tools.StopCheck;

/**
 * The user's request to stop one turn.
 *
 * <p>{@code request} is public and {@code arm} is not. The user interface may ask for a stop.
 * Only the loop may decide where an interrupt is safe.
 *
 * <p>The three methods that touch {@code armed} share one lock. Without it, an interrupt sent as
 * the loop disarms arrives after the clear, and the status stays set. A file operation ignores a
 * set status, but the next blocking HTTP send throws at once, so the following turn would fail
 * for no reason.
 */
public final class Cancellation implements StopCheck {

    private final Object lock = new Object();
    private volatile boolean requested;
    private Thread armed;

    public void request() {
        requested = true;
        synchronized (lock) {
            if (armed != null) {
                armed.interrupt();
            }
        }
    }

    @Override
    public boolean stopped() {
        return requested;
    }

    public void clear() {
        requested = false;
    }

    void arm() {
        synchronized (lock) {
            armed = Thread.currentThread();
        }
    }

    void disarm() {
        synchronized (lock) {
            armed = null;
            Thread.interrupted();
        }
    }
}
```

- [ ] **Step 4: Run the tests to see them pass**

Run: `mvn -q test -Dtest=CancellationTest`
Expected: 0 failures.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/konacode/agent/Cancellation.java src/test/java/dev/konacode/agent/CancellationTest.java
git commit -m "feat: arm and disarm the interrupt around one call"
```

---

## Task 4: `Tool.stopsOnInterrupt()`

The method is abstract and not a default, so every implementation must answer it. Six sites answer it, and all six answer `false`. This task does not compile until all six are done.

**Files:**
- Modify: `src/main/java/dev/konacode/tools/Tool.java`
- Modify: `src/main/java/dev/konacode/tools/ListFiles.java`
- Modify: `src/main/java/dev/konacode/tools/ReadFile.java`
- Modify: `src/main/java/dev/konacode/tools/EditFile.java`
- Modify: `src/test/java/dev/konacode/tools/ToolRegistryTest.java`
- Modify: `src/test/java/dev/konacode/agent/AgentTest.java`

- [ ] **Step 1: Add the method to `Tool`**

Add it after `execute` in `src/main/java/dev/konacode/tools/Tool.java`:

```java
    boolean stopsOnInterrupt();
```

- [ ] **Step 2: Run the build to see it fail**

Run: `mvn -q compile`
Expected: `ListFiles is not abstract and does not override abstract method stopsOnInterrupt()`, and the same for `ReadFile` and `EditFile`.

- [ ] **Step 3: Answer it in the three tools**

Add this method to each of `ListFiles`, `ReadFile` and `EditFile`, after `execute`:

```java
    @Override
    public boolean stopsOnInterrupt() {
        return false;
    }
```

- [ ] **Step 4: Answer it in the three test stubs**

Add the same method to `StubTool` in `src/test/java/dev/konacode/tools/ToolRegistryTest.java`, and to `EchoTool` and `ExplodingTool` in `src/test/java/dev/konacode/agent/AgentTest.java`.

- [ ] **Step 5: Run the whole suite**

Run: `mvn test`
Expected: `BUILD SUCCESS`, 0 failures.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/dev/konacode/tools/ src/test/java/dev/konacode/tools/ToolRegistryTest.java src/test/java/dev/konacode/agent/AgentTest.java
git commit -m "feat: a tool declares whether an interrupt stops it safely"
```

---

## Task 5: The loop stops, and closes the turn

**Files:**
- Modify: `src/main/java/dev/konacode/agent/Agent.java`
- Modify: `src/test/java/dev/konacode/agent/FakeLlmClient.java`
- Modify: `src/test/java/dev/konacode/agent/AgentTest.java`
- Modify: `src/main/java/dev/konacode/cli/Main.java`

- [ ] **Step 1: Give `FakeLlmClient` a hook**

Add the field, the setter and the call in `src/test/java/dev/konacode/agent/FakeLlmClient.java`.

Add the field beside `failure`:

```java
    private Runnable beforeReply = () -> {
    };
```

Add the setter after `failWith`:

```java
    /** Runs on the calling thread inside chat, so a test can stop the turn mid-request. */
    FakeLlmClient beforeReply(Runnable hook) {
        this.beforeReply = hook;
        return this;
    }
```

In `chat`, run the hook immediately after `receivedHistories.add(...)`:

```java
        beforeReply.run();
```

- [ ] **Step 2: Write the failing tests**

Add these to `AgentTest`. Put `StoppingTool` beside `EchoTool` at the top of the class.

```java
    /** Stops the turn from inside a tool, which is what an ESC during a tool call does. */
    private record StoppingTool(String name, Cancellation cancellation) implements Tool {
        @Override
        public String description() {
            return "Stops the turn.";
        }

        @Override
        public ObjectNode inputSchema() {
            return Schemas.object().build();
        }

        @Override
        public ToolResult execute(JsonNode args) {
            cancellation.request();
            return ToolResult.ok("ran");
        }

        @Override
        public boolean stopsOnInterrupt() {
            return false;
        }
    }
```

```java
    @Test
    void stopsWhenTheUserStopsDuringTheProviderCall() {
        Cancellation cancellation = new Cancellation();
        FakeLlmClient client = new FakeLlmClient()
                .beforeReply(cancellation::request)
                .reply(new AssistantMessage("", List.of(call("c1", "echo", "{}"))));
        Conversation conversation = new Conversation(new SystemMessage("You are konacode."));
        Agent agent = new Agent(client, ToolRegistry.of(new EchoTool("echo")),
                new AllowAllPolicy(), conversation, new RecordingToolCallListener(),
                cancellation, 8);

        String answer = agent.respond("list the files");

        assertEquals("Stopped.", answer);
        List<Message> messages = conversation.messages();
        assertInstanceOf(ToolMessage.class, messages.get(messages.size() - 2));
        assertEquals(new AssistantMessage("Stopped by the user.", List.of()),
                messages.get(messages.size() - 1));
    }

    @Test
    void answersEveryToolCallThatNeverRan() {
        Cancellation cancellation = new Cancellation();
        FakeLlmClient client = new FakeLlmClient()
                .beforeReply(cancellation::request)
                .reply(new AssistantMessage("",
                        List.of(call("c1", "echo", "{}"), call("c2", "echo", "{}"))));
        Conversation conversation = new Conversation(new SystemMessage("You are konacode."));
        Agent agent = new Agent(client, ToolRegistry.of(new EchoTool("echo")),
                new AllowAllPolicy(), conversation, new RecordingToolCallListener(),
                cancellation, 8);

        agent.respond("do two things");

        List<String> toolMessages = conversation.messages().stream()
                .filter(ToolMessage.class::isInstance)
                .map(message -> ((ToolMessage) message).content())
                .toList();
        assertEquals(2, toolMessages.size());
        assertTrue(toolMessages.get(0).contains("Stopped by the user before this tool ran."));
        assertTrue(toolMessages.get(1).contains("Stopped by the user before this tool ran."));
    }

    @Test
    void runsTheToolThatStartedAndStopsBeforeTheNextOne() {
        Cancellation cancellation = new Cancellation();
        FakeLlmClient client = new FakeLlmClient()
                .reply(new AssistantMessage("",
                        List.of(call("c1", "stop", "{}"), call("c2", "echo", "{}"))));
        Conversation conversation = new Conversation(new SystemMessage("You are konacode."));
        RecordingToolCallListener listener = new RecordingToolCallListener();
        Agent agent = new Agent(client,
                ToolRegistry.of(new StoppingTool("stop", cancellation), new EchoTool("echo")),
                new AllowAllPolicy(), conversation, listener, cancellation, 8);

        assertEquals("Stopped.", agent.respond("do two things"));

        List<String> toolMessages = conversation.messages().stream()
                .filter(ToolMessage.class::isInstance)
                .map(message -> ((ToolMessage) message).content())
                .toList();
        assertEquals(2, toolMessages.size());
        assertEquals("ran", toolMessages.get(0));
        assertTrue(toolMessages.get(1).contains("Stopped by the user before this tool ran."));
    }

    @Test
    void treatsAnAbortedRequestAsAStopAndNotAFailure() {
        Cancellation cancellation = new Cancellation();
        FakeLlmClient client = new FakeLlmClient()
                .beforeReply(cancellation::request)
                .failWith(new LlmException("Request was interrupted."));
        Conversation conversation = new Conversation(new SystemMessage("You are konacode."));
        Agent agent = new Agent(client, ToolRegistry.of(new EchoTool("echo")),
                new AllowAllPolicy(), conversation, new RecordingToolCallListener(),
                cancellation, 8);

        assertEquals("Stopped.", agent.respond("hello"));
    }

    @Test
    void clearsTheStopBeforeEachTurn() {
        Cancellation cancellation = new Cancellation();
        cancellation.request();
        FakeLlmClient client = new FakeLlmClient().replyText("hello");
        Conversation conversation = new Conversation(new SystemMessage("You are konacode."));
        Agent agent = new Agent(client, ToolRegistry.of(new EchoTool("echo")),
                new AllowAllPolicy(), conversation, new RecordingToolCallListener(),
                cancellation, 8);

        assertEquals("hello", agent.respond("hello"));
    }
```

Update the existing `agent(...)` helper in `AgentTest` so the other tests still compile:

```java
    private Agent agent(FakeLlmClient client, ToolRegistry registry, ToolPolicy policy,
                        RecordingToolCallListener listener, int maxIterations) {
        return new Agent(
                client,
                registry,
                policy,
                new Conversation(new SystemMessage("You are konacode.")),
                listener,
                new Cancellation(),
                maxIterations);
    }
```

- [ ] **Step 3: Run the tests to see them fail**

Run: `mvn -q test -Dtest=AgentTest`
Expected: a compilation failure, `constructor Agent cannot be applied to given types`.

- [ ] **Step 4: Change `Agent`**

Add the field, the constructor parameter, and replace `respond`. No new import is needed: the new code uses `List.subList`.

Add the field beside `listener`:

```java
    private final Cancellation cancellation;
```

Add the parameter to the constructor, before `maxIterations`, and assign it:

```java
    public Agent(LlmClient client,
                 ToolRegistry registry,
                 ToolPolicy policy,
                 Conversation conversation,
                 ToolCallListener listener,
                 Cancellation cancellation,
                 int maxIterations) {
        this.client = Objects.requireNonNull(client, "client");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.conversation = Objects.requireNonNull(conversation, "conversation");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
        if (maxIterations < 1) {
            throw new IllegalArgumentException("maxIterations must be at least 1.");
        }
        this.maxIterations = maxIterations;
    }
```

Replace `respond` with this, and add `stopped` beside `fail`:

```java
    public String respond(String userText) {
        cancellation.clear();
        conversation.add(new UserMessage(userText));
        List<ToolSpec> tools = ToolSpecs.from(registry);
        try {
            for (int iteration = 0; iteration < maxIterations; iteration++) {
                AssistantMessage reply = client.chat(conversation.messages(), tools);

                // Before running anything: providers reject a tool result whose originating
                // assistant message is absent from the history.
                conversation.add(reply);

                if (!reply.hasToolCalls()) {
                    return reply.text();
                }

                List<ToolCall> calls = reply.toolCalls();
                for (int index = 0; index < calls.size(); index++) {
                    if (cancellation.stopped()) {
                        return stopped(calls.subList(index, calls.size()));
                    }
                    ToolCall call = calls.get(index);
                    ToolResult result = perform(call);
                    conversation.add(new ToolMessage(call.id(), result.render()));
                }

                if (cancellation.stopped()) {
                    return stopped(List.of());
                }
            }
            return fail("<error> Exceeded maximum tool iterations.");
        } catch (LlmException e) {
            if (cancellation.stopped()) {
                return stopped(List.of());
            }
            return fail("<error> " + e.getMessage());
        }
    }

    /**
     * Closes a stopped turn.
     *
     * <p>The history keeps the whole turn, so the model can read what it did and reverse it when
     * the user asks. Every tool call that never ran is answered here, because a provider rejects
     * a conversation where a call has no result.
     */
    private String stopped(List<ToolCall> unanswered) {
        for (ToolCall call : unanswered) {
            conversation.add(new ToolMessage(call.id(),
                    ToolResult.err("Stopped by the user before this tool ran.").render()));
        }
        conversation.add(new AssistantMessage("Stopped by the user.", List.of()));
        return "Stopped.";
    }
```

- [ ] **Step 5: Fix `Main`**

In `src/main/java/dev/konacode/cli/Main.java`, add the import `dev.konacode.agent.Cancellation`, build one instance before the agent, and pass it:

```java
        Cancellation cancellation = new Cancellation();

        Agent agent = new Agent(new OpenAiClient(config), registry, new AllowAllPolicy(),
                conversation, ui, cancellation, maxIterations);
```

- [ ] **Step 6: Run the whole suite**

Run: `mvn test`
Expected: `BUILD SUCCESS`, 0 failures.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/dev/konacode/agent/Agent.java src/main/java/dev/konacode/cli/Main.java src/test/java/dev/konacode/agent/
git commit -m "feat: the loop stops the turn and closes it honestly"
```

---

## Task 6: Arm the interrupt around the provider call

**Files:**
- Modify: `src/main/java/dev/konacode/agent/Agent.java`
- Modify: `src/test/java/dev/konacode/agent/AgentTest.java`

- [ ] **Step 1: Write the failing tests**

```java
    @Test
    void interruptsTheThreadThatWaitsForTheProvider() {
        Cancellation cancellation = new Cancellation();
        boolean[] wasInterrupted = {false};
        FakeLlmClient client = new FakeLlmClient()
                .beforeReply(() -> {
                    cancellation.request();
                    wasInterrupted[0] = Thread.currentThread().isInterrupted();
                })
                .replyText("hello");
        Agent agent = new Agent(client, ToolRegistry.of(new EchoTool("echo")),
                new AllowAllPolicy(), new Conversation(new SystemMessage("You are konacode.")),
                new RecordingToolCallListener(), cancellation, 8);

        agent.respond("hello");

        assertTrue(wasInterrupted[0], "the thread inside chat must be interrupted");
    }

    @Test
    void leavesNoInterruptBehindAfterAStop() {
        Cancellation cancellation = new Cancellation();
        FakeLlmClient client = new FakeLlmClient()
                .beforeReply(cancellation::request)
                .replyText("hello");
        Agent agent = new Agent(client, ToolRegistry.of(new EchoTool("echo")),
                new AllowAllPolicy(), new Conversation(new SystemMessage("You are konacode.")),
                new RecordingToolCallListener(), cancellation, 8);

        agent.respond("hello");

        assertFalse(Thread.interrupted(), "disarm must clear the interrupt status");
    }
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `mvn -q test -Dtest=AgentTest`
Expected: `interruptsTheThreadThatWaitsForTheProvider` fails, "the thread inside chat must be interrupted".

- [ ] **Step 3: Wrap the call**

In `Agent.respond`, replace the line

```java
                AssistantMessage reply = client.chat(conversation.messages(), tools);
```

with

```java
                AssistantMessage reply = chat(tools);
```

and add this private method beside `stopped`:

```java
    /**
     * Arms the interrupt for the length of the provider call and no longer.
     *
     * <p>The finally makes the window exactly this call, whether it returns or throws. A tool
     * that runs afterwards is never interrupted by accident.
     */
    private AssistantMessage chat(List<ToolSpec> tools) {
        cancellation.arm();
        try {
            return client.chat(conversation.messages(), tools);
        } finally {
            cancellation.disarm();
        }
    }
```

- [ ] **Step 4: Run the tests to see them pass**

Run: `mvn -q test -Dtest=AgentTest`
Expected: 0 failures.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/konacode/agent/Agent.java src/test/java/dev/konacode/agent/AgentTest.java
git commit -m "feat: abort the in-flight provider call on a stop"
```

---

## Task 7: Arm the interrupt around a tool that asks for it

**Files:**
- Modify: `src/main/java/dev/konacode/agent/Agent.java`
- Modify: `src/test/java/dev/konacode/agent/AgentTest.java`

- [ ] **Step 1: Write the failing tests**

Add this tool beside `StoppingTool`:

```java
    /** Declares that an interrupt is safe, and records whether the loop delivered one. */
    private static final class InterruptibleTool implements Tool {
        private final Cancellation cancellation;
        private final boolean declares;
        boolean sawInterrupt;

        InterruptibleTool(Cancellation cancellation, boolean declares) {
            this.cancellation = cancellation;
            this.declares = declares;
        }

        @Override
        public String name() {
            return "blocking";
        }

        @Override
        public String description() {
            return "Waits.";
        }

        @Override
        public ObjectNode inputSchema() {
            return Schemas.object().build();
        }

        @Override
        public ToolResult execute(JsonNode args) {
            cancellation.request();
            sawInterrupt = Thread.currentThread().isInterrupted();
            return ToolResult.err("Stopped by the user. Nothing was changed.");
        }

        @Override
        public boolean stopsOnInterrupt() {
            return declares;
        }
    }
```

```java
    @Test
    void armsAroundAToolThatStopsOnInterrupt() {
        Cancellation cancellation = new Cancellation();
        InterruptibleTool tool = new InterruptibleTool(cancellation, true);
        FakeLlmClient client = new FakeLlmClient()
                .reply(new AssistantMessage("", List.of(call("c1", "blocking", "{}"))));
        Agent agent = new Agent(client, ToolRegistry.of(tool), new AllowAllPolicy(),
                new Conversation(new SystemMessage("You are konacode.")),
                new RecordingToolCallListener(), cancellation, 8);

        agent.respond("fetch it");

        assertTrue(tool.sawInterrupt, "a tool that declares stopsOnInterrupt must be armed");
        assertFalse(Thread.interrupted(), "disarm must clear the interrupt status");
    }

    @Test
    void doesNotArmAroundAToolThatDoesNotStopOnInterrupt() {
        Cancellation cancellation = new Cancellation();
        InterruptibleTool tool = new InterruptibleTool(cancellation, false);
        FakeLlmClient client = new FakeLlmClient()
                .reply(new AssistantMessage("", List.of(call("c1", "blocking", "{}"))));
        Agent agent = new Agent(client, ToolRegistry.of(tool), new AllowAllPolicy(),
                new Conversation(new SystemMessage("You are konacode.")),
                new RecordingToolCallListener(), cancellation, 8);

        agent.respond("fetch it");

        assertFalse(tool.sawInterrupt, "a tool that does not declare it must never be interrupted");
    }
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `mvn -q test -Dtest=AgentTest`
Expected: `armsAroundAToolThatStopsOnInterrupt` fails, "a tool that declares stopsOnInterrupt must be armed".

- [ ] **Step 3: Branch in `run`**

In `Agent.run`, replace

```java
        try {
            return tool.execute(args);
        } catch (RuntimeException e) {
            // A misbehaving tool must not kill the session.
            return ToolResult.err("Tool " + call.name() + " failed: " + e);
        }
```

with

```java
        try {
            return execute(tool, args);
        } catch (RuntimeException e) {
            // A misbehaving tool must not kill the session.
            return ToolResult.err("Tool " + call.name() + " failed: " + e);
        }
```

and add this method beside `chat`:

```java
    /**
     * Arms the interrupt only for a tool that says an interrupt is safe for it.
     *
     * <p>A tool that says nothing is never interrupted. Arming every tool would rest the safety
     * of the loop on every tool author writing correct cleanup, for ever.
     */
    private ToolResult execute(Tool tool, JsonNode args) {
        if (!tool.stopsOnInterrupt()) {
            return tool.execute(args);
        }
        cancellation.arm();
        try {
            return tool.execute(args);
        } finally {
            cancellation.disarm();
        }
    }
```

- [ ] **Step 4: Run the tests to see them pass**

Run: `mvn -q test -Dtest=AgentTest`
Expected: 0 failures.

- [ ] **Step 5: Run the whole suite**

Run: `mvn test`
Expected: `BUILD SUCCESS`, 0 failures.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/dev/konacode/agent/Agent.java src/test/java/dev/konacode/agent/AgentTest.java
git commit -m "feat: arm the interrupt around a tool that declares it safe"
```

---

## Task 8: `ListFiles` stops between entries

**Files:**
- Modify: `src/main/java/dev/konacode/tools/Workspace.java`
- Modify: `src/main/java/dev/konacode/tools/ListFiles.java`
- Modify: `src/test/java/dev/konacode/tools/ListFilesTest.java`

- [ ] **Step 1: Write the failing test**

Add to `ListFilesTest`:

```java
    @Test
    void stopsBetweenEntriesAndReportsThatNothingChanged() throws IOException {
        Files.createFile(root.resolve("a.txt"));
        Files.createFile(root.resolve("b.txt"));
        ListFiles stopping = new ListFiles(new Workspace(root), () -> true);

        ToolResult result = stopping.execute(args("."));

        ToolResult.Err error = assertInstanceOf(ToolResult.Err.class, result);
        assertTrue(error.message().startsWith("Stopped by the user"), error.message());
        assertTrue(error.message().contains("Nothing was changed."), error.message());
    }
```

Add the imports `assertInstanceOf` and `assertTrue` from `org.junit.jupiter.api.Assertions` if the file does not have them. Change the existing `setUp` so the tool takes a `StopCheck`:

```java
    @BeforeEach
    void setUp() {
        tool = new ListFiles(new Workspace(root), StopCheck.NEVER);
    }
```

- [ ] **Step 2: Run the test to see it fail**

Run: `mvn -q test -Dtest=ListFilesTest`
Expected: a compilation failure, `constructor ListFiles cannot be applied to given types`.

- [ ] **Step 3: Change `Workspace.listSorted`**

Replace `listSorted` in `src/main/java/dev/konacode/tools/Workspace.java` with these two methods. Add the import `java.util.ArrayList`.

```java
    public List<Path> listSorted(Path directory) throws IOException {
        return listSorted(directory, StopCheck.NEVER);
    }

    /**
     * Reads the entries, stopping between two of them when the user asks. The caller sees a short
     * list and must ask the {@link StopCheck} itself to know whether the list is complete.
     */
    public List<Path> listSorted(Path directory, StopCheck stop) throws IOException {
        // Locale.ROOT pins the collation rules so listing order does not vary between a
        // developer's machine and CI, which may run under a different default locale.
        Collator collator = Collator.getInstance(Locale.ROOT);
        List<Path> entries = new ArrayList<>();
        try (Stream<Path> stream = Files.list(directory)) {
            for (Path entry : (Iterable<Path>) stream::iterator) {
                if (stop.stopped()) {
                    break;
                }
                entries.add(entry);
            }
        }
        entries.sort(Comparator.comparing(path -> path.getFileName().toString(), collator));
        return entries;
    }
```

- [ ] **Step 4: Change `ListFiles`**

Add the field and the constructor parameter:

```java
    private final Workspace workspace;
    private final StopCheck stop;

    public ListFiles(Workspace workspace, StopCheck stop) {
        this.workspace = workspace;
        this.stop = stop;
    }
```

Replace the final `try` block of `execute`:

```java
        try {
            List<Path> entries = workspace.listSorted(target, stop);
            if (stop.stopped()) {
                return ToolResult.err("Stopped by the user after " + entries.size()
                        + " entries. Nothing was changed.");
            }
            return ToolResult.ok(render(target, entries));
        } catch (IOException e) {
            return ToolResult.err("Could not list path " + target + ". " + e);
        }
```

Change `render` so it takes the entries and no longer reads the disk:

```java
    private String render(Path directory, List<Path> entries) {
        List<String> lines = new ArrayList<>();
        lines.add("directory " + directory);

        if (entries.isEmpty()) {
            lines.add("<empty>");
            return String.join("\n", lines);
        }

        entries.stream().limit(ENTRY_LIMIT).map(ListFiles::format).forEach(lines::add);
        if (entries.size() > ENTRY_LIMIT) {
            lines.add("… " + (entries.size() - ENTRY_LIMIT) + " more");
        }
        return String.join("\n", lines);
    }
```

- [ ] **Step 5: Fix the other caller**

`src/test/java/dev/konacode/cli/CommandsTest.java` builds the tools in its `commands` helper.
Change the `ListFiles` line, and add the import `dev.konacode.tools.StopCheck`:

```java
                ToolRegistry.of(new ListFiles(workspace, StopCheck.NEVER),
                        new ReadFile(workspace)), ui);
```

- [ ] **Step 6: Run the tests to see them pass**

Run: `mvn -q test -Dtest=ListFilesTest+WorkspaceTest+CommandsTest`
Expected: 0 failures.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/dev/konacode/tools/Workspace.java src/main/java/dev/konacode/tools/ListFiles.java src/test/java/dev/konacode/tools/ListFilesTest.java src/test/java/dev/konacode/cli/CommandsTest.java
git commit -m "feat: ListFiles stops between entries"
```

---

## Task 9: `ReadFile` stops between chunks

**Files:**
- Modify: `src/main/java/dev/konacode/tools/Workspace.java`
- Modify: `src/main/java/dev/konacode/tools/ReadFile.java`
- Modify: `src/test/java/dev/konacode/tools/ReadFileTest.java`

- [ ] **Step 1: Write the failing test**

Add to `ReadFileTest`:

```java
    @Test
    void stopsBetweenChunksAndReportsThatTheFileIsUnchanged() throws IOException {
        Files.writeString(root.resolve("big.txt"), "x".repeat(50_000), StandardCharsets.UTF_8);
        ReadFile stopping = new ReadFile(new Workspace(root), () -> true);

        ToolResult result = stopping.execute(args("big.txt"));

        ToolResult.Err error = assertInstanceOf(ToolResult.Err.class, result);
        assertTrue(error.message().startsWith("Stopped by the user"), error.message());
        assertTrue(error.message().contains("The file was not changed."), error.message());
        assertEquals(50_000, Files.size(root.resolve("big.txt")));
    }
```

Change `setUp`:

```java
    @BeforeEach
    void setUp() {
        tool = new ReadFile(new Workspace(root), StopCheck.NEVER);
    }
```

- [ ] **Step 2: Run the test to see it fail**

Run: `mvn -q test -Dtest=ReadFileTest`
Expected: a compilation failure, `constructor ReadFile cannot be applied to given types`.

- [ ] **Step 3: Change `Workspace.readUtf8Capped`**

Replace it with these two methods. Add the import `java.io.ByteArrayOutputStream`.

```java
    public String readUtf8Capped(Path file, int maxBytes) throws IOException {
        return readUtf8Capped(file, maxBytes, StopCheck.NEVER);
    }

    /**
     * Reads at most {@code maxBytes} and decodes UTF-8 leniently. Decoding strictly would fail
     * outright whenever the cap lands mid-codepoint, and report a truncated text file as binary.
     *
     * <p>The read happens in chunks so the user can stop it. A stopped read returns what it has,
     * and the caller asks the {@link StopCheck} itself to know that the text is short.
     */
    public String readUtf8Capped(Path file, int maxBytes, StopCheck stop) throws IOException {
        ByteArrayOutputStream collected = new ByteArrayOutputStream();
        byte[] chunk = new byte[CHUNK_BYTES];
        try (InputStream in = Files.newInputStream(file)) {
            while (collected.size() < maxBytes && !stop.stopped()) {
                int wanted = Math.min(chunk.length, maxBytes - collected.size());
                int read = in.read(chunk, 0, wanted);
                if (read < 0) {
                    break;
                }
                collected.write(chunk, 0, read);
            }
        }
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        return decoder.decode(ByteBuffer.wrap(collected.toByteArray())).toString();
    }
```

Add the constant beside the `root` field:

```java
    private static final int CHUNK_BYTES = 8192;
```

- [ ] **Step 4: Change `ReadFile`**

Add the field and the constructor parameter:

```java
    private final Workspace workspace;
    private final StopCheck stop;

    public ReadFile(Workspace workspace, StopCheck stop) {
        this.workspace = workspace;
        this.stop = stop;
    }
```

Replace the final `try` block of `execute`:

```java
        try {
            String text = workspace.readUtf8Capped(file, MAX_BYTES, stop);
            if (stop.stopped()) {
                return ToolResult.err("Stopped by the user after " + text.length()
                        + " characters. The file was not changed.");
            }
            return ToolResult.ok(text);
        } catch (IOException e) {
            return ToolResult.err("Could not read file at path: " + pathNode.asText() + ". " + e);
        }
```

- [ ] **Step 5: Fix the other caller**

In `src/test/java/dev/konacode/cli/CommandsTest.java`, finish the `commands` helper:

```java
                ToolRegistry.of(new ListFiles(workspace, StopCheck.NEVER),
                        new ReadFile(workspace, StopCheck.NEVER)), ui);
```

- [ ] **Step 6: Run the tests to see them pass**

Run: `mvn -q test -Dtest=ReadFileTest+WorkspaceTest+CommandsTest`
Expected: 0 failures.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/dev/konacode/tools/Workspace.java src/main/java/dev/konacode/tools/ReadFile.java src/test/java/dev/konacode/tools/ReadFileTest.java src/test/java/dev/konacode/cli/CommandsTest.java
git commit -m "feat: ReadFile stops between chunks"
```

---

## Task 10: `EditFile` stops before the write and never inside it

**Files:**
- Modify: `src/main/java/dev/konacode/tools/EditFile.java`
- Modify: `src/test/java/dev/konacode/tools/EditFileTest.java`

- [ ] **Step 1: Write the failing tests**

Add to `EditFileTest`:

```java
    @Test
    void stopsBeforeTheWriteAndLeavesTheFileUnchanged() throws IOException {
        Path file = root.resolve("notes.txt");
        Files.writeString(file, "one two three", StandardCharsets.UTF_8);
        EditFile stopping = new EditFile(new Workspace(root), () -> true);

        ToolResult result = stopping.execute(args("notes.txt", "two", "TWO"));

        ToolResult.Err error = assertInstanceOf(ToolResult.Err.class, result);
        assertEquals("Stopped by the user before the write. The file was not changed.",
                error.message());
        assertEquals("one two three", Files.readString(file));
    }

    @Test
    void stopsBeforeCreatingAFile() {
        EditFile stopping = new EditFile(new Workspace(root), () -> true);

        ToolResult result = stopping.execute(args("new.txt", "", "content"));

        ToolResult.Err error = assertInstanceOf(ToolResult.Err.class, result);
        assertEquals("Stopped by the user before the write. The file was not changed.",
                error.message());
        assertFalse(Files.exists(root.resolve("new.txt")));
    }
```

Change `setUp`:

```java
    @BeforeEach
    void setUp() {
        tool = new EditFile(new Workspace(root), StopCheck.NEVER);
    }
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `mvn -q test -Dtest=EditFileTest`
Expected: a compilation failure, `constructor EditFile cannot be applied to given types`.

- [ ] **Step 3: Change `EditFile`**

Add the field and the constructor parameter:

```java
    private final Workspace workspace;
    private final StopCheck stop;

    public EditFile(Workspace workspace, StopCheck stop) {
        this.workspace = workspace;
        this.stop = stop;
    }
```

Add the constant beside `MAX_EDITABLE_BYTES`:

```java
    private static final String STOPPED =
            "Stopped by the user before the write. The file was not changed.";
```

In `create`, check before the write:

```java
        if (stop.stopped()) {
            return ToolResult.err(STOPPED);
        }
        try {
            workspace.writeAtomic(file, newStr);
            return ToolResult.ok(success("created file " + rawPath, newStr));
        } catch (IOException e) {
            return ToolResult.err("Could not create file at path: " + rawPath + ". " + e);
        }
```

In `update`, check after the read and again before the write. The second check is the one that
matters, and it is the last statement before `writeAtomic`:

```java
        try {
            String original = workspace.readUtf8ForEditing(file, MAX_EDITABLE_BYTES);
            if (stop.stopped()) {
                return ToolResult.err(STOPPED);
            }
            int matches = countOccurrences(original, oldStr);

            if (matches == 0) {
                return ToolResult.err("old_str not found in " + rawPath + ".");
            }
            if (matches > 1) {
                return ToolResult.err(
                        "old_str must match exactly one occurrence. Found " + matches + ".");
            }

            // String.replace, never replaceAll: replaceAll would treat old_str as a regex and
            // new_str as a replacement template, so a $ or \ would corrupt the edit.
            String updated = original.replace(oldStr, newStr);
            if (stop.stopped()) {
                return ToolResult.err(STOPPED);
            }
            workspace.writeAtomic(file, updated);
            return ToolResult.ok(success("updated file " + rawPath, updated));
        } catch (IOException e) {
            return ToolResult.err("Could not edit file at path: " + rawPath + ". " + e);
        }
```

Never add a check inside `writeAtomic`. The guarantee is that the edit is fully applied or the
file is untouched, and it holds only because no check sits between the write and the move.

- [ ] **Step 4: Run the tests to see them pass**

Run: `mvn -q test -Dtest=EditFileTest`
Expected: 0 failures.

- [ ] **Step 5: Run the whole suite**

Run: `mvn test`
Expected: a compilation failure in `Main`, `constructor ListFiles cannot be applied to given types`.

- [ ] **Step 6: Fix `Main`**

Move the `Cancellation` above the registry and pass it to the three tools:

```java
        Cancellation cancellation = new Cancellation();
        Workspace workspace = Workspace.ofCurrentDirectory();
        ToolRegistry registry = ToolRegistry.of(
                new ListFiles(workspace, cancellation),
                new ReadFile(workspace, cancellation),
                new EditFile(workspace, cancellation));
```

- [ ] **Step 7: Run the whole suite**

Run: `mvn test`
Expected: `BUILD SUCCESS`, 0 failures.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/dev/konacode/tools/EditFile.java src/main/java/dev/konacode/cli/Main.java src/test/java/dev/konacode/tools/EditFileTest.java
git commit -m "feat: EditFile is fully applied or untouched, and says which"
```

---

## Task 11: `EscapeWatcher` — the read loop

The loop is a static method, so the test drives it directly and no test depends on thread timing.

**Files:**
- Create: `src/main/java/dev/konacode/cli/EscapeWatcher.java`
- Create: `src/test/java/dev/konacode/cli/EscapeWatcherTest.java`

- [ ] **Step 1: Write the failing test**

```java
package dev.konacode.cli;

import dev.konacode.agent.Cancellation;
import org.jline.utils.NonBlockingReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EscapeWatcherTest {

    @Mock
    NonBlockingReader reader;

    @Test
    void escapeStopsTheTurn() throws IOException {
        when(reader.read(anyLong())).thenReturn(27);
        Cancellation cancellation = new Cancellation();

        EscapeWatcher.watch(reader, cancellation, () -> true);

        assertTrue(cancellation.stopped());
    }

    @Test
    void keepsPollingWhileTheReadExpires() throws IOException {
        when(reader.read(anyLong()))
                .thenReturn(NonBlockingReader.READ_EXPIRED)
                .thenReturn(NonBlockingReader.READ_EXPIRED)
                .thenReturn(27);
        Cancellation cancellation = new Cancellation();

        EscapeWatcher.watch(reader, cancellation, () -> true);

        assertTrue(cancellation.stopped());
    }

    @Test
    void endsOfItsOwnAccordAtEndOfInput() throws IOException {
        when(reader.read(anyLong())).thenReturn(NonBlockingReader.EOF);
        Cancellation cancellation = new Cancellation();

        EscapeWatcher.watch(reader, cancellation, () -> true);

        assertFalse(cancellation.stopped());
    }

    @Test
    void endsWhenItIsNoLongerRunning() throws IOException {
        Cancellation cancellation = new Cancellation();

        EscapeWatcher.watch(reader, cancellation, () -> false);

        assertFalse(cancellation.stopped());
    }

    @Test
    void aReadFailureEndsTheLoopQuietly() throws IOException {
        when(reader.read(anyLong())).thenThrow(new IOException("the terminal went away"));
        Cancellation cancellation = new Cancellation();

        EscapeWatcher.watch(reader, cancellation, () -> true);

        assertFalse(cancellation.stopped());
    }
}
```

- [ ] **Step 2: Run the test to see it fail**

Run: `mvn -q test -Dtest=EscapeWatcherTest`
Expected: a compilation failure, `cannot find symbol: class EscapeWatcher`.

- [ ] **Step 3: Write the class with the loop only**

```java
package dev.konacode.cli;

import dev.konacode.agent.Cancellation;
import org.jline.utils.NonBlockingReader;

import java.io.IOException;
import java.util.function.BooleanSupplier;

/**
 * Reads the terminal while the agent works, and stops the turn when the user presses ESC.
 *
 * <p>ESC is not a signal. It is the byte 0x1B on standard input, so something must read the
 * terminal during a turn. This is a sibling of {@link Spinner}: one daemon thread, start and
 * stop, both idempotent. It is not final, so a test can subclass it and record the calls.
 */
public class EscapeWatcher {

    static final int ESCAPE = 27;
    private static final long POLL_MILLIS = 100;

    /**
     * Reads until ESC arrives, the input ends, or the watcher is stopped.
     *
     * <p>Any 0x1B stops the turn, including the first byte of an arrow key sequence. During a
     * turn there is no line to edit, so this is correct, and one ESC press then works with no
     * timeout.
     */
    static void watch(NonBlockingReader reader, Cancellation cancellation, BooleanSupplier running) {
        try {
            while (running.getAsBoolean()) {
                int c = reader.read(POLL_MILLIS);
                if (c == ESCAPE) {
                    cancellation.request();
                    return;
                }
                if (c == NonBlockingReader.EOF) {
                    return;
                }
            }
        } catch (IOException e) {
            // The terminal went away. The turn is no longer stoppable, and saying so on the
            // screen would corrupt the output the agent is writing.
        }
    }
}
```

- [ ] **Step 4: Run the test to see it pass**

Run: `mvn -q test -Dtest=EscapeWatcherTest`
Expected: 0 failures.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/konacode/cli/EscapeWatcher.java src/test/java/dev/konacode/cli/EscapeWatcherTest.java
git commit -m "feat: read the terminal for ESC during a turn"
```

---

## Task 12: `EscapeWatcher` — start, stop and raw mode

**Files:**
- Modify: `src/main/java/dev/konacode/cli/EscapeWatcher.java`
- Modify: `src/test/java/dev/konacode/cli/EscapeWatcherTest.java`

- [ ] **Step 1: Write the failing tests**

Add these to `EscapeWatcherTest`, with the extra imports.

```java
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;

import static org.mockito.Mockito.verify;
```

```java
    @Mock
    Terminal terminal;

    @Test
    void startEntersRawModeAndKeepsSignals() throws Exception {
        Attributes saved = new Attributes();
        Attributes raw = new Attributes();
        when(terminal.enterRawMode()).thenReturn(saved);
        when(terminal.getAttributes()).thenReturn(raw);
        when(terminal.reader()).thenReturn(reader);
        when(reader.read(anyLong())).thenReturn(NonBlockingReader.READ_EXPIRED);
        EscapeWatcher watcher = new EscapeWatcher(terminal, new Cancellation());

        watcher.start();
        try {
            assertTrue(raw.getLocalFlag(Attributes.LocalFlag.ISIG),
                    "ctrl-C must still end konacode");
            verify(terminal).setAttributes(raw);
        } finally {
            watcher.stop();
        }

        verify(terminal).setAttributes(saved);
    }

    @Test
    void stopWithoutStartDoesNothing() {
        new EscapeWatcher(terminal, new Cancellation()).stop();
    }
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `mvn -q test -Dtest=EscapeWatcherTest`
Expected: a compilation failure, `constructor EscapeWatcher cannot be applied to given types`.

- [ ] **Step 3: Add the fields, the constructor, start and stop**

Add these to `EscapeWatcher`, and the imports `org.jline.terminal.Attributes`,
`org.jline.terminal.Terminal` and `java.util.concurrent.atomic.AtomicBoolean`.

```java
    private final Terminal terminal;
    private final Cancellation cancellation;
    private final AtomicBoolean running = new AtomicBoolean();
    private Thread thread;
    private Attributes saved;

    public EscapeWatcher(Terminal terminal, Cancellation cancellation) {
        this.terminal = terminal;
        this.cancellation = cancellation;
    }

    /**
     * Enters raw mode so one keystroke arrives without a newline, and turns signal generation
     * back on so ctrl-C still ends konacode.
     */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        saved = terminal.enterRawMode();
        Attributes signals = terminal.getAttributes();
        signals.setLocalFlag(Attributes.LocalFlag.ISIG, true);
        terminal.setAttributes(signals);

        thread = new Thread(() -> watch(terminal.reader(), cancellation, running::get),
                "konacode-escape");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        try {
            thread.join(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        terminal.setAttributes(saved);
    }
```

The thread is never interrupted. It leaves within one poll, and interrupting it would risk the
interrupt landing on work that follows.

- [ ] **Step 4: Run the tests to see them pass**

Run: `mvn -q test -Dtest=EscapeWatcherTest`
Expected: 0 failures.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/konacode/cli/EscapeWatcher.java src/test/java/dev/konacode/cli/EscapeWatcherTest.java
git commit -m "feat: enter raw mode for a turn and restore it after"
```

---

## Task 13: `RichUi` starts and stops the watcher

**Files:**
- Modify: `src/main/java/dev/konacode/cli/RichUi.java`
- Modify: `src/test/java/dev/konacode/cli/RichUiTest.java`

- [ ] **Step 1: Write the failing tests**

Add the recording double beside `RecordingSpinner` in `RichUiTest`:

```java
    /** EscapeWatcher is our own type, so the double is hand-written. */
    static final class RecordingEscapeWatcher extends EscapeWatcher {
        final List<String> calls = new ArrayList<>();

        RecordingEscapeWatcher(Terminal terminal) {
            super(terminal, new dev.konacode.agent.Cancellation());
        }

        @Override
        public void start() {
            calls.add("start");
        }

        @Override
        public void stop() {
            calls.add("stop");
        }
    }
```

Add the tests. Follow the way the file already builds a `RichUi`, and pass the watcher as the
last constructor argument.

```java
    @Test
    void thinkingStartsTheSpinnerAndTheWatcher() {
        RecordingSpinner spinner = new RecordingSpinner();
        RecordingEscapeWatcher watcher = new RecordingEscapeWatcher(terminal);
        RichUi ui = new RichUi(reader, terminal, new PrintStream(new ByteArrayOutputStream()),
                spinner, watcher);

        ui.thinking();

        assertEquals(List.of("start"), spinner.calls);
        assertEquals(List.of("start"), watcher.calls);
    }

    @Test
    void showAnswerStopsTheSpinnerAndTheWatcher() {
        RecordingSpinner spinner = new RecordingSpinner();
        RecordingEscapeWatcher watcher = new RecordingEscapeWatcher(terminal);
        RichUi ui = new RichUi(reader, terminal, new PrintStream(new ByteArrayOutputStream()),
                spinner, watcher);

        ui.showAnswer("done");

        assertEquals(List.of("stop"), spinner.calls);
        assertEquals(List.of("stop"), watcher.calls);
    }

    @Test
    void aToolCallStopsTheSpinnerAndLeavesTheWatcherRunning() {
        RecordingSpinner spinner = new RecordingSpinner();
        RecordingEscapeWatcher watcher = new RecordingEscapeWatcher(terminal);
        RichUi ui = new RichUi(reader, terminal, new PrintStream(new ByteArrayOutputStream()),
                spinner, watcher);

        ui.onToolCall("read_file", "{}");

        assertEquals(List.of("stop"), spinner.calls);
        assertEquals(List.of(), watcher.calls);
    }
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `mvn -q test -Dtest=RichUiTest`
Expected: a compilation failure, `constructor RichUi cannot be applied to given types`.

- [ ] **Step 3: Change `RichUi`**

Add the field and the constructor parameter:

```java
    private final Spinner spinner;
    private final EscapeWatcher watcher;

    RichUi(LineReader reader, Terminal terminal, PrintStream out, Spinner spinner,
           EscapeWatcher watcher) {
        this.reader = reader;
        this.terminal = terminal;
        this.out = out;
        this.spinner = spinner;
        this.watcher = watcher;
    }
```

Change `open` to take the `Cancellation` and build the watcher. Add the import
`dev.konacode.agent.Cancellation`.

```java
    static RichUi open(Cancellation cancellation) throws IOException {
```

and its last line:

```java
        return new RichUi(reader, terminal, System.out, new Spinner(System.out, "thinking"),
                new EscapeWatcher(terminal, cancellation));
```

Start and stop the watcher beside the spinner:

```java
    @Override
    public void showAnswer(String text) {
        spinner.stop();
        watcher.stop();
        out.println(Markdown.render(text, terminal.getWidth()));
        out.println();
    }

    @Override
    public void showError(String message) {
        spinner.stop();
        watcher.stop();
        out.println(Ansi.style(message, Ansi.RED));
    }

    @Override
    public void thinking() {
        watcher.start();
        spinner.start();
    }
```

In `close`, add `watcher.stop();` immediately after `spinner.stop();`.

Leave `onToolCall` and `onToolResult` alone. `onToolCall` stops the spinner only, because ESC
must keep working while a tool runs.

Change the welcome hint, because the present line plus ESC is wider than the 41 column banner:

```java
        out.println(Ansi.style("esc stops · ctrl-d quits · alt-enter adds a line · /help",
                Ansi.DIM));
```

- [ ] **Step 4: Run the tests to see them pass**

Run: `mvn -q test -Dtest=RichUiTest`
Expected: 0 failures. Fix any other test in the file that builds a `RichUi` with four arguments.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/konacode/cli/RichUi.java src/test/java/dev/konacode/cli/RichUiTest.java
git commit -m "feat: the rich interface watches for ESC during a turn"
```

---

## Task 14: Wire it in `Main`, and say so in `/help`

**Files:**
- Modify: `src/main/java/dev/konacode/cli/Main.java`
- Modify: `src/main/java/dev/konacode/cli/Commands.java`
- Modify: `src/test/java/dev/konacode/cli/MainTest.java`
- Modify: `src/test/java/dev/konacode/cli/CommandsTest.java`

- [ ] **Step 1: Write the failing test**

Add to `CommandsTest`. `RecordingUi` exposes `answers` as a field, not a method, and the file
builds a `Commands` through its own `commands(ui, conversation)` helper:

```java
    @Test
    void helpNamesTheStopKey() {
        RecordingUi ui = new RecordingUi();
        Commands commands = commands(ui, new Conversation(SYSTEM));

        commands.run("/help");

        assertTrue(ui.answers.get(0).contains("esc"), ui.answers.get(0));
    }
```

- [ ] **Step 2: Run the test to see it fail**

Run: `mvn -q test -Dtest=CommandsTest`
Expected: the assertion fails, because the help text has no `esc`.

- [ ] **Step 3: Change the help text**

```java
    private void help() {
        ui.showAnswer("""
                ```
                esc      stop the turn
                /help    show this list
                /tools   show the tools the model can call
                /clear   forget the conversation and start again
                /exit    end the session
                ```""");
    }
```

- [ ] **Step 4: Change `Main`**

`selectUi` takes the `Cancellation`, because `RichUi.open` needs it and `PlainUi.open` does not.

```java
    static Ui selectUi(Cancellation cancellation) throws IOException {
        String choice = System.getProperty("konacode.ui", "auto");
        return switch (choice) {
            case "plain" -> PlainUi.open();
            case "rich" -> RichUi.open(cancellation);
            case "auto" -> System.console() == null
                    ? PlainUi.open()
                    : openRichOrFallBack(cancellation);
            default -> throw new IllegalArgumentException(
                    "konacode.ui must be auto, plain or rich, but was: " + choice);
        };
    }

    private static Ui openRichOrFallBack(Cancellation cancellation) {
        try {
            return RichUi.open(cancellation);
        } catch (IOException e) {
            return PlainUi.open();
        }
    }
```

In `main`, build the `Cancellation` before the `try` that calls `selectUi`, and pass it:

```java
        Cancellation cancellation = new Cancellation();
        OpenAiConfig config;
        int maxIterations;
        Ui ui;
        try {
            config = OpenAiConfig.fromEnvironment(System.getenv());
            maxIterations = Agent.configuredMaxIterations();
            ui = selectUi(cancellation);
        } catch (IllegalArgumentException | IOException e) {
            System.err.println(e.getMessage());
            System.exit(1);
            return;
        }
```

Delete the second `Cancellation` you added in Task 5 and Task 10, so only this one remains.

- [ ] **Step 5: Fix `MainTest`**

Every call to `Main.selectUi()` becomes `Main.selectUi(new Cancellation())`. Add the import.

- [ ] **Step 6: Run the whole suite**

Run: `mvn test`
Expected: `BUILD SUCCESS`, 0 failures.

- [ ] **Step 7: Try it by hand**

```bash
mvn -q package
OPENAI_API_KEY=sk-... java -jar target/konacode.jar
```

Ask it something long, for example "read every file under src and summarise the design". Press
ESC while it works. Expected: `Stopped.`, then the prompt. Ask "what did you just do?" and check
the model can describe the tools it ran. Press ctrl-C during a turn and check konacode dies.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/dev/konacode/cli/ src/test/java/dev/konacode/cli/
git commit -m "feat: wire the ESC stop into the session"
```

---

## Task 15: `Workspace.delete`

**Files:**
- Modify: `src/main/java/dev/konacode/tools/Workspace.java`
- Modify: `src/test/java/dev/konacode/tools/WorkspaceTest.java`

- [ ] **Step 1: Write the failing test**

```java
    @Test
    void deletesAFile() throws IOException {
        Path file = root.resolve("gone.txt");
        Files.writeString(file, "bye");

        new Workspace(root).delete(file);

        assertFalse(Files.exists(file));
    }

    @Test
    void deleteReportsAMissingFile() {
        Workspace workspace = new Workspace(root);
        Path missing = root.resolve("absent.txt");

        assertThrows(IOException.class, () -> workspace.delete(missing));
    }
```

Add the imports `assertFalse` and `assertThrows` if the file does not have them.

- [ ] **Step 2: Run the tests to see them fail**

Run: `mvn -q test -Dtest=WorkspaceTest`
Expected: a compilation failure, `cannot find symbol: method delete`.

- [ ] **Step 3: Add the method**

Add it after `writeAtomic` in `Workspace`:

```java
    /**
     * Removes one file. On a symbolic link it removes the link and never the target.
     *
     * <p>This is where path confinement will hook in, with the rest of the filesystem
     * operations.
     */
    public void delete(Path file) throws IOException {
        Files.delete(file);
    }
```

- [ ] **Step 4: Run the tests to see them pass**

Run: `mvn -q test -Dtest=WorkspaceTest`
Expected: 0 failures.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/konacode/tools/Workspace.java src/test/java/dev/konacode/tools/WorkspaceTest.java
git commit -m "feat: Workspace deletes a file"
```

---

## Task 16: The `DeleteFile` tool

**Files:**
- Create: `src/main/java/dev/konacode/tools/DeleteFile.java`
- Create: `src/test/java/dev/konacode/tools/DeleteFileTest.java`

- [ ] **Step 1: Write the failing test**

```java
package dev.konacode.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeleteFileTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path root;

    private DeleteFile tool;

    @BeforeEach
    void setUp() {
        tool = new DeleteFile(new Workspace(root));
    }

    private static JsonNode args(String path) {
        return MAPPER.createObjectNode().put("path", path);
    }

    @Test
    void deletesAFile() throws IOException {
        Files.writeString(root.resolve("scratch.txt"), "temporary", StandardCharsets.UTF_8);

        ToolResult result = tool.execute(args("scratch.txt"));

        assertEquals(ToolResult.ok("deleted file scratch.txt"), result);
        assertFalse(Files.exists(root.resolve("scratch.txt")));
    }

    @Test
    void refusesAMissingPath() {
        ToolResult result = tool.execute(args("absent.txt"));

        ToolResult.Err error = assertInstanceOf(ToolResult.Err.class, result);
        assertTrue(error.message().startsWith("Path not found:"), error.message());
    }

    @Test
    void refusesADirectory() throws IOException {
        Files.createDirectory(root.resolve("src"));

        ToolResult result = tool.execute(args("src"));

        ToolResult.Err error = assertInstanceOf(ToolResult.Err.class, result);
        assertTrue(error.message().startsWith("Path is a directory"), error.message());
        assertTrue(Files.isDirectory(root.resolve("src")));
    }

    @Test
    void refusesArgumentsWithoutAPath() {
        ToolResult result = tool.execute(MAPPER.createObjectNode());

        ToolResult.Err error = assertInstanceOf(ToolResult.Err.class, result);
        assertTrue(error.message().startsWith("Invalid arguments for delete_file"),
                error.message());
    }

    @Test
    void deletesALinkAndLeavesItsTarget() throws IOException {
        Path target = root.resolve("target.txt");
        Files.writeString(target, "keep me", StandardCharsets.UTF_8);
        Files.createSymbolicLink(root.resolve("link.txt"), target);

        ToolResult result = tool.execute(args("link.txt"));

        assertInstanceOf(ToolResult.Ok.class, result);
        assertFalse(Files.exists(root.resolve("link.txt"), java.nio.file.LinkOption.NOFOLLOW_LINKS));
        assertEquals("keep me", Files.readString(target));
    }

    @Test
    void neverStopsOnAnInterrupt() {
        assertFalse(tool.stopsOnInterrupt());
    }
}
```

- [ ] **Step 2: Run the test to see it fail**

Run: `mvn -q test -Dtest=DeleteFileTest`
Expected: a compilation failure, `cannot find symbol: class DeleteFile`.

- [ ] **Step 3: Write the tool**

```java
package dev.konacode.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * Removes one file, so the model can reverse a file it created.
 *
 * <p>A directory is refused. A recursive delete is a different tool, and a far more dangerous
 * one.
 */
public final class DeleteFile implements Tool {

    private final Workspace workspace;

    public DeleteFile(Workspace workspace) {
        this.workspace = workspace;
    }

    @Override
    public String name() {
        return "delete_file";
    }

    @Override
    public String description() {
        return """
                Delete the file at a given relative path. \
                Use this to remove a file that is no longer wanted, for example one you created \
                by mistake. The delete cannot be undone. Do not use this with a directory.""";
    }

    @Override
    public ObjectNode inputSchema() {
        return Schemas.object()
                .requiredString("path", "The relative path of the file to delete.")
                .build();
    }

    @Override
    public ToolResult execute(JsonNode args) {
        JsonNode pathNode = args.path("path");
        if (!pathNode.isTextual() || pathNode.asText().isBlank()) {
            return ToolResult.err(
                    "Invalid arguments for delete_file. Expected: {\"path\": \"...\"}");
        }

        Path file;
        try {
            file = workspace.resolve(pathNode.asText());
        } catch (IllegalArgumentException e) {
            return ToolResult.err(e.getMessage());
        }

        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return ToolResult.err("Path not found: " + file);
        }
        // NOFOLLOW_LINKS: a link to a directory is deleted as a link, and the target survives.
        if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) {
            return ToolResult.err("Path is a directory, not a file: " + file);
        }

        try {
            workspace.delete(file);
            return ToolResult.ok("deleted file " + pathNode.asText());
        } catch (IOException e) {
            return ToolResult.err("Could not delete file at path: " + pathNode.asText() + ". " + e);
        }
    }

    @Override
    public boolean stopsOnInterrupt() {
        return false;
    }
}
```

- [ ] **Step 4: Run the test to see it pass**

Run: `mvn -q test -Dtest=DeleteFileTest`
Expected: 0 failures.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/konacode/tools/DeleteFile.java src/test/java/dev/konacode/tools/DeleteFileTest.java
git commit -m "feat: add the delete_file tool"
```

---

## Task 17: Register `DeleteFile`

**Files:**
- Modify: `src/main/java/dev/konacode/cli/Main.java`

- [ ] **Step 1: Register it**

```java
        ToolRegistry registry = ToolRegistry.of(
                new ListFiles(workspace, cancellation),
                new ReadFile(workspace, cancellation),
                new EditFile(workspace, cancellation),
                new DeleteFile(workspace));
```

Add the import `dev.konacode.tools.DeleteFile`.

- [ ] **Step 2: Run the whole suite**

Run: `mvn test`
Expected: `BUILD SUCCESS`, 0 failures.

- [ ] **Step 3: Try it by hand**

```bash
mvn -q package
cd /tmp && mkdir -p konacode-try && cd konacode-try
OPENAI_API_KEY=sk-... java -jar /path/to/target/konacode.jar
```

Type `/tools` and check `delete_file` is listed. Ask it to create a file, then ask it to remove
the file it just created. Check the file is gone.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/dev/konacode/cli/Main.java
git commit -m "feat: register delete_file"
```

---

## Task 18: The documentation

**Files:**
- Modify: `CLAUDE.md`
- Modify: `ARCHITECTURE.md`
- Modify: `README.md`

- [ ] **Step 1: `CLAUDE.md`**

In the `dev.konacode.tools` table, add these rows and change the `Workspace` row:

```markdown
| `StopCheck` | interface | `boolean stopped()`. One question, asked by a tool between two steps. It lives here and not in `agent` because `agent` already depends on `tools`, so the reverse import would close a cycle. `NEVER` serves every tool and test that does not stop. |
| `DeleteFile` | implements `Tool` | Removes one file. Refuses a directory. On a symbolic link it removes the link, never the target. Any path, no confirmation, no copy — see the design for what closes that. |
```

Add to the `Tool` row: `stopsOnInterrupt()` declares whether interrupting the thread that runs
this tool aborts it safely. It is abstract and not a default, so a new tool must answer it.

In the `dev.konacode.agent` table, add:

```markdown
| `Cancellation` | final class | The user's request to stop one turn. `request` and `stopped` are public; `arm` and `disarm` are not, because only the loop may decide where an interrupt is safe. Implements `StopCheck`. |
```

In the `dev.konacode.cli` table, add:

```markdown
| `EscapeWatcher` | class | Reads the terminal during a turn and calls `Cancellation.request()` on ESC. A sibling of `Spinner`: one daemon thread, `start` and `stop`, both idempotent, not final so a test can record. Raw mode keeps ISIG on, so ctrl-C still ends konacode. |
```

Update the test count in the Commands section from 186 to the number `mvn test` now reports.

- [ ] **Step 2: `ARCHITECTURE.md`**

In the "Invariants" section, replace this paragraph:

```markdown
**Three ways a turn ends**, and all three return text: an AssistantMessage with no ToolCalls, an
exhausted iteration budget, or a transport failure. None of them throws.
```

with this one:

```markdown
**Four ways a turn ends**, and all four return text: an AssistantMessage with no ToolCalls, an
exhausted iteration budget, a transport failure, or the user pressing ESC. None of them throws.

A stopped turn stays in the history, so the model can read what it did and reverse it when the
user asks. Every ToolCall that never ran is answered with an Err saying the user stopped the turn
before it ran, so the dangling call invariant above holds with no special case.
```

Add the two sequence diagrams from
[the interrupt design](docs/superpowers/specs/2026-08-23-interrupt-design.md) under a new heading
"Stopping a turn", and copy them exactly, because the column alignment is easy to break.

- [ ] **Step 3: `README.md`**

Add a row to the tool table at line 34:

```markdown
| `delete_file` | Remove a file | Refuses a directory |
```

Near line 101, where the rich interface is described, add: press `esc` to stop the turn. The
conversation keeps what happened, so the model can undo it when you ask.

- [ ] **Step 4: Check the documents against the code**

Run: `mvn test`
Expected: `BUILD SUCCESS`. Read each changed row and confirm it names a type that exists.

- [ ] **Step 5: Commit**

```bash
git add CLAUDE.md ARCHITECTURE.md README.md
git commit -m "docs: record the stop and delete_file"
```

---

## Done

Run the whole suite one last time, and check the branch.

```bash
mvn test
git log --oneline main..HEAD
```

Expected: `BUILD SUCCESS`, and one commit per task plus the three design commits.
