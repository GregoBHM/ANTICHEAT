package ac.grim.grimac.manager.integrity;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.player.GrimPlayer;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Preserves server-side fall debt across packet stalls.
 *
 * <p>Vanilla fall distance can be lost when a client freezes movement and later resumes in a way that
 * resets the server's normal bookkeeping. Grim already tracks a predicted fall distance; this manager
 * keeps the maximum trusted value observed around a protected stall and mirrors it back to the platform
 * player until the fall is resolved or a trusted reset condition occurs.</p>
 */
public final class FallIntegrityManager {
    private final Map<UUID, FallState> states = new ConcurrentHashMap<>();
    private final Set<UUID> platformApplyScheduled = ConcurrentHashMap.newKeySet();

    private volatile boolean enabled = true;
    private volatile boolean enforcePlatformFallDistance = true;
    private volatile double minimumDistance = 3.0D;
    private volatile double maximumEnforcedDistance = 256.0D;
    private volatile long recoveryRetentionNanos = TimeUnit.SECONDS.toNanos(8L);
    private volatile long disconnectRetentionNanos = TimeUnit.SECONDS.toNanos(60L);

    public void reload(@NotNull ConfigManager config) {
        enabled = config.getBooleanElse("fall-integrity.enabled", true);
        enforcePlatformFallDistance = config.getBooleanElse("fall-integrity.enforce-platform-fall-distance", true);
        minimumDistance = Math.max(0.0D, config.getDoubleElse("fall-integrity.minimum-distance", 3.0D));
        maximumEnforcedDistance = Math.max(minimumDistance, config.getDoubleElse("fall-integrity.maximum-enforced-distance", 256.0D));
        long retentionMs = clamp(config.getLongElse("fall-integrity.recovery-retention-ms", 8000L), 500L, 60_000L);
        recoveryRetentionNanos = TimeUnit.MILLISECONDS.toNanos(retentionMs);
        long disconnectMs = clamp(config.getLongElse("fall-integrity.disconnect-retention-ms", 60_000L), 1000L, 300_000L);
        disconnectRetentionNanos = TimeUnit.MILLISECONDS.toNanos(disconnectMs);
        if (!enabled) {
            states.clear();
            platformApplyScheduled.clear();
        }
    }

    public void beginProtection(@NotNull UUID uuid, double predictedFallDistance, boolean selectiveEvidence) {
        if (!enabled) return;
        if (GrimAPI.INSTANCE.getMovementContextManager().resetsFallLedger(uuid)) {
            states.remove(uuid);
            return;
        }

        FallState state = states.computeIfAbsent(uuid, ignored -> new FallState());
        synchronized (state) {
            state.protectedStall = true;
            state.selectiveEvidence |= selectiveEvidence;
            state.pendingDistance = Math.max(state.pendingDistance, sanitizeDistance(predictedFallDistance));
            state.enforceUntilNanos = Long.MAX_VALUE;
            state.lastTouchedNanos = System.nanoTime();
        }
    }

    public void updateProtection(@NotNull UUID uuid, double predictedFallDistance) {
        if (!enabled) return;
        FallState state = states.get(uuid);
        if (state == null) return;
        synchronized (state) {
            state.pendingDistance = Math.max(state.pendingDistance, sanitizeDistance(predictedFallDistance));
            state.lastTouchedNanos = System.nanoTime();
        }
    }

    /** Continue enforcing the preserved distance briefly after packet flow returns. */
    public void releaseProtection(@NotNull UUID uuid) {
        FallState state = states.get(uuid);
        if (state == null) return;
        synchronized (state) {
            state.protectedStall = false;
            state.enforceUntilNanos = System.nanoTime() + recoveryRetentionNanos;
            state.lastTouchedNanos = System.nanoTime();
        }
    }

    public double getPendingDistance(@NotNull UUID uuid) {
        FallState state = states.get(uuid);
        if (state == null) return 0.0D;
        synchronized (state) {
            return state.pendingDistance;
        }
    }

