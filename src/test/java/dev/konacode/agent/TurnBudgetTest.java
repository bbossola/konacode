package dev.konacode.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurnBudgetTest {

    @Test
    void startsAtTheOrdinaryMaximum() {
        assertEquals(8, new TurnBudget(8, 24).max());
    }

    @Test
    void extendRaisesTheMaximum() {
        TurnBudget budget = new TurnBudget(8, 24);

        budget.extend();

        assertEquals(24, budget.max());
    }

    @Test
    void aSecondExtendChangesNothing() {
        TurnBudget budget = new TurnBudget(8, 24);

        budget.extend();
        budget.extend();

        assertEquals(24, budget.max());
    }

    @Test
    void resetPutsTheMaximumBack() {
        TurnBudget budget = new TurnBudget(8, 24);
        budget.extend();

        budget.reset();

        assertEquals(8, budget.max(), "the larger maximum ends with the turn that earned it");
    }

    @Test
    void refusesAnOrdinaryMaximumBelowOne() {
        assertThrows(IllegalArgumentException.class, () -> new TurnBudget(0, 24));
    }

    @Test
    void refusesAPlannedMaximumBelowTheOrdinaryOne() {
        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> new TurnBudget(8, 4));

        assertTrue(thrown.getMessage().contains("planned"), thrown.getMessage());
    }

    @Test
    void acceptsAPlannedMaximumEqualToTheOrdinaryOne() {
        TurnBudget budget = new TurnBudget(8, 8);

        budget.extend();

        assertEquals(8, budget.max(), "an agent with no plan tool has one number");
    }
}
