package ac.grim.grimac.manager.integrity;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.player.GrimPlayer;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Central confidence policy for heuristic integrity additions.
 *
 * <p>Phase 4A keeps deterministic Grim checks untouched, but makes heuristic additions aware of both
 * server recovery after a tick spike and per-player RTT instability. High ping alone is never treated as
 * cheating; it only reduces the weight of heuristic evidence.</p>
 */
public final class LagProtectionManager {
    private final Map<UUID, PlayerHealth> playerHealth = new ConcurrentHashMap<>();

    private volatile boolean enabled = true;
    private volatile double minimumTps = 17.0D;
    private volatile double degradedTps = 18.5D;
    private volatile long spikeThresholdMillis = 250L;
    private volatile long severeSpikeThresholdMillis = 1000L;
    private volatile double degradedConfidence = 0.70D;
    private volatile double severeConfidence = 0.30D;
    private volatile long spikeRecoveryNanos = TimeUnit.MILLISECONDS.toNanos(2500L);
    private volatile long severeRecoveryNanos = TimeUnit.MILLISECONDS.toNanos(4000L);

    private volatile double highJitterMillis = 120.0D;
    private volatile double severeJitterMillis = 250.0D;
    private volatile double highPingMillis = 300.0D;
    private volatile double severePingMillis = 600.0D;
    private volatile double unstablePlayerConfidence = 0.75D;
    private volatile double severePlayerConfidence = 0.50D;
    private volatile long playerRetentionNanos = TimeUnit.MINUTES.toNanos(2L);

    private volatile long lastTickNanos;
    private volatile long lastTickIntervalMillis = 50L;
    private volatile long lastSpikeNanos;
    private volatile long lastSevereSpikeNanos;
    private volatile long lastPlayerCleanupNanos;

    public void reload(@NotNull ConfigManager config) {
        enabled = config.getBooleanElse("lag-protection.enabled", true);
        minimumTps = clamp(config.getDoubleElse("lag-protection.minimum-tps", 17.0D), 1.0D, 20.0D);
        degradedTps = clamp(config.getDoubleElse("lag-protection.degraded-tps", 18.5D), minimumTps, 20.0D);
        spikeThresholdMillis = (long) clamp(config.getLongElse("lag-protection.tick-spike-ms", 250L), 75L, 5000L);
        severeSpikeThresholdMillis = (long) clamp(config.getLongElse("lag-protection.severe-tick-spike-ms", 1000L), spikeThresholdMillis, 10_000L);
        degradedConfidence = clamp(config.getDoubleElse("lag-protection.degraded-confidence", 0.70D), 0.0D, 1.0D);
        severeConfidence = clamp(config.getDoubleElse("lag-protection.severe-confidence", 0.30D), 0.0D, 1.0D);
        spikeRecoveryNanos = TimeUnit.MILLISECONDS.toNanos(clamp(config.getLongElse("lag-protection.spike-recovery-ms", 2500L), 0L, 15_000L));
        severeRecoveryNanos = TimeUnit.MILLISECONDS.toNanos(clamp(config.getLongElse("lag-protection.severe-spike-recovery-ms", 4000L), 0L, 30_000L));

        highJitterMillis = clamp(config.getDoubleElse("lag-protection.player-network.high-jitter-ms", 120.0D), 5.0D, 2000.0D);
        severeJitterMillis = clamp(config.getDoubleElse("lag-protection.player-network.severe-jitter-ms", 250.0D), highJitterMillis, 5000.0D);
        highPingMillis = clamp(config.getDoubleElse("lag-protection.player-network.high-ping-ms", 300.0D), 20.0D, 5000.0D);
        severePingMillis = clamp(config.getDoubleElse("lag-protection.player-network.severe-ping-ms", 600.0D), highPingMillis, 10_000.0D);
        unstablePlayerConfidence = clamp(config.getDoubleElse("lag-protection.player-network.unstable-confidence", 0.75D), 0.0D, 1.0D);
        severePlayerConfidence = clamp(config.getDoubleElse("lag-protection.player-network.severe-confidence", 0.50D), 0.0D, unstablePlayerConfidence);
        playerRetentionNanos = TimeUnit.SECONDS.toNanos(clamp(config.getLongElse("lag-protection.player-network.retention-seconds", 120L), 15L, 600L));
        if (!enabled) playerHealth.clear();
    }

