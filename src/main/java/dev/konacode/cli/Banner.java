package dev.konacode.cli;

/**
 * The name konacode wears at startup.
 *
 * <p>The art is 41 columns wide. A narrower terminal would wrap every line and show a mess, so
 * a narrow terminal gets the plain name instead.
 *
 * <p>Generated from the art in README.md, and not retyped. A block character that is wrong by one
 * byte is invisible in a diff, and there are hundreds of them.
 */
public final class Banner {

    static final int WIDTH = 41;

    private static final String LOGO = """
             █████
            ░░███
             ░███ █████  ██████  ████████    ██████
             ░███░░███  ███░░███░░███░░███  ░░░░░███
             ░██████░  ░███ ░███ ░███ ░███   ███████
             ░███░░███ ░███ ░███ ░███ ░███  ███░░███
             ████ █████░░██████  ████ █████░░████████
            ░░░░ ░░░░░  ░░░░░░  ░░░░ ░░░░░  ░░░░░░░░""";

    private Banner() {
    }

    static String forWidth(int width) {
        return width >= WIDTH ? LOGO : "kona";
    }
}