    public boolean hasPendingFall(@NotNull UUID uuid) {
        return getPendingDistance(uuid) >= minimumDistance;
    }

    /**
     * Called once per Grim tick. On Bukkit, PlatformPlayer maps this to Player#setFallDistance.
     * Other platforms can leave the interface default no-op until they implement an equivalent bridge.
     */
    public void tickPlayer(@NotNull GrimPlayer player) {
        if (!enabled || player.platformPlayer == null) return;
        UUID uuid = player.uuid;

        if (GrimAPI.INSTANCE.getMovementContextManager().resetsFallLedger(uuid)) {
            clear(uuid);
            return;
        }

        FallState state = states.get(uuid);
        if (state == null) return;

        double pending;
        boolean shouldEnforce;
        long now = System.nanoTime();
        synchronized (state) {
            pending = state.pendingDistance;
            shouldEnforce = state.protectedStall || state.enforceUntilNanos > now;
            if (!shouldEnforce) {
                states.remove(uuid, state);
                return;
            }
        }

        if (!enforcePlatformFallDistance || pending < minimumDistance) return;
        if (!platformApplyScheduled.add(uuid)) return;

        var platformPlayer = player.platformPlayer;
        Runnable cleanup = () -> platformApplyScheduled.remove(uuid);

        GrimAPI.INSTANCE.getScheduler().getEntityScheduler().execute(
                platformPlayer,
                GrimAPI.INSTANCE.getGrimPlugin(),
                () -> {
                    try {
                        if (!enabled || !enforcePlatformFallDistance) {
                            return;
                        }

                        FallState latest = states.get(uuid);
                        if (latest == null) {
                            return;
                        }

                        double latestPending;
                        boolean latestShouldEnforce;
                        long taskNow = System.nanoTime();

                        synchronized (latest) {
                            latestPending = latest.pendingDistance;
                            latestShouldEnforce = latest.protectedStall
                                    || latest.enforceUntilNanos > taskNow;
                        }

                        if (!latestShouldEnforce || latestPending < minimumDistance) {
                            return;
                        }

                        float desired = (float) Math.min(
                                maximumEnforcedDistance,
                                latestPending
                        );
                        float current = platformPlayer.getFallDistance();

                        if (current + 0.01F < desired) {
                            platformPlayer.setFallDistance(desired);
                        }
                    } finally {
                        cleanup.run();
                    }
                },
                cleanup,
                0
        );
    }

    /** Preserve a protected fall briefly across disconnects so freezing then relogging cannot erase it. */
    public void recordDisconnect(@NotNull UUID uuid) {
        FallState state = states.get(uuid);
        if (state == null) return;
        synchronized (state) {
            if (state.pendingDistance < minimumDistance) {
                states.remove(uuid, state);
                return;
            }
            state.protectedStall = false;
            state.enforceUntilNanos = System.nanoTime() + disconnectRetentionNanos;
            state.lastTouchedNanos = System.nanoTime();
        }
    }

    /** Clear when a trusted mechanic makes a normal fall impossible/redefined. */
    public void clear(@NotNull UUID uuid) {
        states.remove(uuid);
    }

    public void tick() {
        if (!enabled) return;
        long now = System.nanoTime();
        states.entrySet().removeIf(entry -> {
            FallState state = entry.getValue();
            synchronized (state) {
                return !state.protectedStall && state.enforceUntilNanos <= now;
            }
        });
    }

    private double sanitizeDistance(double distance) {
        if (!Double.isFinite(distance) || distance <= 0.0D) return 0.0D;
        return Math.min(distance, maximumEnforcedDistance);
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class FallState {
        double pendingDistance;
        boolean protectedStall;
        boolean selectiveEvidence;
        long enforceUntilNanos;
        long lastTouchedNanos;
    }
}
