package dev.konacode.cli;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * The version, as Maven wrote it at build time.
 *
 * <p>Maven fills in `konacode.properties` from the project version, so the number cannot drift
 * from the one in the pom.
 */
public final class Version {

    private static final String VALUE = read();

    private Version() {
    }

    public static String current() {
        return VALUE;
    }

    private static String read() {
        try (InputStream in = Version.class.getResourceAsStream("/konacode.properties")) {
            if (in == null) {
                return "unknown";
            }
            Properties properties = new Properties();
            properties.load(in);
            return properties.getProperty("version", "unknown");
        } catch (IOException e) {
            return "unknown";
        }
    }
}
