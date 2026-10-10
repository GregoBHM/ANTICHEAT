package ac.grim.grimac.manager.integrity;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.utils.latency.CompensatedWorld;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import com.github.retrooper.packetevents.util.Vector3d;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Tracks server-cancelled placements long enough to reject client-only ghost-block support. */
public final class CancelledBlockIntegrityManager {
    private static final long AUTHORITATIVE_CANCEL_NANOS = TimeUnit.MILLISECONDS.toNanos(750L);

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

    public void recordCancelledPlacement(@NotNull UUID playerId, @NotNull String worldName, int x, int y, int z) {
        if (!enabled) return;
        long now = System.nanoTime();
        long expires = now + retentionNanos;
        Deque<Entry> deque = entries.computeIfAbsent(playerId, ignored -> new ArrayDeque<>());
        synchronized (deque) {
            cleanupLocked(deque, now);
            String worldKey = normalizeWorldKey(worldName);
            deque.removeIf(entry -> entry.matches(worldKey, x, y, z));
            deque.addLast(new Entry(worldKey, x, y, z, now, expires));
            while (deque.size() > maxEntriesPerPlayer) deque.pollFirst();
        }
    }

    public @Nullable SupportUse findCancelledSupport(
            @NotNull UUID playerId,
            @NotNull String worldName,
            @NotNull CompensatedWorld world,
            @NotNull SimpleCollisionBox currentPlayerBox,
            @NotNull Vector3d from,
            @NotNull Vector3d to
    ) {
        if (!enabled) return null;

        Deque<Entry> deque = entries.get(playerId);
        if (deque == null) return null;

        String worldKey = normalizeWorldKey(worldName);
        long now = System.nanoTime();

        double deltaX = to.getX() - from.getX();
        double deltaY = to.getY() - from.getY();
        double deltaZ = to.getZ() - from.getZ();

        SimpleCollisionBox previousPlayerBox = currentPlayerBox.copy()
                .offset(-deltaX, -deltaY, -deltaZ);

        synchronized (deque) {
            cleanupLocked(deque, now);

            for (Entry entry : deque) {
                if (!entry.worldKey.equals(worldKey)) {
                    continue;
                }

                if (now - entry.createdAtNanos > AUTHORITATIVE_CANCEL_NANOS) {
                    var current = world.getBlock(entry.x, entry.y, entry.z);
                    if (!current.getType().isAir()
                            && !current.getType().isReplaceable()) {
                        continue;
                    }
                }

                boolean previousOverlap = horizontalOverlap(
                        previousPlayerBox,
                        entry.x,
                        entry.z
                );
                boolean currentOverlap = horizontalOverlap(
                        currentPlayerBox,
                        entry.x,
                        entry.z
                );

                double blockTop = entry.y + 1.0D;

                if (CancelledBlockSupportPolicy.isSupportUse(
                        from.getY(),
                        to.getY(),
                        blockTop,
                        previousOverlap,
                        currentOverlap
                )) {
                    return new SupportUse(
                            entry.x,
                            entry.y,
                            entry.z,
                            to.getY() - from.getY()
                    );
                }
            }
        }

        return null;
    }

    public void confirmPlacement(
            @NotNull String worldName,
            int x,
            int y,
            int z
    ) {
        if (!enabled || entries.isEmpty()) return;

        String worldKey = normalizeWorldKey(worldName);

        entries.forEach((uuid, deque) -> {
            synchronized (deque) {
                deque.removeIf(entry -> entry.matches(worldKey, x, y, z));
                if (deque.isEmpty()) {
                    entries.remove(uuid, deque);
                }
            }
        });
    }

    private static boolean horizontalOverlap(
            SimpleCollisionBox box,
            int blockX,
            int blockZ
    ) {
        double epsilon = 1.0E-4D;
        return box.maxX > blockX + epsilon
                && box.minX < blockX + 1.0D - epsilon
                && box.maxZ > blockZ + epsilon
                && box.minZ < blockZ + 1.0D - epsilon;
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

    private static String normalizeWorldKey(String worldName) {
        return worldName.trim().toLowerCase(Locale.ROOT);
    }

    private static void cleanupLocked(Deque<Entry> deque, long now) {
        while (!deque.isEmpty() && deque.peekFirst().expiresAtNanos <= now) deque.pollFirst();
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    public record SupportUse(int x, int y, int z, double deltaY) {
    }

    private static final class Entry {
        final String worldKey;
        final int x;
        final int y;
        final int z;
        final long createdAtNanos;
        final long expiresAtNanos;

        Entry(
                String worldKey,
                int x,
                int y,
                int z,
                long createdAtNanos,
                long expiresAtNanos
        ) {
            this.worldKey = worldKey;
            this.x = x;
            this.y = y;
            this.z = z;
            this.createdAtNanos = createdAtNanos;
            this.expiresAtNanos = expiresAtNanos;
        }

        boolean matches(String worldKey, int x, int y, int z) {
            return this.x == x
                    && this.y == y
                    && this.z == z
                    && this.worldKey.equals(worldKey);
        }
    }
}
