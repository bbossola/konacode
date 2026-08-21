package dev.konacode.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BannerTest {

    @Test
    void showsTheArtWhenTheTerminalIsWideEnough() {
        String shown = Banner.forWidth(Banner.WIDTH);

        assertTrue(shown.lines().count() > 1, shown);
        assertTrue(shown.lines().allMatch(line -> line.length() <= Banner.WIDTH), shown);
    }

    @Test
    void showsThePlainNameWhenTheTerminalIsTooNarrow() {
        assertEquals("konacode", Banner.forWidth(Banner.WIDTH - 1));
    }
}
