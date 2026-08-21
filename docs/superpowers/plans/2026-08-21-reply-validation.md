# Reply Validation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop a turn being lost when a model writes a tool call as prose, by having the OpenAI provider notice and re-send the identical request once.

**Architecture:** One new class in `dev.konacode.llm.openai`. The agent loop, the codec, the message model and every existing test are unchanged — this is a provider-level repair that the loop never learns about.

**Tech Stack:** Java 21, Maven, JUnit 5. No new dependencies.

**Spec:** [2026-08-21-reply-validation-design.md](../specs/2026-08-21-reply-validation-design.md) — read it first. It carries the reasoning behind the detection rule, which is easy to get subtly wrong.

**Baseline:** 93 tests passing. Build with:
```bash
JAVA_HOME=/home/bbossola/.sdkman/candidates/java/21.0.2-open mvn -q test
```
The default `java` on this machine is 11; `sdk use` does not work in a non-interactive shell, so every Maven command needs that prefix.

---

## File Structure

| File | Responsibility |
|---|---|
| `llm/openai/ReplyValidator.java` | **new** — recognises a tool call written as prose; owns the retry budget |
| `llm/openai/OpenAiClient.java` | modified — `chat` splits into `sendOnce` plus a retry loop |
| `llm/openai/ReplyValidatorTest.java` | **new** — all detection logic, offline |
| `llm/openai/OpenAiClientTest.java` | modified — retry behaviour via a scripted `Supplier` |
| `ARCHITECTURE.md`, `CLAUDE.md` | modified — record the resolved decision |

---

### Task 1: ReplyValidator

**Files:**
- Create: `src/main/java/dev/konacode/llm/openai/ReplyValidator.java`
- Test: `src/test/java/dev/konacode/llm/openai/ReplyValidatorTest.java`

- [ ] **Step 1: Write the failing test**

```java
package dev.konacode.llm.openai;

import dev.konacode.llm.Message.AssistantMessage;
import dev.konacode.llm.ToolCall;
import dev.konacode.llm.ToolSpec;
import dev.konacode.tools.Schemas;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplyValidatorTest {

    private static final List<ToolSpec> ADVERTISED = List.of(
            new ToolSpec("list_files", "List files.", Schemas.object().build()),
            new ToolSpec("read_file", "Read a file.", Schemas.object().build()));

    private static ReplyValidator validator() {
        return ReplyValidator.create("qwen3-coder", ADVERTISED);
    }

    private static AssistantMessage text(String body) {
        return new AssistantMessage(body, List.of());
    }

    // --- refuses -----------------------------------------------------------------------

    @Test
    void refusesNarrationFollowedByATrailingFunctionTag() {
        // Exactly what qwen3-coder produced. An earlier draft of the rule required the whole
        // text to be the blob, which would have caught nothing: the narration comes first.
        AssistantMessage reply = text("""
                I'll help you add a second line to sample.txt. First, I need to check if the \
                file exists and then I'll edit it.

                <function=list_files>
                </function>
                </tool_call>""");

        assertFalse(validator().accepts(reply));
    }

    @Test
    void refusesABareFunctionTagWithNothingElse() {
        assertFalse(validator().accepts(text("<function=read_file>\n</function>")));
    }

    @Test
    void refusesATrailingToolCallBlock() {
        AssistantMessage reply = text("""
                Let me look at that file.

                <tool_call>
                {"name": "read_file", "arguments": {"path": "pom.xml"}}
                </tool_call>""");

        assertFalse(validator().accepts(reply));
    }

    // --- accepts -----------------------------------------------------------------------

    @Test
    void acceptsAReplyThatCarriesRealToolCallsEvenIfItsTextMentionsOne() {
        AssistantMessage reply = new AssistantMessage(
                "Calling <function=list_files> now.",
                List.of(new ToolCall("c1", "list_files", "{}")));

        assertTrue(validator().accepts(reply));
    }

    @Test
    void acceptsAMentionThatTheModelKeepsTalkingAfter() {
        // konacode reads its own repo, so the model quotes these formats legitimately.
        AssistantMessage reply = text(
                "The codec turns <function=list_files> style text into a tool_calls array, "
                        + "which is what the provider actually expects.");

        assertTrue(validator().accepts(reply));
    }

    @Test
    void acceptsAConstructInsideAFencedCodeBlock() {
        AssistantMessage reply = text("""
                Some models emit this instead:

                ```
                <function=list_files>
                </function>
                ```""");

        assertTrue(validator().accepts(reply));
    }

    @Test
    void acceptsATrailingConstructNamingAToolWeDidNotAdvertise() {
        assertTrue(validator().accepts(text("<function=deploy>\n</function>")));
    }

    @Test
    void acceptsOrdinaryProse() {
        assertTrue(validator().accepts(text("There are two files here: pom.xml and README.md.")));
    }

    @Test
    void acceptsBlankText() {
        assertTrue(validator().accepts(text("   ")));
    }

    // --- budget ------------------------------------------------------------------------

    @Test
    void acceptsTheSecondGarbledReplyBecauseTheBudgetIsSpent() {
        ReplyValidator validator = validator();
        AssistantMessage garbled = text("<function=list_files>\n</function>");

        assertFalse(validator.accepts(garbled), "first should be refused");
        assertTrue(validator.accepts(garbled), "second should be accepted - budget spent");
    }

    @Test
    void neverRefusesWhenTheBudgetIsZero() {
        ReplyValidator validator = new ReplyValidator(ADVERTISED, 0);

        assertTrue(validator.accepts(text("<function=list_files>\n</function>")));
    }

    @Test
    void rejectsANegativeBudget() {
        assertThrows(IllegalArgumentException.class, () -> new ReplyValidator(ADVERTISED, -1));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
JAVA_HOME=/home/bbossola/.sdkman/candidates/java/21.0.2-open mvn -q test -Dtest=ReplyValidatorTest
```

