# The plan — design

Issue [#19](https://github.com/bbossola/konacode/issues/19), the first two items of the checklist.

## Problem

The loop is a primitive form of harness-side reasoning. It observes, it acts, and it observes
again. It does no more than that.

Two costs follow. The model holds no statement of what it intends to do, so it drifts across a
long turn, and it stops before the work is done. konacode holds one maximum number of iterations
for every turn, and the default is 8. That number is enough for read-read-edit. It is too small
for work that needs ten steps.

konacode cannot raise the number for every turn. A model that repeats itself on a short question
then uses all of it, and the user pays for every iteration.

## Decision

konacode adds one tool. The model calls `plan` with the steps of the work. The tool gives the
list back, and the loop appends it to the conversation. The model reads its own plan on each
iteration, because konacode sends the whole conversation on each request.

A turn in which the model calls `plan` gets a larger maximum number of iterations. Every other
turn keeps the maximum of 8.

konacode stores no plan. The conversation holds it.

## What a plan is

A plan is a list of steps. A step is one sentence and a state. The state is `todo`, `doing` or
`done`.

A plan is not a list of calls. konacode never runs a step. The model does the work with the other
tools, one call at a time, in the loop it uses today. The word `Action` is taken: it means the
fact a tool states about one call, and the policy reads it.

## Where the tool lives

`PlanTool` lives in the package `agent`, beside `Agent`. It is the only tool outside `tools`.

The five tools in `tools` act on the world. They read a file, they write a file and they run a
command. `PlanTool` acts on the turn, and the turn belongs to the loop. So the tool lives with the
turn.

A class in `agent` may implement `Tool`, because `agent` imports `tools`. So this needs no
interface, and it breaks no package rule.

Two designs that put `PlanTool` in `tools` were rejected. Both are in the section below. Issue
[#41](https://github.com/bbossola/konacode/issues/41) discusses the rule that decides where a tool
lives, together with the interfaces of konacode.

## The tool

`PlanTool` implements `Tool`. It holds one collaborator, a `TurnBudget`, in the way `ReadFile`
holds a `Workspace`. It holds no state.

| Method | Answer |
|---|---|
| `name()` | `plan` |
| `description()` | The prompt text below |
| `inputSchema()` | One array, `steps`, of objects with `text` and `state`. `Schemas` gains one method for an array of objects. |
| `execute(args)` | Raises the maximum, and gives the list back as text |
| `stopsOnInterrupt()` | `false`. The tool stores a list, so it has no step to stop between. |
| `computeAction(args)` | A safe call. See below. |

### The description

The description is prompt text. It tells the model when to call the tool.

```
Record the steps of the work you are going to do, and give the list back.
Use this before work that needs more than two or three tool calls.
Write one short step for each thing you must do. Write 20 steps or fewer, and keep each
step to 200 characters or fewer.
Each step has a state: todo, doing or done. Keep one step doing at a time.
Call this tool again at each change: mark the step you finished done, mark the next step
doing, and send the whole list in one call.
This tool changes no file and runs no command. It records what you intend to do, and you
read it again on the next step.
```

The description says nothing about the maximum number of iterations. A model that reads "a plan
gives more iterations" calls `plan` to get the iterations, and not to plan.

### The result

`execute` gives back the list as text, one line for each step.

```
1. [doing] find every use of respond
2. [todo]  edit the files that use it
3. [todo]  run mvn test
```

`execute` answers an `Err` for a call it cannot read, in the way every other tool answers. Each
`Err` names one fault, and it names the step that holds it. An `Err` is prompt text: one message
for six faults tells the model the shape it already sent, and the model must then read a plan of
ten steps again to find the one word that failed. `EditFile` and `DeleteFile` both make this split.

| Fault | The message |
|---|---|
| `steps` is missing, or it is not an array | The shape of the call, in the way `DeleteFile` answers |
| A step is not an object | The shape of the call. The model sent a string where an object goes, so the shape is the fault. |
| The list is empty | The plan has no step. Send at least one step. |
| The list holds more than 20 steps | The plan has N steps. Send 20 steps or fewer. |
| A step has no text | Step N has no text. Give one short sentence for each step. |
| The text of a step is longer than 200 characters | Step N is M characters. Keep a step to 200 characters or fewer. |
| A step has a state konacode does not know | Step N has a state konacode does not know. Use todo, doing or done. |

No message repeats a word the model wrote. The state of step N is a word the model chose, so the
message names the step and never the word. A number is not a word the model wrote, so a message
gives the number of steps and the number of characters.

Each message names the same limit as the check. The check refuses 21 steps and 201 characters, so
the message says "20 steps or fewer" and "200 characters or fewer". "Under 200" would name 199.

Two caps hold the size: 20 steps, and 200 characters for the text of one step. Every other tool
caps what it gives back. This one gives its result back on every later iteration of the turn,
because konacode sends the whole conversation each time.

The text of a step becomes one line. A newline in the text draws a second numbered line, and that
line is not konacode's. `RunCommand` answers the same problem for `<exit N>`.

### The code

```java
private final TurnBudget budget;

public PlanTool(TurnBudget budget) {
    this.budget = budget;
}

@Override
public ToolResult execute(JsonNode args) {
    List<Step> steps = readSteps(args);   // this answers an Err when the list is missing or empty
    budget.extend();
    return ToolResult.ok(render(steps));
}
```

The order matters. `execute` reads the steps first. A bad call answers the `Err` and stops, so it
never calls `extend()`. A good call raises the maximum, and then it answers `Ok`.

Nothing tests a value here. The tool makes the decision, so konacode needs no branch anywhere.

### What the tool states about one call

`computeAction` answers a safe call.

| Part | Value |
|---|---|
| `toolName` | `plan` |
| `effect` | `NONE`, the sixth value |
| `toolOperand` | The empty string. There is no path and no command line to name. |
| `standingPermission` | Empty. No standing "always" can describe a call that nobody is asked about. |

`Effect` gains a sixth value, `NONE`. The five values today all name the world outside the
session. A plan call reaches nothing outside the session, so no value today is true.
`EffectPolicy.check` is a switch with no `default`, so the compiler names the switch when the
value arrives. The answer there is one line: `case NONE -> Decision.allow()`.

The budget reads nothing here. `NONE` states what the call does, and the policy is its one reader.

Two consequences follow. konacode asks the user nothing about a plan call. The judge reads only
the calls that konacode asks about, so the judge never reads a plan call. A plan call costs no
model call, and the user waits no longer.

## The maximum number of iterations

`Agent` holds an `int` today, and the loop reads it on each iteration.

```java
private final int maxIterations;
...
for (; iterations <= maxIterations; iterations++) {
```

konacode replaces the number with one object, `TurnBudget`, in the package `agent`.

```java
budget.reset();                                    // once, at the start of a turn
for (; iterations <= budget.max(); iterations++) {
```

`TurnBudget` holds two numbers and the number of the turn that runs now. `extend` sets the number
of this turn to the larger one, and a second call changes nothing. `reset` puts the number back to
the smaller one, and `Agent.respond` calls it once for each turn. So the larger maximum ends with
the turn that earned it.

`extend` is public, because `PlanTool` calls it. `reset` and `max` are package-private, because
only the loop uses them. `PlanTool` and `Agent` are in one package, so `PlanTool` sees all three,
and no class outside `agent` sees any of them.

`Agent` takes a `TurnBudget` where it takes an `int` today. The check that the number is at least
one moves to `TurnBudget`, because that class owns both numbers now.

`Main` builds one `TurnBudget`. `PlanTool` and `Agent` share it.

```java
TurnBudget budget = new TurnBudget(maxIterations, plannedMaxIterations);

ToolRegistry registry = ToolRegistry.of(
        new ListFiles(workspace, cancellation),
        new ReadFile(workspace, cancellation),
        new EditFile(workspace, cancellation),
        new DeleteFile(workspace),
        new RunCommand(workspace, cancellation, commandTimeout),
        new PlanTool(budget));

Agent agent = new Agent(client, registry, policies, approvals, conversation, kona,
        cancellation, budget);
```

## Configuration

konacode gains one property. The rule in CLAUDE.md holds: a property configures konacode, and a
wrong value prints one line and exits 1.

| Name | Kind | Required | Default |
|---|---|---|---|
| `konacode.maxIterations` | property | no | `8` |
| `konacode.maxIterations.planned` | property | no | `24` |

A planned maximum below the ordinary maximum is a wrong value. konacode prints one line and exits
1, because a plan must never reduce the number of iterations.

## The system prompt

The prompt gains one sentence, and it reaches five lines.

```
Plan with the plan tool before work that needs more than two or three tool calls.
```

`MainTest` pins the sentence, in the way it pins the other three facts. The test that holds the
prompt to five lines then sits at its limit. A sixth line needs a decision about the prompt, and
not one more sentence.

## What the user reads

konacode shows a plan call with the trace line it shows for every other tool call.

```
kona> tool plan {"steps":[{"text":"find every use of respond","state":"done"},{"text":"edit …
```

`RichUi` cuts the line to the width of the terminal. `TraceLine` gains no case for one tool, and
konacode gains no trace event. The trace already reports every tool call, so the user already
reads what the model does.

## One turn

The user types `rename respond to answer across the project`.

1. The model calls `plan` with three steps. The first step is `doing`. `PlanTool` raises the
   maximum of this turn to 24. The loop appends the list.
2. The model calls `run_command` with `grep -rn respond src`.
3. The model calls `plan` again. Step 1 is `done`, and step 2 is `doing`.
4. The model calls `read_file` and `edit_file` for each file.
5. The model calls `plan` again. Step 2 is `done`, and step 3 is `doing`.
6. The model calls `run_command` with `mvn -q test`.
7. The model calls `plan` again. Every step is `done`.
8. The model answers with text, and the turn ends.

The conversation holds four plan messages. The last one is the plan. The next turn starts with the
maximum of 8 again.

## The costs

- A plan adds three or four requests to a turn. Each request costs time and money.
- The model writes the whole list each time, so the conversation becomes longer.
- Nothing makes the model obey the plan. konacode checks no step, and it refuses no call that the
  plan does not cover.
- A short turn that plans pays these costs and gets nothing. The description must keep the model
  from planning a job of one step.

## Rejected alternatives

**A `Plan` class that holds the list.** konacode would store the steps, and the tool would hold
state. The conversation already holds every tool result, so the class would keep a second copy of
the same list. `/clear` would then need a line to remove it.

**A `Budget` interface in `tools`, with `TurnBudget` in `agent`.** An interface declares where a
concept lives. `Budget` in `tools` says that the tools own the idea of the budget, and that the
loop implements an idea of the tools. The loop owns the turn, so that is backwards. It also adds
one more interface of the kind that #41 asks konacode to remove.

**`TurnBudget` in `tools`.** This removes the interface, and it puts a concept of the loop in the
package of the tools.

**The loop hands the action to the budget.** `Agent` computes the `Action` of every call, so it
could pass it to `TurnBudget`, and `TurnBudget` could switch on the effect. It needs no interface.
It adds a branch, and the effect value must then say `PLANS` and serve two readers. The tool
already knows that it plans, so the branch answers a question that nobody needs to ask.

**A tool name in the loop.** `Agent` holds no tool name today. One literal there ends that
property for ever.

**A checklist on the screen.** `TraceLine` would hold a case for one named tool, and it would read
the arguments of that tool. That is the first special case for one tool inside the interface. The
trace already reports the call.

**konacode runs the steps.** The plan would become a list of calls, and konacode would execute a
list that the model wrote in advance. The policy would then decide about calls that the model
never made one at a time.

**The loop refuses the first call of a turn when no plan exists.** This makes every turn plan,
including a turn that reads one file. It is item 4 of the epic, and it needs its own design.

## Out of scope

- Item 3 of the epic: the loop answers a failed call with corrective guidance.
- Item 4 of the epic: plan and act become two phases.
- Issue #20, sub-agents.
- Issue #18, `/compact`. The plan is the message a compaction should keep, and that decision
  belongs to #18.
- Issue #41, the interfaces and the rule for where a tool lives.

## Tests

| Test | What it proves |
|---|---|
| `PlanToolTest` | The tool gives the list back, one line for each step. |
| `PlanToolTest` | Each of the six faults gives its own `Err`, and the message names the step. |
| `PlanToolTest` | A step of two lines becomes one line. |
| `PlanToolTest` | The description holds the guard against a plan of one step, and the cap of 20. |
| `PlanToolTest` | `computeAction` answers `NONE`, an empty operand and no permission. |
| `PlanToolTest` | A good call raises the maximum. A failed call leaves it. |
| `EffectPolicyTest` | `NONE` gives `Allow`. |
| `TurnBudgetTest` | `extend` raises the maximum. A second `extend` changes nothing. |
| `TurnBudgetTest` | `reset` puts the maximum back. |
| `AgentTest` | A turn that calls `plan` runs more than 8 iterations. |
| `AgentTest` | A turn that calls no plan stops at 8 iterations. |
| `AgentTest` | The turn after a planned turn stops at 8 iterations. |
| `MainTest` | The prompt holds the planning sentence, and it holds five lines or fewer. |
| `TurnBudgetTest` | A planned maximum below the ordinary maximum is refused. `Main` builds the budget inside the `try` that reads the configuration, so konacode prints one line and exits 1. |
