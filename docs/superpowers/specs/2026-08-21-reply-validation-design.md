# Reply validation — design

**Date:** 2026-08-21
**Status:** approved, pending implementation plan

## Problem

Some models write a tool call as prose instead of emitting it in the provider's tool-call field.
Observed against `qwen3-coder` via Ollama, roughly one turn in four:

```
konacode: I'll help you add a second line to sample.txt saying "edited by konacode".
          First, I need to check if the file exists and then I'll edit it.

          <function=list_files>
          </function>
          </tool_call>
```

The reply is well formed. It carries `content` and no `tool_calls`. Nothing throws, nothing is
malformed, and no error occurs anywhere.

It breaks because of a single invariant in [ARCHITECTURE.md](../../../ARCHITECTURE.md):

> An AssistantMessage carrying no ToolCalls is the definition of "the model is done."

That is the loop's only notion of completion. So a reply that says "finished" while meaning the
opposite is indistinguishable from a real answer, and the turn ends with the blob printed to the
user.

The same model returns a correct `tool_calls` structure for the same prompt when asked again, so
this is sampling variance rather than a systematic misunderstanding.

## Decision: this is a provider defect

`LlmClient`'s contract already says an implementation returns text or tool calls, faithfully. A
model garbling the encoding is producing a broken response *from that provider*, so repairing it
is the provider's job.

The alternative — giving the domain a third outcome alongside "here is text" and "here is a tool
call" — was rejected. It would widen the SPI every future provider must satisfy, and put a
model-specific quirk into the vocabulary of a loop that is deliberately provider-neutral.

**The agent loop does not change. It never learns that malformed replies exist.**

## Detection

Detection is biased hard toward **missing** cases rather than catching innocent ones, because the
two failure modes are not symmetric:

| | Cost |
|---|---|
| **False negative** — miss a garbled call | Exactly today's behaviour. One wasted turn. |
| **False positive** — refuse a good reply | A correct answer is thrown away, a round trip is burned, and the model answers the same way again because it was right. The user sees latency and possibly a worse answer, with no indication why. |

This matters more for konacode than for most agents: it is a coding agent whose own repository is
*about* tool-call formats. `src/test/resources/openai/` contains tool-call JSON and
`ARCHITECTURE.md` contains the literal string `<function=list_files>`. When konacode reads its own
source, the model will quote these back legitimately.

### The rule

`isMisencodedToolCall(reply)` is true when **all** of the following hold. Any one failing means we
do nothing, which is today's behaviour.

1. `reply.toolCalls()` is empty.
2. `reply.text()` is not blank.
3. Ignoring trailing whitespace, the text **ends with** a recognised call-shaped construct.
4. That construct is **not inside a fenced code block** — the number of ``` markers before it is
   even.
5. The tool name inside the construct is one of the names **advertised in this request**.

Condition 3 does most of the work. A model emitting a garbled call puts it last, because it is an
action, not commentary; a model *explaining* tool-call formats keeps talking afterwards.

Note that an earlier draft of this rule required the *entire* text to be a call-shaped blob. The
one case actually observed has a sentence of narration first, so that rule would have caught
nothing. The rule must tolerate leading prose.

### Recognised constructs

Both shapes are emitted by qwen-family models:

```
<function=NAME>
  ...optional arguments...
</function>
```

optionally followed by a stray `</tool_call>`, and:

```
<tool_call>
{"name": "NAME", "arguments": {...}}
</tool_call>
```

These are not a specification — they are observed output. New shapes are added when observed, not
guessed at.

## Retry

Blind: the request body is encoded **once** and re-sent byte-identical. No corrective nudge is
added.

A nudge was considered and rejected for now. The provider could legitimately append a transient
instruction to the outgoing request without touching the `Conversation` — the invariant that the
Conversation is the only state would survive, since the nudge never enters it. But it puts words
in the model's context that the loop cannot see, which makes "why did it answer that?"
unanswerable later, and it is prompt engineering tuned to one model's failure mode. The evidence
says resampling alone is enough.

**One extra attempt.** Enough for sampling variance — at an observed one-in-four failure rate,
one retry takes it to roughly one in sixteen — and it caps the cost at two requests rather than
N. That cost is not free: konacode resends the whole conversation every turn, so a retry re-pays
the full prompt.

If the second reply is also a garbled call, it is returned as it came, and the turn ends exactly
as it does today. The worst case is never worse than current behaviour.

## The class

One class, in `dev.konacode.llm.openai`. Not in `dev.konacode.llm`: the concept is reusable, but
nothing reuses it, and putting it in the SPI package would imply every provider must reason about
it. Moving it up when a second provider wants one is a two-minute change.

```java
public class ReplyValidator {