Expected: COMPILATION ERROR — `cannot find symbol: class ReplyValidator`.

- [ ] **Step 3: Write the implementation**

```java
package dev.konacode.llm.openai;

import dev.konacode.llm.Message.AssistantMessage;
import dev.konacode.llm.ToolSpec;

import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Decides whether a reply is usable, or should be discarded and asked for again.
 *
 * <p>One per request: it captures what that request advertised — so it can tell a garbled tool
 * call from a reply that merely mentions one — and counts the replies it has refused, so the
 * retry budget lives here rather than in the client.
 *
 * <p>A reply carrying no tool calls is the agent loop's only definition of "the model is done".
 * A model that narrates an action and then writes the call as text produces a reply that says
 * "finished" while meaning the opposite. Rather than teach the loop about that, the provider
 * notices and re-sends the identical request.
 */
public class ReplyValidator {

    /** One extra attempt: enough for sampling variance, and it caps the cost at two requests. */
    static final int DEFAULT_MAX_RETRIES = 1;

    /** {@code <function=NAME> … </function>}, optionally trailed by a stray closing tag. */
    private static final Pattern FUNCTION_TAG = Pattern.compile(
            "<function=([A-Za-z0-9_.-]+)\\s*>.*?</function>\\s*(?:</tool_call>)?\\s*$",
            Pattern.DOTALL);

    /** {@code <tool_call> {"name": "NAME", …} </tool_call>}. */
    private static final Pattern TOOL_CALL_TAG = Pattern.compile(
            "<tool_call>.*?\"name\"\\s*:\\s*\"([A-Za-z0-9_.-]+)\".*?</tool_call>\\s*$",
            Pattern.DOTALL);

    private final Set<String> advertised;
    private final int maxRetries;
    private int refused;

    /**
     * A validator for one request.
     *
     * <p>The model is passed because these quirks are model-specific: qwen3-coder writes tool
     * calls as prose roughly one turn in four, gpt-5-mini has not been seen to. Today every model
     * gets the same validator — this is the point where that stops being true.
     */
    public static ReplyValidator create(String model, List<ToolSpec> advertised) {
        return new ReplyValidator(advertised, DEFAULT_MAX_RETRIES);
    }

    protected ReplyValidator(List<ToolSpec> advertised, int maxRetries) {
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must not be negative.");
        }
        this.advertised = advertised.stream()
                .map(ToolSpec::name)
                .collect(Collectors.toUnmodifiableSet());
        this.maxRetries = maxRetries;
    }

    /**
     * True to accept this reply; false to discard it and send the same request again.
     *
     * <p>Final because it owns the budget, and the budget is what makes the client's loop
     * terminate. Subclasses change what counts as garbled, not whether asking again ever stops.
     */
    public final boolean accepts(AssistantMessage reply) {
        if (refused >= maxRetries) {
            return true;
        }
        if (!isMisencodedToolCall(reply)) {
            return true;
        }
        refused++;
        return false;
    }

    /**
     * True when the reply looks like a tool call the model failed to emit properly: it carries no
     * tool calls, and its text ends with an unfenced call-shaped construct naming one of the tools
     * this request advertised.
     *
     * <p>Every condition must hold. The bias is deliberate and hard: missing a garbled call costs
     * one wasted turn, which is today's behaviour, while refusing a good reply throws away a
     * correct answer and burns a round trip on a model that will answer the same way again. That
     * matters here more than in most agents — konacode reads its own repository, which is full of
     * tool-call formats, so a model quoting one back is normal.
     *
     * <p>The extension point. Override to recognise a different model's quirk.
     */
    protected boolean isMisencodedToolCall(AssistantMessage reply) {
        if (!reply.toolCalls().isEmpty()) {
            return false;
        }
        String text = reply.text();
        if (text.isBlank()) {
            return false;
        }

        Matcher matcher = FUNCTION_TAG.matcher(text);
        if (!matcher.find()) {
            matcher = TOOL_CALL_TAG.matcher(text);
            if (!matcher.find()) {
                return false;
            }
        }
        if (!advertised.contains(matcher.group(1))) {
            return false;
        }
        return !isInsideFence(text, matcher.start());
    }

    /** A construct preceded by an odd number of fence markers sits inside a code block. */
    private static boolean isInsideFence(String text, int index) {
        int fences = 0;
        for (int at = text.indexOf("```"); at >= 0 && at < index; at = text.indexOf("```", at + 3)) {
            fences++;
        }
        return fences % 2 == 1;
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
JAVA_HOME=/home/bbossola/.sdkman/candidates/java/21.0.2-open mvn -q test
```

