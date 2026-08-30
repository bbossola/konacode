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

## The tool

`PlanTool` implements `Tool`. It holds no state, in the way `ReadFile` holds no state.

| Method | Answer |
|---|---|
| `name()` | `plan` |
| `description()` | The prompt text below |
| `inputSchema()` | One array, `steps`, of objects with `text` and `state`. `Schemas` gains one method for an array of objects. |
| `execute(args)` | Gives the list back as text |
| `stopsOnInterrupt()` | `false`. The tool stores a list, so it has no step to stop between. |
| `computeAction(args)` | A safe call. See below. |

### The description

The description is prompt text. It tells the model when to call the tool.

```
Record the steps of the work you are going to do, and give the list back.
Use this before work that needs more than two or three tool calls.
Write one short step for each thing you must do.
Each step has a state: todo, doing or done. Keep one step doing at a time.
Call this tool again each time a step starts and each time a step finishes, and send the
whole list every time.
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

A malformed argument is an `Err`, in the way every other tool answers. An empty list is an `Err`,
because a plan with no step states nothing.

`execute` raises the maximum number of iterations only when it answers `Ok`. A call that fails
states no plan, so it earns no iteration.

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

Two consequences follow. konacode asks the user nothing about a plan call. The judge reads only
the calls that konacode asks about, so the judge never reads a plan call. A plan call costs no
model call, and the user waits no longer.

## The maximum number of iterations

`Agent` holds an `int` today, and the loop reads it on each iteration.

konacode replaces the `int` with one object, and the tool raises the maximum itself. The loop
gains no line, and the loop reads no effect.

```java
package dev.konacode.tools;

/** The number of iterations one turn may use. The tool that plans the turn raises it. */
public interface Budget {
    void extend();
}
```

```java
package dev.konacode.agent;

public final class TurnBudget implements Budget {
    // extend() is public, because a tool calls it.
    // reset() and max() are package-private, because only the loop may use them.
}
```

`TurnBudget` holds two numbers, and one number for the turn that runs now. `extend` sets the
number of this turn to the larger one, and a second call changes nothing. `reset` puts the number
back to the smaller one, and `Agent.respond` calls it once for each turn. So the larger maximum
ends with the turn that earned it.

This is the shape of `StopCheck`, with the direction reversed. `Cancellation` lives in `agent`,
`StopCheck` lives in `tools`, and the tools read it. Here the budget lives in `agent`, `Budget`
lives in `tools`, and one tool writes to it. `agent` already depends on `tools`, so this closes no
cycle.

`Agent` takes a `TurnBudget` where it takes an `int` today. The check that the number is at least
one moves to `TurnBudget`, because the object owns both numbers now.

`Main` builds one `TurnBudget`. It gives it to `PlanTool` and to `Agent`. The registry is built
before the loop, so the budget must exist before both. That is why `Agent` does not implement
`Budget` itself.

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

1. The model calls `plan` with three steps. The first step is `doing`. The loop appends the list.
   `TurnBudget` raises the maximum of this turn to 24.
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

**The loop reads the effect.** The loop computes the `Action` of every call, so it could read the
sixth value and raise the maximum itself. `Action` exists for the policy. Two readers of one value
for two purposes make the value harder to change later, and the loop gains a branch.

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

## Tests

| Test | What it proves |
|---|---|
| `PlanToolTest` | The tool gives the list back, one line for each step. |
| `PlanToolTest` | A malformed argument is an `Err`. An empty list is an `Err`. |
| `PlanToolTest` | `computeAction` answers `NONE`, an empty operand and no permission. |
| `PlanToolTest` | `execute` raises the budget. |
| `EffectPolicyTest` | `NONE` gives `Allow`. |
| `TurnBudgetTest` | `extend` raises the maximum. A second `extend` changes nothing. |
| `TurnBudgetTest` | `reset` puts the maximum back. |
| `AgentTest` | A turn that calls `plan` runs more than 8 iterations. |
| `AgentTest` | A turn that calls no plan stops at 8 iterations. |
| `AgentTest` | The turn after a planned turn stops at 8 iterations. |
| `MainTest` | The prompt holds the planning sentence, and it holds five lines or fewer. |
| `MainTest` | A planned maximum below the ordinary maximum exits 1. |
