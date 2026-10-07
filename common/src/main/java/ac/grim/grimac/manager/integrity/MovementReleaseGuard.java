package ac.grim.grimac.manager.integrity;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.player.GrimPlayer;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Applies one existing Grim setback when a confirmed selective stall starts releasing queued movement.
 * It never creates a second movement engine and never cancels ordinary flying packets.
 */
public final class MovementReleaseGuard {
    private final Map<UUID, Long> lastApplied = new ConcurrentHashMap<>();
    private volatile boolean enabled = true;
    private volatile long cooldownNanos = TimeUnit.SECONDS.toNanos(2);
    private volatile long retentionNanos = TimeUnit.MINUTES.toNanos(2);

    public void reload(@NotNull ConfigManager config) {
        enabled = config.getBooleanElse("movement-release-guard.enabled", true);
        cooldownNanos = TimeUnit.MILLISECONDS.toNanos(clamp(config.getLongElse("movement-release-guard.cooldown-ms", 2000L), 250L, 30_000L));
        retentionNanos = TimeUnit.SECONDS.toNanos(clamp(config.getLongElse("movement-release-guard.retention-seconds", 120L), 10L, 600L));
        if (!enabled) lastApplied.clear();
    }

    public boolean apply(@NotNull GrimPlayer player, boolean enoughEvidence) {
        if (!enabled || !enoughEvidence || player.disableGrim) return false;
        long now = System.nanoTime();
        Long previous = lastApplied.put(player.uuid, now);
        if (previous != null && now - previous < cooldownNanos) return false;
        if (player.getSetbackTeleportUtil().shouldBlockMovement()) return false;
        player.getSetbackTeleportUtil().executeNonSimulatingSetback();
        return true;
    }

    public void clear(@NotNull UUID uuid) { lastApplied.remove(uuid); }

    public void tick() {
        long now = System.nanoTime();
        lastApplied.entrySet().removeIf(entry -> now - entry.getValue() > retentionNanos);
    }

    private static long clamp(long value, long min, long max) { return Math.max(min, Math.min(max, value)); }
}
