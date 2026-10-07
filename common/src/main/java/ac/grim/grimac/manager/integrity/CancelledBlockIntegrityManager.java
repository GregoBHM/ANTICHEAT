package ac.grim.grimac.manager.integrity;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.platform.api.world.PlatformWorld;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Tracks server-cancelled placements long enough to reject client-only ghost-block support. */
public final class CancelledBlockIntegrityManager {
    private final Map<UUID, Deque<Entry>> entries = new ConcurrentHashMap<>();

    private volatile boolean enabled = true;
    private volatile long retentionNanos = TimeUnit.MILLISECONDS.toNanos(2500L);
    private volatile int maxEntriesPerPlayer = 32;

    public void reload(@NotNull ConfigManager config) {
        enabled = config.getBooleanElse("cancelled-block-integrity.enabled", true);
        long retentionMs = clamp(config.getLongElse("cancelled-block-integrity.retention-ms", 2500L), 250L, 10_000L);
        retentionNanos = TimeUnit.MILLISECONDS.toNanos(retentionMs);
        maxEntriesPerPlayer = (int) clamp(config.getLongElse("cancelled-block-integrity.max-entries-per-player", 32L), 4L, 128L);
        if (!enabled) entries.clear();
    }

    public void recordCancelledPlacement(@NotNull UUID playerId, @NotNull UUID worldId, int x, int y, int z) {
        if (!enabled) return;
        long expires = System.nanoTime() + retentionNanos;
        Deque<Entry> deque = entries.computeIfAbsent(playerId, ignored -> new ArrayDeque<>());
        synchronized (deque) {
            cleanupLocked(deque, System.nanoTime());
            deque.addLast(new Entry(worldId.toString(), x, y, z, expires));
            while (deque.size() > maxEntriesPerPlayer) deque.pollFirst();
        }
    }

    public boolean isUsingCancelledSupport(@NotNull UUID playerId,
                                           @NotNull PlatformWorld world,
                                           @NotNull SimpleCollisionBox playerBox) {
        if (!enabled) return false;
        Deque<Entry> deque = entries.get(playerId);
        if (deque == null) return false;
        String worldKey = worldKey(world);
        long now = System.nanoTime();

        synchronized (deque) {
            cleanupLocked(deque, now);
            for (Entry entry : deque) {
                if (!entry.worldKey.equals(worldKey)) continue;

                // The authoritative server block must still be non-supporting; otherwise another plugin/player
                // legitimately placed something there after the cancellation.
                if (!world.getBlockAt(entry.x, entry.y, entry.z).getType().isAir()
                        && !world.getBlockAt(entry.x, entry.y, entry.z).getType().isReplaceable()) {
                    continue;
                }

                double blockMinX = entry.x;
                double blockMaxX = entry.x + 1.0D;
                double blockTopY = entry.y + 1.0D;
                double blockMinZ = entry.z;
                double blockMaxZ = entry.z + 1.0D;

                boolean horizontalOverlap = playerBox.maxX > blockMinX + 1.0E-4
                        && playerBox.minX < blockMaxX - 1.0E-4
                        && playerBox.maxZ > blockMinZ + 1.0E-4
                        && playerBox.minZ < blockMaxZ - 1.0E-4;
                boolean feetAtGhostTop = playerBox.minY >= blockTopY - 0.08D
                        && playerBox.minY <= blockTopY + 0.35D;

                if (horizontalOverlap && feetAtGhostTop) return true;
            }
        }
        return false;
    }

    public void clear(@NotNull UUID playerId) {
        entries.remove(playerId);
    }

    public void tick() {
        if (!enabled) return;
        long now = System.nanoTime();
        entries.forEach((uuid, deque) -> {
            synchronized (deque) {
                cleanupLocked(deque, now);
                if (deque.isEmpty()) entries.remove(uuid, deque);
            }
        });
    }

    private static String worldKey(PlatformWorld world) {
        UUID uid = world.getUID();
        return uid != null ? uid.toString() : world.getName();
    }

    private static void cleanupLocked(Deque<Entry> deque, long now) {
        while (!deque.isEmpty() && deque.peekFirst().expiresAtNanos <= now) deque.pollFirst();
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class Entry {
        final String worldKey;
        final int x;
        final int y;
        final int z;
        final long expiresAtNanos;

        Entry(String worldKey, int x, int y, int z, long expiresAtNanos) {
            this.worldKey = worldKey;
            this.x = x;
            this.y = y;
            this.z = z;
            this.expiresAtNanos = expiresAtNanos;
        }
    }
}