Expected: BUILD SUCCESS, 105 tests (93 existing + 12).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/konacode/llm/openai/ReplyValidator.java \
        src/test/java/dev/konacode/llm/openai/ReplyValidatorTest.java
git commit -m "feat(openai): recognise a tool call written as prose"
```

---

### Task 2: Retry in the client

**Files:**
- Modify: `src/main/java/dev/konacode/llm/openai/OpenAiClient.java`
- Test: `src/test/java/dev/konacode/llm/openai/OpenAiClientTest.java`

- [ ] **Step 1: Write the failing test**

Append to the existing `OpenAiClientTest`, keeping the two tests already there:

```java
    private static AssistantMessage garbled() {
        return new AssistantMessage("<function=list_files>\n</function>", List.of());
    }

    private static AssistantMessage plain(String text) {
        return new AssistantMessage(text, List.of());
    }

    private static ReplyValidator validator() {
        return ReplyValidator.create("qwen3-coder",
                List.of(new ToolSpec("list_files", "List files.", Schemas.object().build())));
    }

    /** Hands out scripted replies and counts how many were asked for. */
    private static final class ScriptedSender implements Supplier<AssistantMessage> {
        private final Deque<AssistantMessage> script = new ArrayDeque<>();
        private int sends;

        ScriptedSender(AssistantMessage... replies) {
            Collections.addAll(script, replies);
        }

        @Override
        public AssistantMessage get() {
            sends++;
            if (script.isEmpty()) {
                throw new AssertionError("asked for more replies than were scripted");
            }
            return script.poll();
        }
    }

    @Test
    void sendsOnceWhenTheFirstReplyIsAccepted() {
        ScriptedSender sender = new ScriptedSender(plain("Two files here."));

        AssistantMessage reply = OpenAiClient.sendUntilAccepted(validator(), sender);

        assertEquals("Two files here.", reply.text());
        assertEquals(1, sender.sends);
    }

    @Test
    void asksAgainWhenTheFirstReplyIsAGarbledToolCall() {
        ScriptedSender sender = new ScriptedSender(garbled(), plain("Two files here."));

        AssistantMessage reply = OpenAiClient.sendUntilAccepted(validator(), sender);

        assertEquals("Two files here.", reply.text());
        assertEquals(2, sender.sends);
    }

    @Test
    void returnsTheSecondGarbledReplyAsItCameRatherThanRetryingForever() {
        ScriptedSender sender = new ScriptedSender(garbled(), garbled());

        AssistantMessage reply = OpenAiClient.sendUntilAccepted(validator(), sender);

        assertEquals(garbled().text(), reply.text());
        assertEquals(2, sender.sends);
    }
