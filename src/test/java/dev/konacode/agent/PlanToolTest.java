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
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String THREE_STEPS = """
            {"steps":[
              {"text":"find every use of respond","state":"doing"},
              {"text":"edit the files that use it","state":"todo"},
              {"text":"run mvn test","state":"todo"}]}""";

    private static JsonNode args(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private final TurnBudget budget = new TurnBudget(8, 24);
    private final PlanTool tool = new PlanTool(budget);

    private String refusal(String json) {
        ToolResult result = tool.execute(args(json));

        assertEquals(8, budget.max(), "a call that states no plan earns no iteration");
        return assertInstanceOf(ToolResult.Err.class, result).message();
    }

    @Test
    void givesTheListBack() {
        ToolResult result = tool.execute(args(THREE_STEPS));

        assertEquals("""
                1. [doing] find every use of respond
                2. [todo]  edit the files that use it
                3. [todo]  run mvn test""",
                assertInstanceOf(ToolResult.Ok.class, result).text());
    }

    @Test
    void raisesTheMaximumOfTheTurn() {
        tool.execute(args(THREE_STEPS));

        assertEquals(24, budget.max());
    }

    @Test
    void makesOneLineOfAStepThatHoldsTwoLines() {
        ToolResult result = tool.execute(args("{\"steps\":[{\"text\":\"read\\nthen write\",\"state\":\"todo\"}]}"));

        assertEquals("1. [todo]  read then write", assertInstanceOf(ToolResult.Ok.class, result).text());
    }

    @Test
    void refusesAnEmptyList() {
        assertEquals("The plan has no step. Send at least one step.", refusal("{\"steps\":[]}"));
    }

    @Test
    void refusesAMissingList() {
        assertEquals("Invalid arguments for plan. Expected: "
                + "{\"steps\": [{\"text\": \"...\", \"state\": \"todo|doing|done\"}]}", refusal("{}"));
    }

    @Test
    void refusesAListThatIsNotAnArray() {
        assertEquals("Invalid arguments for plan. Expected: "
                + "{\"steps\": [{\"text\": \"...\", \"state\": \"todo|doing|done\"}]}", refusal("{\"steps\":\"find every use\"}"));
    }

    @Test
    void refusesMoreStepsThanTheCap() {
        StringBuilder steps = new StringBuilder("{\"steps\":[");
        for (int i = 1; i <= 21; i++) {
            steps.append(i > 1 ? "," : "").append("{\"text\":\"step ").append(i).append("\",\"state\":\"todo\"}");
        }

        assertEquals("The plan has 21 steps. Send 20 steps or fewer.", refusal(steps.append("]}").toString()));
    }

    @Test
    void refusesAStateItDoesNotKnow() {
        String message = refusal("{\"steps\":[{\"text\":\"go\",\"state\":\"todo\"},{\"text\":\"stop\",\"state\":\"later\"}]}");

        assertEquals("Step 2 has a state konacode does not know. Use todo, doing or done.", message);
        assertFalse(message.contains("later"), "no message repeats a word the model wrote");
    }

    @Test
    void refusesAStepWithNoState() {
        assertEquals("Step 2 has a state konacode does not know. Use todo, doing or done.",
                refusal("{\"steps\":[{\"text\":\"go\",\"state\":\"todo\"},{\"text\":\"stop\"}]}"));
    }

    @Test
    void refusesAStepWithNoText() {
        assertEquals("Step 2 has no text. Give one short sentence for each step.",
                refusal("{\"steps\":[{\"text\":\"go\",\"state\":\"todo\"},{\"text\":\"  \",\"state\":\"todo\"}]}"));
    }

    @Test
    void refusesAStepThatIsNotAnObject() {
        assertEquals("Step 1 has no text. Give one short sentence for each step.",
                refusal("{\"steps\":[\"find every use of respond\"]}"));
    }

    @Test
    void refusesATextLongerThanTheCap() {
        String tooLong = "x".repeat(201);

        assertEquals("Step 1 is too long. Keep a step under 200 characters.",
                refusal("{\"steps\":[{\"text\":\"" + tooLong + "\",\"state\":\"todo\"}]}"));
    }

    @Test
    void statesASafeCall() {
        Action action = tool.computeAction(args(THREE_STEPS));

        assertEquals("plan", action.toolName());
        assertEquals(Effect.NONE, action.effect());
        assertEquals("", action.toolOperand());
        assertEquals(Optional.empty(), action.standingPermission());
    }

    @Test
    void doesNotStopOnAnInterrupt() {
        assertFalse(tool.stopsOnInterrupt(),
                "the tool records a list, so it has no step to stop between");
    }

    @Test
    void theSchemaAsksForTheWholeList() {
        JsonNode schema = tool.inputSchema();

        assertEquals("array", schema.get("properties").get("steps").get("type").asText());
        assertEquals("steps", schema.get("required").get(0).asText());
    }

    @Test
    void theDescriptionSaysWhenToCallTheTool() {
        assertTrue(tool.description().contains("more than two or three tool calls"), tool.description());
    }

    @Test
    void theDescriptionNamesTheCapOnTheNumberOfSteps() {
        assertTrue(tool.description().contains("20 steps or fewer"), tool.description());
    }
}
