package ac.grim.grimac.manager.integrity;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.config.ConfigManager;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Staff-alert presentation gate. Internal flags, punishment history, logs and verbose listeners are untouched.
 * Only repeated NORMAL chat alerts are aggregated by UUID + stable check key.
 */
public final class AlertAggregationManager {
    private final Map<Key, State> states = new ConcurrentHashMap<>();

    private volatile boolean enabled = true;
    private volatile boolean aggregation = true;
    private volatile boolean burstSummary = true;
    private volatile long cooldownNanos = TimeUnit.MILLISECONDS.toNanos(1500L);
    private volatile long retentionNanos = TimeUnit.MINUTES.toNanos(10L);
    private volatile int minimumViolationGap = 5;
    private volatile int[] milestones = new int[] {1, 5, 10, 15, 20};
    private volatile double mediumThreshold = 4.0D;
    private volatile double highThreshold = 8.0D;
    private volatile double criticalThreshold = 14.0D;

    public void reload(@NotNull ConfigManager config) {
        enabled = config.getBooleanElse("alerts.enabled", true);
        aggregation = config.getBooleanElse("alerts.aggregation", true);
        burstSummary = config.getBooleanElse("alerts.burst-summary", true);
        cooldownNanos = TimeUnit.MILLISECONDS.toNanos(clamp(config.getLongElse("alerts.cooldown-ms", 1500L), 0L, 60_000L));
        retentionNanos = TimeUnit.SECONDS.toNanos(clamp(config.getLongElse("alerts.state-retention-seconds", 600L), 30L, 3600L));
        minimumViolationGap = (int) clamp(config.getLongElse("alerts.minimum-vl-gap", 5L), 1L, 1000L);
        mediumThreshold = Math.max(0.0D, config.getDoubleElse("alerts.severity.medium", 4.0D));
        highThreshold = Math.max(mediumThreshold, config.getDoubleElse("alerts.severity.high", 8.0D));
        criticalThreshold = Math.max(highThreshold, config.getDoubleElse("alerts.severity.critical", 14.0D));

        java.util.List<String> configured = config.getStringListElse("alerts.milestones", java.util.List.of("1", "5", "10", "15", "20"));
        int[] parsed = configured.stream().mapToInt(value -> {
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }).filter(value -> value > 0).distinct().sorted().toArray();
        milestones = parsed.length == 0 ? new int[] {1, 5, 10, 15, 20} : parsed;
        if (!enabled || !aggregation) states.clear();
    }

    public @NotNull Decision evaluate(@NotNull UUID uuid, @NotNull String stableKey, int violationLevel) {
        if (!enabled) return new Decision(false, 0);
        if (!aggregation) return new Decision(true, 0);
        final long now = System.nanoTime();
        final Key key = new Key(uuid, stableKey == null || stableKey.isEmpty() ? "unknown" : stableKey);
        final State state = states.computeIfAbsent(key, ignored -> new State(now));

        synchronized (state) {
            state.lastTouchedNanos = now;

            // A server tick spike can make many players fail heuristic movement checks at once.
            // Keep every internal flag/log/history entry, but do not fan that one server event
            // out into a wall of normal staff alerts. Verbose listeners remain untouched.
            if (isSpikeSensitive(stableKey) && GrimAPI.INSTANCE.getLagProtectionManager().isServerSpike()) {
                state.suppressed++;
                return new Decision(false, state.suppressed);
            }

            if (!state.sentOnce) {
                int suppressed = state.suppressed;
                state.suppressed = 0;
                state.sentOnce = true;
                state.lastSentNanos = now;
                state.lastSentViolation = violationLevel;
                return new Decision(true, suppressed);
            }

            // Punishment VL is rolling-window based and can drop after old violations expire. Treat a lower
            // value as a new alert episode instead of comparing it forever against the previous high watermark.
            if (violationLevel < state.lastSentViolation) {
                int suppressed = state.suppressed;
                state.suppressed = 0;
                state.lastSentNanos = now;
                state.lastSentViolation = violationLevel;
                return new Decision(true, suppressed);
            }

            boolean milestone = Arrays.binarySearch(milestones, violationLevel) >= 0;
            boolean enoughViolations = violationLevel - state.lastSentViolation >= minimumViolationGap;
            boolean cooldownElapsed = now - state.lastSentNanos >= cooldownNanos;
            if (milestone || (cooldownElapsed && enoughViolations)) {
                int suppressed = state.suppressed;
                state.suppressed = 0;
                state.lastSentNanos = now;
                state.lastSentViolation = violationLevel;
                return new Decision(true, suppressed);
            }

            state.suppressed++;
            return new Decision(false, state.suppressed);
        }
    }

    public @NotNull String suppressedSuffix(int suppressed) {
        return burstSummary && suppressed > 0 ? " &8(+" + suppressed + ")" : "";
    }

    public @NotNull IntegritySeverity severity(@NotNull UUID uuid) {
        double score = GrimAPI.INSTANCE.getIntegrityCorrelationManager().getScore(uuid);
        if (score >= criticalThreshold) return IntegritySeverity.CRITICAL;
        if (score >= highThreshold) return IntegritySeverity.HIGH;
        if (score >= mediumThreshold) return IntegritySeverity.MEDIUM;
        return IntegritySeverity.LOW;
    }

    public void clear(@NotNull UUID uuid) {
        states.keySet().removeIf(key -> key.uuid.equals(uuid));
    }

    public void tick() {
        if (!enabled || !aggregation) return;
        long now = System.nanoTime();
        states.entrySet().removeIf(entry -> now - entry.getValue().lastTouchedNanos >= retentionNanos);
    }


    private static boolean isSpikeSensitive(@NotNull String stableKey) {
        return "grim.prediction.simulation".equals(stableKey)
                || "grim.timer.packet_burst".equals(stableKey)
                || "grim.interaction.inventory_frequency".equals(stableKey)
                || "grim.combat.attack_burst".equals(stableKey);
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    public record Decision(boolean send, int suppressedSinceLastAlert) {}

    private record Key(UUID uuid, String stableKey) {}

    private static final class State {
        boolean sentOnce;
        int suppressed;
        int lastSentViolation;
        long lastSentNanos;
        volatile long lastTouchedNanos;

        State(long now) {
            lastTouchedNanos = now;
        }
    }
}
