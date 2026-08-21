package dev.konacode.cli;

/**
 * The name konacode wears at startup.
 *
 * <p>The art is 80 columns wide. A narrower terminal would wrap every line and show a mess, so
 * a narrow terminal gets the plain name instead.
 */
public final class Banner {

    static final int WIDTH = 80;

    private static final String LOGO = """
             █████                                                       █████
            ░░███                                                       ░░███
             ░███ █████  ██████  ████████    ██████    ██████   ██████   ░███ █████  ██████
             ░███░░███  ███░░███░░███░░███  ░░░░░███  ███░░███ ███░░███  ░███░░███  ███░░███
             ░██████░  ░███ ░███ ░███ ░███   ███████ ░███ ░░░ ░███ ░███  ░██████░  ░███████
             ░███░░███ ░███ ░███ ░███ ░███  ███░░███ ░███  ███░███ ░███  ░███░░███ ░███░░░
             ████ █████░░██████  ████ █████░░████████░░██████ ░░██████   ████ █████░░██████
            ░░░░ ░░░░░  ░░░░░░  ░░░░ ░░░░░  ░░░░░░░░  ░░░░░░   ░░░░░░   ░░░░ ░░░░░  ░░░░░░""";

    private Banner() {
    }

    static String forWidth(int width) {
        return width >= WIDTH ? LOGO : "konacode";
    }
}
