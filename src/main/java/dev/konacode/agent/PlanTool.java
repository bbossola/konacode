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
                Write one short step for each thing you must do. \
                Each step has a state: todo, doing or done. Keep one step doing at a time. \
                Call this tool again each time a step starts and each time a step finishes, \
                and send the whole list every time. \
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
        if (!steps.isArray() || steps.isEmpty()) {
            return ToolResult.err(USAGE);
        }

        StringBuilder list = new StringBuilder();
        int number = 1;
        for (JsonNode step : steps) {
            JsonNode text = step.path("text");
            String state = step.path("state").asText("");
            if (!text.isTextual() || text.asText().isBlank() || !STATES.contains(state)) {
                return ToolResult.err(USAGE);
            }
            if (number > 1) {
                list.append('\n');
            }
            list.append(String.format("%d. %-7s %s", number++, "[" + state + "]", text.asText()));
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
}