```

Add the imports these need: `dev.konacode.llm.Message.AssistantMessage`, `dev.konacode.llm.ToolSpec`, `dev.konacode.tools.Schemas`, `java.util.ArrayDeque`, `java.util.Collections`, `java.util.Deque`, `java.util.function.Supplier`, and `org.junit.jupiter.api.Assertions.assertEquals`. Check which are already imported.

- [ ] **Step 2: Run the test to verify it fails**

```bash
JAVA_HOME=/home/bbossola/.sdkman/candidates/java/21.0.2-open mvn -q test -Dtest=OpenAiClientTest
```

Expected: COMPILATION ERROR — `cannot find symbol: method sendUntilAccepted`.

- [ ] **Step 3: Split `chat` and add the loop**

In `OpenAiClient`, replace the body of `chat` with the three lines below, move everything that followed the `body` assignment into a new private `sendOnce`, and add the package-private helper. Nothing inside `sendOnce` changes.

```java
    @Override
    public AssistantMessage chat(List<Message> history, List<ToolSpec> tools) {
        ObjectNode body = codec.encodeRequest(config.model(), history, tools);
        ReplyValidator validator = ReplyValidator.create(config.model(), tools);

        return sendUntilAccepted(validator, () -> sendOnce(body));
    }

    /**
     * Sends until the validator accepts a reply. The body is encoded once and re-sent unchanged,
     * so a retry is a fresh sample of the same request rather than a subtly different one.
     *
     * <p>Package-private and static so the retry behaviour can be tested with a scripted sender,
     * without a network or a mocking framework. Termination is guaranteed by the validator, which
     * accepts unconditionally once its budget is spent.
     */
    static AssistantMessage sendUntilAccepted(
            ReplyValidator validator, Supplier<AssistantMessage> send) {
        AssistantMessage reply = send.get();
        while (!validator.accepts(reply)) {
            reply = send.get();
        }
        return reply;
    }

    private AssistantMessage sendOnce(ObjectNode body) {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(config.chatCompletionsUri())
                    .timeout(config.timeout())
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + config.apiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                    .build();
        } catch (IllegalArgumentException e) {
            // A malformed base URL, or a key carrying a control character - a trailing newline
            // survives isBlank() - would otherwise escape as an unchecked exception and kill the
            // session, since the agent loop catches only LlmException.
            throw new LlmException("Could not build the request: " + e.getMessage(), e);
        }

        HttpResponse<String> response;
        try {
            response = http.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new LlmException(
                    "Request to " + config.chatCompletionsUri() + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("Request was interrupted.", e);
        }

        if (response.statusCode() / 100 != 2) {
            throw new LlmException(
                    "HTTP " + response.statusCode() + ": " + truncate(response.body()));
        }

        return codec.decodeResponse(response.body());
    }
```

Add `import java.util.function.Supplier;`.

- [ ] **Step 4: Run the tests to verify they pass**

```bash
JAVA_HOME=/home/bbossola/.sdkman/candidates/java/21.0.2-open mvn -q test
```

Expected: BUILD SUCCESS, 108 tests (105 + 3).

The two pre-existing `OpenAiClientTest` cases — malformed base URL and a key with a control character — must still pass. Both now fail inside `sendOnce` on the first send, so `LlmException` still propagates out of `chat` untouched.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/konacode/llm/openai/OpenAiClient.java \
        src/test/java/dev/konacode/llm/openai/OpenAiClientTest.java
git commit -m "feat(openai): re-send once when a reply is a tool call written as prose"
```

---

### Task 3: Record the resolved decision in the docs

**Files:**
- Modify: `ARCHITECTURE.md`
- Modify: `CLAUDE.md`

- [ ] **Step 1: Replace the open question in `ARCHITECTURE.md`**

The final section is currently headed `## An open question` and ends by saying the matter is
undecided. Replace that whole section with:

