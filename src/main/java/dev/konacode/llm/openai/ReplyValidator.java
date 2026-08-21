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