    /** Called from the exact sync tick path. */
    public void tick() {
        long now = System.nanoTime();
        long previous = lastTickNanos;
        lastTickNanos = now;
        if (previous != 0L) {
            long millis = Math.max(0L, (now - previous) / 1_000_000L);
            lastTickIntervalMillis = millis;
            if (millis >= severeSpikeThresholdMillis) {
                lastSevereSpikeNanos = now;
                lastSpikeNanos = now;
            } else if (millis >= spikeThresholdMillis) {
                lastSpikeNanos = now;
            }
        }
        if (now - lastPlayerCleanupNanos >= TimeUnit.SECONDS.toNanos(10L)) {
            lastPlayerCleanupNanos = now;
            playerHealth.entrySet().removeIf(entry -> now - entry.getValue().lastSampleNanos >= playerRetentionNanos);
        }
    }

    /**
     * Lightweight per-player network sample. Calling this on normal movement traffic builds an EWMA RTT/jitter
     * baseline without scheduling any extra task or allocating per packet.
     */
    public void observePlayer(@NotNull GrimPlayer player) {
        if (!enabled) return;
        long now = System.nanoTime();
        double ping = Math.max(0.0D, player.getTransactionPing());
        PlayerHealth health = playerHealth.computeIfAbsent(player.uuid, ignored -> new PlayerHealth(now, ping));
        if (now - health.lastSampleNanos < TimeUnit.MILLISECONDS.toNanos(25L)) return;
        synchronized (health) {
            if (now - health.lastSampleNanos < TimeUnit.MILLISECONDS.toNanos(25L)) return;
            double previousAverage = health.averagePingMillis;
            if (!health.initialized) {
                health.averagePingMillis = ping;
                health.jitterMillis = 0.0D;
                health.initialized = true;
            } else {
                // EWMA is deliberately smooth: one delayed transaction must not make a legitimate player "unstable".
                health.averagePingMillis = previousAverage * 0.875D + ping * 0.125D;
                double deviation = Math.abs(ping - previousAverage);
                health.jitterMillis = health.jitterMillis * 0.80D + deviation * 0.20D;
            }
            health.lastSampleNanos = now;
        }
    }

    /** Server-only multiplier retained for existing call sites. */
    public double heuristicConfidence() {
        if (!enabled) return 1.0D;
        double tps = GrimAPI.INSTANCE.getPlatformServer().getTPS();
        long interval = lastTickIntervalMillis;
        long now = System.nanoTime();
        if (interval >= severeSpikeThresholdMillis || tps < minimumTps
                || (lastSevereSpikeNanos != 0L && now - lastSevereSpikeNanos <= severeRecoveryNanos)) {
            return severeConfidence;
        }
        if (interval >= spikeThresholdMillis || tps < degradedTps
                || (lastSpikeNanos != 0L && now - lastSpikeNanos <= spikeRecoveryNanos)) {
            return degradedConfidence;
        }
        return 1.0D;
    }

    /** Server + per-player network confidence for heuristic evidence. */
    public double heuristicConfidence(@NotNull GrimPlayer player) {
        observePlayer(player);
        double confidence = heuristicConfidence();
        PlayerHealth health = playerHealth.get(player.uuid);
        if (health == null) return confidence;
        double average;
        double jitter;
        synchronized (health) {
            average = health.averagePingMillis;
            jitter = health.jitterMillis;
        }
        double playerConfidence = 1.0D;
        if (average >= severePingMillis || jitter >= severeJitterMillis) {
            playerConfidence = severePlayerConfidence;
        } else if (average >= highPingMillis || jitter >= highJitterMillis) {
            playerConfidence = unstablePlayerConfidence;
        }
        return Math.min(confidence, playerConfidence);
    }

    /** Includes the configurable post-spike recovery period. */
    public boolean isServerSpike() {
        if (!enabled) return false;
        long now = System.nanoTime();
        return lastTickIntervalMillis >= spikeThresholdMillis
                || (lastSpikeNanos != 0L && now - lastSpikeNanos <= spikeRecoveryNanos)
                || (lastSevereSpikeNanos != 0L && now - lastSevereSpikeNanos <= severeRecoveryNanos);
    }

    public long getLastTickIntervalMillis() {
        return lastTickIntervalMillis;
    }

    public double getAveragePingMillis(@NotNull UUID uuid) {
        PlayerHealth health = playerHealth.get(uuid);
        if (health == null) return 0.0D;
        synchronized (health) {
            return health.averagePingMillis;
        }
    }

    public double getPlayerJitterMillis(@NotNull UUID uuid) {
        PlayerHealth health = playerHealth.get(uuid);
        if (health == null) return 0.0D;
        synchronized (health) {
            return health.jitterMillis;
        }
    }

    public void clear(@NotNull UUID uuid) {
        playerHealth.remove(uuid);
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class PlayerHealth {
        boolean initialized;
        double averagePingMillis;
        double jitterMillis;
        volatile long lastSampleNanos;

        PlayerHealth(long now, double ping) {
            this.initialized = true;
            this.averagePingMillis = ping;
            this.lastSampleNanos = now;
        }
    }
}
