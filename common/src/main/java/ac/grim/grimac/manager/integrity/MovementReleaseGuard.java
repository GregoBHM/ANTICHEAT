package ac.grim.grimac.manager.integrity;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.LogUtil;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MovementReleaseGuard {
    private static final AtomicBoolean CONFIG_ERROR_LOGGED = new AtomicBoolean();

    private final Map<UUID, Long> lastApplied = new ConcurrentHashMap<>();
    private final Object applyLock = new Object();

    private boolean enabled;
    private long cooldownNanos;
    private long retentionNanos;

    public void reload(@NotNull ConfigManager config) {
        try {
            enabled = requireBoolean(
                    config,
                    "movement-release-guard.enabled"
            );

            long cooldownMillis = requirePositiveLong(
                    config,
                    "movement-release-guard.cooldown-ms"
            );

            long retentionSeconds = requirePositiveLong(
                    config,
                    "movement-release-guard.retention-seconds"
            );

            cooldownNanos = TimeUnit.MILLISECONDS.toNanos(cooldownMillis);
            retentionNanos = TimeUnit.SECONDS.toNanos(retentionSeconds);
            CONFIG_ERROR_LOGGED.set(false);

            if (!enabled) {
                synchronized (applyLock) {
                    lastApplied.clear();
                }
            }
        } catch (RuntimeException ex) {
            enabled = false;

            synchronized (applyLock) {
                lastApplied.clear();
            }

            if (CONFIG_ERROR_LOGGED.compareAndSet(false, true)) {
                LogUtil.error(
                        "MovementReleaseGuard configuration is invalid. The guard is disabled until the config is fixed and Grim is reloaded.",
                        ex
                );
            }
        }
    }

    public boolean apply(@NotNull GrimPlayer player, boolean enoughEvidence) {
        if (!enabled || !enoughEvidence || player.disableGrim) {
            return false;
        }

        final long now = System.nanoTime();

        synchronized (applyLock) {
            Long previous = lastApplied.get(player.uuid);

            if (previous != null && now - previous < cooldownNanos) {
                return false;
            }

            if (player.getSetbackTeleportUtil().shouldBlockMovement()) {
                return false;
            }

            player.getSetbackTeleportUtil().executeNonSimulatingSetback();
            lastApplied.put(player.uuid, now);
            return true;
        }
    }

    public void clear(@NotNull UUID uuid) {
        lastApplied.remove(uuid);
    }

    public void tick() {
        long now = System.nanoTime();
        lastApplied.entrySet().removeIf(entry -> now - entry.getValue() > retentionNanos);
    }

    private static boolean requireBoolean(ConfigManager config, String key) {
        Object raw = config.get(key);

        if (!(raw instanceof Boolean value)) {
            throw invalidConfig(key, "boolean", raw);
        }

        return value;
    }

    private static long requirePositiveLong(ConfigManager config, String key) {
        Object raw = config.get(key);

        if (!(raw instanceof Number number)) {
            throw invalidConfig(key, "positive integer", raw);
        }

        long value = number.longValue();

        if (value <= 0L) {
            throw invalidConfig(key, "positive integer", raw);
        }

        return value;
    }

    private static IllegalStateException invalidConfig(String key, String expected, Object actual) {
        String actualType = actual == null ? "missing" : actual.getClass().getSimpleName();

        return new IllegalStateException(
                "Invalid SparkGrim config key '" + key + "': expected "
                        + expected + ", got " + actualType
                        + ". Update the bundled config to config-version 15."
        );
    }
}
