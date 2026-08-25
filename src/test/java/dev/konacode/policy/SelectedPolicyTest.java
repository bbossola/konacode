package dev.konacode.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SelectedPolicyTest {

    private static final ToolPolicy DENIES = (tool, args) -> Decision.deny("no");

    @Test
    void itDelegatesToTheChosenPolicy() {
        SelectedPolicy selected = new SelectedPolicy(new AllowAllPolicy());

        assertInstanceOf(Decision.Allow.class,
                selected.check(null, new ObjectMapper().createObjectNode()));

        selected.select(DENIES);

        assertInstanceOf(Decision.Deny.class,
                selected.check(null, new ObjectMapper().createObjectNode()));
    }

    @Test
    void itNamesWhatIsChosen() {
        SelectedPolicy selected = new SelectedPolicy(new AllowAllPolicy());

        assertInstanceOf(AllowAllPolicy.class, selected.selected());

        selected.select(DENIES);

        assertEquals(DENIES, selected.selected());
    }

    @Test
    void itRefusesNothing() {
        assertThrows(NullPointerException.class, () -> new SelectedPolicy(null));
        assertThrows(NullPointerException.class,
                () -> new SelectedPolicy(new AllowAllPolicy()).select(null));
    }
}