    /** One extra attempt: enough for sampling variance, and it caps the cost at two requests. */
    static final int DEFAULT_MAX_RETRIES = 1;

    private final Set<String> advertised;
    private final int maxRetries;
    private int refused;

    /**
     * A validator for one request.
     *
     * <p>The model is passed because these quirks are model-specific. Today every model gets the
     * same validator — this is the point where that stops being true.
     */
    public static ReplyValidator create(String model, List<ToolSpec> advertised) {
        return new ReplyValidator(advertised, DEFAULT_MAX_RETRIES);
    }

    protected ReplyValidator(List<ToolSpec> advertised, int maxRetries) { ... }

    /**
     * True to accept this reply; false to discard it and send the same request again.
     *
     * <p>Final because it owns the budget, and the budget is what makes the client's loop
     * terminate. Subclasses change what counts as garbled, not whether asking again ever stops.
     */
    public final boolean accepts(AssistantMessage reply) {
        if (refused >= maxRetries) {
            return true;                       // budget spent — take the reply as it came
        }
        if (!isMisencodedToolCall(reply)) {
            return true;
        }
        refused++;
        return false;
    }

    /** The extension point. Override to recognise a different model's quirk. */
    protected boolean isMisencodedToolCall(AssistantMessage reply) { ... }
}
```

**One per request.** That lifetime is what lets it capture the advertised tool names, which
condition 5 needs, and hold the refusal count, which keeps the budget out of the client.

**`accepts` is final, `isMisencodedToolCall` is protected.** The extension point is detection. A
subclass cannot make the client spin, so "must eventually accept" is enforced rather than
promised.

**`model` is unused today.** This is the one piece of speculative surface in the design, kept
deliberately: it is one parameter with a clear future use, and adding it later would mean
touching the call site anyway.

## Client integration

`OpenAiClient.chat` gains four lines:

```java
ObjectNode body = codec.encodeRequest(config.model(), history, tools);   // encoded once
ReplyValidator validator = ReplyValidator.create(config.model(), tools);

return sendUntilAccepted(validator, () -> sendOnce(body));
```

with the existing send-and-decode extracted to `sendOnce(ObjectNode)`, unchanged in behaviour,
and the loop in a package-private helper:

```java
    static AssistantMessage sendUntilAccepted(
            ReplyValidator validator, Supplier<AssistantMessage> send) {
        AssistantMessage reply = send.get();
        while (!validator.accepts(reply)) {
            reply = send.get();
        }
        return reply;
    }
```

The helper exists for one reason: it makes the retry behaviour testable offline, driven by a
scripted `Supplier`, without a network or a mocking framework. Inlining the loop in `chat` would
leave the only new behaviour in this change untested, since `OpenAiClient` cannot otherwise be
exercised without HTTP.

## Deliberately not doing

- **No nudge, no corrective message.** See above.
- **No parsing or salvaging** of the prose into a real `ToolCall`. It would avoid a round trip,
  but it means writing a parser for an undocumented, model-specific format that varies between
  models and versions — and a mis-parse would execute the *wrong* tool call, which is worse than
  any failure this design can produce.
- **No per-model branching yet.** `create` takes the model and ignores it.
- **No observability.** A retry is currently invisible: nothing in the loop, the listener, or the
  conversation records it, so a session that sent 40 requests looks like one that sent 20. To be
  addressed with logging later; tracked in FOLLOWUP.md.

## Testing

`ReplyValidatorTest` — all detection logic, offline:

| Case | Expected |
|---|---|
| Narration then trailing `<function=list_files></function></tool_call>`, advertised | refuse |
| Bare trailing `<function=read_file></function>`, nothing else | refuse |
| Trailing `<tool_call>{"name":"read_file",...}</tool_call>`, advertised | refuse |
| Reply carries real tool calls, text also mentions `<function=` | accept |
| `<function=list_files>` mid-message, prose continues after it | accept |
| Construct inside a fenced code block | accept |
| Name not advertised (`<function=deploy>`) | accept |
| Ordinary prose | accept |
| Blank text | accept |
| Second refusal with a budget of 1 | accept — budget spent |
| Budget of 0 | accept immediately, never refuses |

`OpenAiClientTest` — retry behaviour via `sendUntilAccepted` and a scripted `Supplier`, offline:

| Case | Expected |
|---|---|
| First reply accepted | one send, that reply returned |
| First refused, second accepted | two sends, second reply returned |
| Both refused, budget 1 | two sends, second reply returned as-is |

## Out of scope

The agent loop, the codec, the message model, and every existing test are unchanged.
