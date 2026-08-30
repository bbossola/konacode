package dev.konacode.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.konacode.tools.Action;
import dev.konacode.tools.Effect;
import dev.konacode.tools.Schemas;
import dev.konacode.tools.Tool;
import dev.konacode.tools.ToolResult;

import java.util.Set;

/**
 * Records the steps of the work, and gives the list back.
 *
 * <p>This tool lives in {@code agent} and not in {@code tools}, because it acts on the turn and
 * not on the world. It reaches no file and runs no command, so it states {@link Effect#NONE}.
 *
 * <p>konacode stores no plan. The result goes into the conversation, and konacode sends the whole
 * conversation on each request, so the model reads its own plan on each iteration.
 */
public final class PlanTool implements Tool {

    private static final Set<String> STATES = Set.of("todo", "doing", "done");

    /** The result enters the conversation again on every later iteration, so the size has a cap. */
    private static final int MAX_STEPS = 20;

    private static final int MAX_TEXT = 200;

    private static final String USAGE = "Invalid arguments for plan. Expected: "
            + "{\"steps\": [{\"text\": \"...\", \"state\": \"todo|doing|done\"}]}";

    private final TurnBudget budget;

    public PlanTool(TurnBudget budget) {
        this.budget = budget;
    }

    @Override
    public String name() {
        return "plan";
    }

    @Override
    public String description() {
        return """
                Record the steps of the work you are going to do, and give the list back. \
                Use this before work that needs more than two or three tool calls. \
                Write one short step for each thing you must do, and write 20 steps or fewer. \
                Each step has a state: todo, doing or done. Keep one step doing at a time. \
                Call this tool again at each change: mark the step you finished done, mark the next step \
                doing, and send the whole list in one call. \
                This tool changes no file and runs no command. It records what you intend to do, \
                and you read it again on the next step.""";
    }

    @Override
    public ObjectNode inputSchema() {
        return Schemas.object()
                .requiredArray("steps", "The whole list of steps, in order.",
                        Schemas.object()
                                .requiredString("text", "What this step does, in one short sentence.")
                                .requiredString("state", "todo, doing or done.")
                                .build())
                .build();
    }

    @Override
    public ToolResult execute(JsonNode args) {
        JsonNode steps = args.path("steps");
        if (!steps.isArray()) {
            return ToolResult.err(USAGE);
        }
        if (steps.isEmpty()) {
            return ToolResult.err("The plan has no step. Send at least one step.");
        }
        if (steps.size() > MAX_STEPS) {
            return ToolResult.err("The plan has " + steps.size() + " steps. Send " + MAX_STEPS + " steps or fewer.");
        }

        StringBuilder list = new StringBuilder();
        int number = 1;
        for (JsonNode step : steps) {
            JsonNode text = step.path("text");
            if (!text.isTextual() || text.asText().isBlank()) {
                return ToolResult.err("Step " + number + " has no text. Give one short sentence for each step.");
            }
            if (text.asText().length() > MAX_TEXT) {
                return ToolResult.err("Step " + number + " is too long. Keep a step under " + MAX_TEXT + " characters.");
            }
            String state = step.path("state").asText("");
            if (!STATES.contains(state)) {
                // The state is a word the model chose, so the message names the step and not the word.
                return ToolResult.err("Step " + number + " has a state konacode does not know. Use todo, doing or done.");
            }
            if (!list.isEmpty()) {
                list.append('\n');
            }
            list.append(String.format("%d. %-7s %s", number, "[" + state + "]", oneLine(text.asText())));
            number++;
        }

        // After the list is read, so a call that states no plan earns no iteration.
        budget.extend();
        return ToolResult.ok(list.toString());
    }

    @Override
    public boolean stopsOnInterrupt() {
        return false;
    }

    @Override
    public Action computeAction(JsonNode args) {
        return Action.once(name(), Effect.NONE, "");
    }

    /** A newline in the text of a step draws a second numbered line, and that line is not konacode's. */
    private static String oneLine(String text) {
        return text.replace('\r', ' ').replace('\n', ' ');
    }
}