```markdown
## A reply that lies about being finished

The invariants above contain one that is doing more work than it looks:

> **An AssistantMessage carrying no ToolCalls is the definition of "the model is done."**

The loop has no other notion of completion. So a reply whose *text* reads
`<function=list_files>` while carrying no ToolCalls is not a malformed message — it is a
well-formed message meaning "done", sent by a model that meant the opposite. Some local models do
this intermittently; qwen3-coder does it roughly one turn in four.

This is treated as a **provider defect, not a domain concept**. `LlmClient` already promises text
or tool calls, faithfully, so a model garbling the encoding has produced a broken response from
that provider, and repairing it belongs there. The OpenAI provider notices such a reply and
re-sends the identical request once; the loop above never learns that any of this happens.

Detection is biased hard toward missing cases, because refusing a good reply is far more
expensive than missing a bad one. See
[the design](docs/superpowers/specs/2026-08-21-reply-validation-design.md).
```

- [ ] **Step 2: Add `ReplyValidator` to the definitions table in `CLAUDE.md`**

In the `dev.konacode.llm.openai` table, add a row after `OpenAiClient`:

```markdown
| `ReplyValidator` | class, one per request | Recognises a tool call the model wrote as prose rather than emitting properly, and owns the budget for asking again. `accepts` is final so the client's retry loop always terminates; `isMisencodedToolCall` is the extension point for another model's quirk. Detection is deliberately strict — see FOLLOWUP.md for the observability gap. |
```

- [ ] **Step 3: Update the test count**

`CLAUDE.md` says `mvn test  # 93 tests, all offline, no network`. Change 93 to the number the
suite actually reports after Task 2. Do the same in `CONTEXT.md`.

- [ ] **Step 4: Commit**

```bash
git add ARCHITECTURE.md CLAUDE.md CONTEXT.md
git commit -m "docs: record reply validation as a provider-level repair"
```

---

### Task 4: Verify against the live model

**Files:** none — this is verification.

- [ ] **Step 1: Rebuild**

```bash
JAVA_HOME=/home/bbossola/.sdkman/candidates/java/21.0.2-open mvn -q package
```

- [ ] **Step 2: Run the prompt that failed before**

The failure was observed on an edit request. Run it several times, since the quirk appears in
roughly one turn in four and a single green run proves nothing:

```bash
mkdir -p /tmp/konacode-smoke && cd /tmp/konacode-smoke
for i in 1 2 3 4 5 6; do
  printf 'hello from the smoke test\n' > sample.txt
  echo "--- attempt $i ---"
  echo 'append a line to sample.txt with the text: edited by konacode' | timeout 90 env \
    OPENAI_API_KEY=ollama KONACODE_BASE_URL=http://localhost:11434/v1 \
    KONACODE_MODEL=qwen3-coder \
    PATH=/home/bbossola/.sdkman/candidates/java/21.0.2-open/bin:$PATH \
    java -Dkonacode.maxIterations=6 -jar /home/bbossola/projects/ai/konacode/target/konacode.jar \
    2>&1 | grep -E "^tool:|function=" | head -5
  echo "file: $(tr '\n' '|' < sample.txt)"
done
```

Expected: every attempt ends with the edit applied. A `function=` line appearing in the output
means a garbled reply reached the user — the retry either did not fire or did not help.

- [ ] **Step 3: Confirm the detector is not firing on legitimate mentions**

Point konacode at its own repository, which is full of tool-call formats, and ask something that
makes the model quote them:

```bash
cd /home/bbossola/projects/ai/konacode
echo "read ARCHITECTURE.md and explain what happens when a model writes a tool call as text" \
  | timeout 120 env OPENAI_API_KEY=ollama KONACODE_BASE_URL=http://localhost:11434/v1 \
    KONACODE_MODEL=qwen3-coder \
    PATH=/home/bbossola/.sdkman/candidates/java/21.0.2-open/bin:$PATH \
    java -jar target/konacode.jar 2>&1 | tail -20
```

Expected: a normal answer that may quote `<function=list_files>` from the file, returned promptly.
A noticeably slow answer would suggest a false positive caused a retry — the exact failure mode
the strict rule exists to avoid. If that happens, report it rather than loosening the tests.

- [ ] **Step 4: Record the outcome**

Note how many of the six attempts needed a retry, if that is observable. It is not currently
logged — that is the known observability gap in FOLLOWUP.md — so the honest measure is whether
the failure that used to appear roughly one turn in four still appears at all.

---

## Done

`mvn test` is green with 108 offline tests, and the edit prompt that previously failed
intermittently now succeeds repeatedly against `qwen3-coder`. What remains is the observability
gap: a retry is still invisible. See [FOLLOWUP.md](../../../FOLLOWUP.md).
