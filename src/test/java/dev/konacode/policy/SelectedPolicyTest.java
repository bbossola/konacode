package dev.konacode.policy;

import dev.konacode.tools.Action;
import dev.konacode.tools.Effect;
import org.junit.jupiter.api.Test;


import java.util.Optional;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SelectedPolicyTest {

    private static final ToolPolicy DENIES = new FakePolicy((action, userText) -> Decision.deny("no"));

    private static final Action READ = Action.once("read_file", Effect.READS_INSIDE, "notes.txt");

    @Test
    void itDelegatesToTheChosenPolicy() {
        SelectedPolicy selected = new SelectedPolicy(new AllowAllPolicy());

        assertInstanceOf(Decision.Allow.class, selected.check(READ, "read the notes"));

        selected.select(DENIES);

        assertInstanceOf(Decision.Deny.class, selected.check(READ, "read the notes"));
    }

    @Test
    void itNamesWhatIsChosen() {
        SelectedPolicy selected = new SelectedPolicy(new AllowAllPolicy());

        assertInstanceOf(AllowAllPolicy.class, selected.selected());

        selected.select(DENIES);

        assertEquals(DENIES, selected.selected());
    }

    @Test
    void itPassesTheNameAndTheRefusalThrough() {
        SelectedPolicy selected = new SelectedPolicy(new AllowAllPolicy());

        assertEquals("allow-all", selected.label());
        assertEquals(Optional.empty(), selected.refusal());

        selected.select(new EffectPolicy());

        assertEquals("effect", selected.label());
        assertEquals(Optional.of("refuses every call outside this project"), selected.refusal());
    }

    @Test
    void itRefusesNothing() {
        assertThrows(NullPointerException.class, () -> new SelectedPolicy(null));
        assertThrows(NullPointerException.class,
                () -> new SelectedPolicy(new AllowAllPolicy()).select(null));
    }
}
