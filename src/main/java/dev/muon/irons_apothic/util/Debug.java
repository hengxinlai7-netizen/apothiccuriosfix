package dev.muon.irons_apothic.util;

import dev.muon.irons_apothic.IronsApothic;

/**
 * Debug logging gate. Everything in this mod is silent unless the JVM system property
 * {@code -Dirons_apothic.debug=true} is set (also honoured when it is set through the
 * Forge client/server launch args).
 */
public final class Debug {

    public static final String PROPERTY = "irons_apothic.debug";

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
            IronsApothic.LOGGER.info("[irons_apothic][debug] " + message);
        } else {
            IronsApothic.LOGGER.info("[irons_apothic][debug] " + String.format(message, args));
        }
    }
}
