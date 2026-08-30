package dev.konacode.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.konacode.tools.Action;
import dev.konacode.tools.Effect;
import dev.konacode.tools.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class PlanToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode args(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static final String THREE_STEPS = """
            {"steps":[
              {"text":"find every use of respond","state":"doing"},
              {"text":"edit the files that use it","state":"todo"},
              {"text":"run mvn test","state":"todo"}]}""";

    @Test
    void givesTheListBack() {
        ToolResult result = new PlanTool(new TurnBudget(8, 24)).execute(args(THREE_STEPS));

        assertEquals("""
                1. [doing] find every use of respond
                2. [todo]  edit the files that use it
                3. [todo]  run mvn test""",
                assertInstanceOf(ToolResult.Ok.class, result).text());
    }

    @Test
    void raisesTheMaximumOfTheTurn() {
        TurnBudget budget = new TurnBudget(8, 24);

        new PlanTool(budget).execute(args(THREE_STEPS));

        assertEquals(24, budget.max());
    }

    @Test
    void refusesAnEmptyList() {
        TurnBudget budget = new TurnBudget(8, 24);

        ToolResult result = new PlanTool(budget).execute(args("{\"steps\":[]}"));

        assertInstanceOf(ToolResult.Err.class, result);
        assertEquals(8, budget.max(), "a call that states no plan earns no iteration");
    }

    @Test
    void refusesAMissingList() {
        assertInstanceOf(ToolResult.Err.class,
                new PlanTool(new TurnBudget(8, 24)).execute(args("{}")));
    }

    @Test
    void refusesAStateItDoesNotKnow() {
        TurnBudget budget = new TurnBudget(8, 24);

        ToolResult result = new PlanTool(budget)
                .execute(args("{\"steps\":[{\"text\":\"go\",\"state\":\"later\"}]}"));

        assertInstanceOf(ToolResult.Err.class, result);
        assertEquals(8, budget.max());
    }

    @Test
    void refusesAStepWithNoText() {
        assertInstanceOf(ToolResult.Err.class, new PlanTool(new TurnBudget(8, 24))
                .execute(args("{\"steps\":[{\"text\":\"  \",\"state\":\"todo\"}]}")));
    }

    @Test
    void statesASafeCall() {
        Action action = new PlanTool(new TurnBudget(8, 24)).computeAction(args(THREE_STEPS));

        assertEquals("plan", action.toolName());
        assertEquals(Effect.NONE, action.effect());
        assertEquals("", action.toolOperand());
        assertEquals(Optional.empty(), action.standingPermission());
    }

    @Test
    void doesNotStopOnAnInterrupt() {
        assertFalse(new PlanTool(new TurnBudget(8, 24)).stopsOnInterrupt(),
                "the tool records a list, so it has no step to stop between");
    }

    @Test
    void theSchemaAsksForTheWholeList() {
        JsonNode schema = new PlanTool(new TurnBudget(8, 24)).inputSchema();

        assertEquals("array", schema.get("properties").get("steps").get("type").asText());
        assertEquals("steps", schema.get("required").get(0).asText());
    }
}
