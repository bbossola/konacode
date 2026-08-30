package dev.konacode.agent;

/**
 * The number of iterations one turn may use.
 *
 * <p>A turn starts at the ordinary maximum. The tool that records a plan calls {@link #extend()},
 * and the turn then runs to the planned maximum. {@code reset} puts the number back, so the larger
 * maximum ends with the turn that earned it.
 *
 * <p>{@code extend} is public, because a tool calls it. {@code reset} and {@code max} are
 * package-private, because only the loop uses them.
 *
 * <p>One budget serves one agent, because {@code reset} states that a new turn starts. A second
 * agent that shares this budget ends the larger maximum of the first agent. A sub-agent, issue
 * #20, must get its own budget.
 */
public final class TurnBudget {

    private final int ordinary;
    private final int planned;
    private int max;

    public TurnBudget(int ordinary, int planned) {
        if (ordinary < 1) {
            throw new IllegalArgumentException("maxIterations must be at least 1.");
        }
        if (planned < ordinary) {
            throw new IllegalArgumentException(
                    "The planned maximum must be at least the ordinary maximum, but was: " + planned);
        }
        this.ordinary = ordinary;
        this.planned = planned;
        this.max = ordinary;
    }

    /** Raises the maximum of this turn. A second call in one turn changes nothing. */
    public void extend() {
        max = planned;
    }

    int max() {
        return max;
    }

    void reset() {
        max = ordinary;
    }
}
