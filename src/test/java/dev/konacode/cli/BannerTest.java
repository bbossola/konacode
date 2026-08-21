package dev.konacode.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BannerTest {

    @Test
    void showsTheArtWhenTheTerminalIsWideEnough() {
        String shown = Banner.forWidth(Banner.WIDTH);

        assertTrue(shown.lines().count() > 1, shown);
        assertTrue(shown.lines().allMatch(line -> line.length() <= Banner.WIDTH), shown);
    }

    @Test
    void endsWithTheVersion() {
        assertTrue(Banner.forWidth(Banner.WIDTH).endsWith(Version.current()),
                Banner.forWidth(Banner.WIDTH));
    }

    @Test
    void knowsItsVersion() {
        assertNotEquals("unknown", Version.current());
    }

    @Test
    void showsThePlainNameWhenTheTerminalIsTooNarrow() {
        assertEquals("kona " + Version.current(), Banner.forWidth(Banner.WIDTH - 1));
    }
}
