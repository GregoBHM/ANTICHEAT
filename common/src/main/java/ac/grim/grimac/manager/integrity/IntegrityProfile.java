package ac.grim.grimac.manager.integrity;

import java.util.Locale;

/**
 * Coarse integrity tuning profile for the additional SparkGrim protection layer.
 *
 * <p>The profile never disables Grim's mathematical checks. It only tunes the extra heuristic
 * guards added around packet stalls, recovery windows and correlated actions.</p>
 */
public enum IntegrityProfile {
    SAFE(1.35D, 1.25D, 1),
    BALANCED(1.00D, 1.00D, 0),
    STRICT(0.80D, 0.85D, -1);

    private final double watchMultiplier;
    private final double recoveryMultiplier;
    private final int repeatThresholdAdjustment;

    IntegrityProfile(double watchMultiplier, double recoveryMultiplier, int repeatThresholdAdjustment) {
        this.watchMultiplier = watchMultiplier;
        this.recoveryMultiplier = recoveryMultiplier;
        this.repeatThresholdAdjustment = repeatThresholdAdjustment;
    }

    public long scaleWatchMillis(long millis) {
        return Math.max(1L, Math.round(millis * watchMultiplier));
    }

    public long scaleRecoveryMillis(long millis) {
        return Math.max(1L, Math.round(millis * recoveryMultiplier));
    }

    public int adjustRepeatThreshold(int threshold) {
        return Math.max(1, threshold + repeatThresholdAdjustment);
    }

    public static IntegrityProfile parse(String value) {
        if (value == null) return BALANCED;
        try {
            return IntegrityProfile.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return BALANCED;
        }
    }
}
