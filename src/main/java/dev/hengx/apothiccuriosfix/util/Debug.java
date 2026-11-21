package dev.hengx.apothiccuriosfix.util;

import dev.hengx.apothiccuriosfix.ApothicCuriosFix;

/**
 * Debug logging gate.
 *
 * <p>This mod is completely silent on a normal launch (the project convention for every patch mod in this
 * pack). Everything diagnostic is printed only when the JVM system property
 * {@code -Dapothiccuriosfix.debug=true} is set, either on the java command line or through the launcher's
 * "JVM arguments" field.</p>
 */
public final class Debug {

    public static final String PROPERTY = "apothiccuriosfix.debug";

    private Debug() {
    }

    public static boolean enabled() {
        String value = System.getProperty(PROPERTY);
        return value != null && (value.isEmpty() || Boolean.parseBoolean(value));
    }

    public static void log(String message, Object... args) {
        if (!enabled()) {
            return;
        }
        if (args.length == 0) {
            ApothicCuriosFix.LOGGER.info("[apothiccuriosfix][debug] " + message);
        } else {
            ApothicCuriosFix.LOGGER.info("[apothiccuriosfix][debug] " + String.format(message, args));
        }
    }
}
