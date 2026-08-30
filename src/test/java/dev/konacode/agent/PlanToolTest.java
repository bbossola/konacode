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

    private static final String SHAPE = "Invalid arguments for plan. Expected: "
            + "{\"steps\": [{\"text\": \"...\", \"state\": \"todo|doing|done\"}]}";

    private static JsonNode args(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static String plan(int steps) {
        StringBuilder json = new StringBuilder("{\"steps\":[");
        for (int i = 1; i <= steps; i++) {
            json.append(i > 1 ? "," : "").append("{\"text\":\"step ").append(i).append("\",\"state\":\"todo\"}");
        }
        return json.append("]}").toString();
    }

    private final TurnBudget budget = new TurnBudget(8, 24);
    private final PlanTool tool = new PlanTool(budget);

    private String accepted(String json) {
        return assertInstanceOf(ToolResult.Ok.class, tool.execute(args(json))).text();
    }

    private String refusal(String json) {
        ToolResult result = tool.execute(args(json));

        assertEquals(8, budget.max(), "PlanTool reads the whole list first, so a call it refuses does not raise the maximum");
        return assertInstanceOf(ToolResult.Err.class, result).message();
    }

    @Test
    void givesTheListBack() {
        assertEquals("""
                1. [doing] find every use of respond
                2. [todo]  edit the files that use it
                3. [todo]  run mvn test""", accepted(THREE_STEPS));
    }

    @Test
    void raisesTheMaximumOfTheTurn() {
        tool.execute(args(THREE_STEPS));

        assertEquals(24, budget.max());
    }

    @Test
    void acceptsTheNumberOfStepsAtTheCap() {
        String list = accepted(plan(PlanTool.MAX_STEPS));

        assertEquals(PlanTool.MAX_STEPS, list.lines().count());
        assertEquals(24, budget.max());
    }

    @Test
    void acceptsATextAtTheCap() {
        String text = "x".repeat(PlanTool.MAX_TEXT);

        assertEquals("1. [todo]  " + text, accepted("{\"steps\":[{\"text\":\"" + text + "\",\"state\":\"todo\"}]}"));
    }

    @Test
    void makesOneLineOfAStepThatHoldsANewline() {
        assertEquals("1. [todo]  read then write",
                accepted("{\"steps\":[{\"text\":\"read\\nthen write\",\"state\":\"todo\"}]}"));
    }

    @Test
    void makesOneLineOfAStepThatHoldsACarriageReturn() {
        assertEquals("1. [todo]  read then write",
                accepted("{\"steps\":[{\"text\":\"read\\rthen write\",\"state\":\"todo\"}]}"));
    }

    @Test
    void makesOneLineOfAStepThatHoldsBothCharacters() {
        assertEquals("1. [todo]  read then write",
                accepted("{\"steps\":[{\"text\":\"read\\r\\nthen write\",\"state\":\"todo\"}]}"));
    }

    @Test
    void refusesAnEmptyList() {
        assertEquals("The plan has no step. Send at least one step.", refusal("{\"steps\":[]}"));
    }

    @Test
    void refusesAMissingList() {
        assertEquals(SHAPE, refusal("{}"));
    }

    @Test
    void refusesAListThatIsNotAnArray() {
        assertEquals(SHAPE, refusal("{\"steps\":\"find every use\"}"));
    }

    @Test
    void refusesAStepThatIsNotAnObject() {
        assertEquals(SHAPE, refusal("{\"steps\":[\"find every use of respond\"]}"));
    }

    @Test
    void refusesMoreStepsThanTheCap() {
        assertEquals("The plan has " + (PlanTool.MAX_STEPS + 1) + " steps. Send " + PlanTool.MAX_STEPS + " steps or fewer.",
                refusal(plan(PlanTool.MAX_STEPS + 1)));
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
    void refusesATextLongerThanTheCap() {
        String tooLong = "x".repeat(PlanTool.MAX_TEXT + 1);

        assertEquals("Step 1 is " + (PlanTool.MAX_TEXT + 1) + " characters. Keep a step to " + PlanTool.MAX_TEXT + " characters or fewer.",
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
    void theDescriptionAsksForOneCallAtEachChange() {
        assertTrue(tool.description().contains("in one call"), tool.description());
    }

    @Test
    void theDescriptionNamesTheTwoCaps() {
        String description = tool.description();

        assertTrue(description.contains(PlanTool.MAX_STEPS + " steps or fewer"), description);
        assertTrue(description.contains(PlanTool.MAX_TEXT + " characters or fewer"), description);
    }
}
