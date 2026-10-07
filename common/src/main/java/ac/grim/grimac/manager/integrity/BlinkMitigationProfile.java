package ac.grim.grimac.manager.integrity;

import java.util.Locale;

public enum BlinkMitigationProfile {
    SAFE, BALANCED, LOCKDOWN;

    public static BlinkMitigationProfile parse(String value) {
        if (value == null) return BALANCED;
        try { return valueOf(value.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ignored) { return BALANCED; }
    }
}
