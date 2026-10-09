package ac.grim.grimac.manager.integrity;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Stores short-lived trusted movement contexts created by server plugins.
 *
 * <p>This is deliberately not a permission-based bypass system. Contexts expire automatically,
 * are attached to a source name, and only affect integrity behavior declared by the context type.</p>
 */
public final class MovementContextManager {
    private static final long MAX_CONTEXT_MILLIS = 15_000L;
    private static final long MAX_TRUSTED_VELOCITY_MILLIS = 1_500L;

    private final Map<UUID, CopyOnWriteArrayList<Context>> contexts = new ConcurrentHashMap<>();

    public Context begin(@NotNull UUID uuid,
                         @NotNull MovementContextType type,
                         @NotNull String source,
                         long durationMillis) {
        return beginTrusted(uuid, type, source, durationMillis, null);
    }

    public Context begin(@NotNull UUID uuid,
                         @NotNull MovementContextType type,
                         @NotNull String source,
                         long durationMillis,
                         @Nullable String detail) {
        return beginTrusted(uuid, type, source, durationMillis, detail);
    }

    public Context beginTrusted(@NotNull UUID uuid, @NotNull MovementContextType type, @NotNull String source, long durationMillis) {
        return beginTrusted(uuid, type, source, durationMillis, null);
    }

    public Context beginTrusted(@NotNull UUID uuid, @NotNull MovementContextType type, @NotNull String source, long durationMillis, @Nullable String detail) {
        return beginInternal(uuid, type, source, durationMillis, detail, true);
    }

    public Context beginObserved(@NotNull UUID uuid, @NotNull MovementContextType type, @NotNull String source, long durationMillis) {
        return beginObserved(uuid, type, source, durationMillis, null);
    }

    public Context beginObserved(@NotNull UUID uuid, @NotNull MovementContextType type, @NotNull String source, long durationMillis, @Nullable String detail) {
        return beginInternal(uuid, type, source, durationMillis, detail, false);
    }

    private Context beginInternal(@NotNull UUID uuid, @NotNull MovementContextType type, @NotNull String source, long durationMillis, @Nullable String detail, boolean trusted) {
        long maximum = trusted && type.allowsTrustedVelocityOverride() ? MAX_TRUSTED_VELOCITY_MILLIS : MAX_CONTEXT_MILLIS;
        long duration = Math.max(1L, Math.min(maximum, durationMillis));
        long now = System.nanoTime();
        long expiresAtNanos = now + TimeUnit.MILLISECONDS.toNanos(duration);
        String normalizedSource = sanitizeSource(source);
        Context context = new Context(type, normalizedSource, sanitizeDetail(detail), trusted, now, expiresAtNanos);
        CopyOnWriteArrayList<Context> list = contexts.computeIfAbsent(uuid, ignored -> new CopyOnWriteArrayList<>());
        list.removeIf(existing -> existing.type == type && existing.source.equals(normalizedSource) && existing.trusted == trusted);
        list.add(context);
        return context;
    }

    public boolean hasActive(@NotNull UUID uuid, @NotNull MovementContextType type) {
        long now = System.nanoTime();
        CopyOnWriteArrayList<Context> list = contexts.get(uuid);
        if (list == null) return false;
        cleanup(uuid, list, now);
        for (Context context : list) {
            if (context.type == type && context.expiresAtNanos > now) return true;
        }
        return false;
    }

    public boolean suppressesConnectionStall(@NotNull UUID uuid) {
        long now = System.nanoTime();
        CopyOnWriteArrayList<Context> list = contexts.get(uuid);
        if (list == null) return false;
        cleanup(uuid, list, now);
        for (Context context : list) {
            if (context.expiresAtNanos > now && context.type.suppressesConnectionStall()) return true;
        }
        return false;
    }

    public boolean resetsFallLedger(@NotNull UUID uuid) {
        long now = System.nanoTime();
        CopyOnWriteArrayList<Context> list = contexts.get(uuid);
        if (list == null) return false;
        cleanup(uuid, list, now);
        for (Context context : list) {
            if (context.expiresAtNanos > now && context.type.resetsFallLedger()) return true;
        }
        return false;
    }

    public boolean hasTrustedVelocityContext(@NotNull UUID uuid) {
        long now = System.nanoTime();
        CopyOnWriteArrayList<Context> list = contexts.get(uuid);
        if (list == null) return false;
        cleanup(uuid, list, now);
        for (Context context : list) {
            if (context.expiresAtNanos > now && context.trusted && context.type.allowsTrustedVelocityOverride()) return true;
        }
        return false;
    }

    public @NotNull List<Context> getActive(@NotNull UUID uuid) {
        long now = System.nanoTime();
        CopyOnWriteArrayList<Context> list = contexts.get(uuid);
        if (list == null) return Collections.emptyList();
        cleanup(uuid, list, now);
        if (list.isEmpty()) return Collections.emptyList();
        return Collections.unmodifiableList(new ArrayList<>(list));
    }

    public void clear(@NotNull UUID uuid) {
        contexts.remove(uuid);
    }

    public void clear(@NotNull UUID uuid, @Nullable String source) {
        if (source == null) {
            clear(uuid);
            return;
        }
        CopyOnWriteArrayList<Context> list = contexts.get(uuid);
        if (list == null) return;
        String normalized = sanitizeSource(source);
        list.removeIf(context -> context.source.equals(normalized));
        if (list.isEmpty()) contexts.remove(uuid, list);
    }

    public void tick() {
        long now = System.nanoTime();
        contexts.forEach((uuid, list) -> cleanup(uuid, list, now));
    }

    private void cleanup(UUID uuid, CopyOnWriteArrayList<Context> list, long now) {
        list.removeIf(context -> context.expiresAtNanos <= now);
        if (list.isEmpty()) contexts.remove(uuid, list);
    }

    private static String sanitizeSource(String source) {
        String clean = source == null ? "unknown" : source.trim();
        if (clean.isEmpty()) clean = "unknown";
        return clean.length() > 64 ? clean.substring(0, 64) : clean;
    }

    private static @Nullable String sanitizeDetail(@Nullable String detail) {
        if (detail == null) return null;
        String clean = detail.trim();
        if (clean.isEmpty()) return null;
        return clean.length() > 160 ? clean.substring(0, 160) : clean;
    }

    public static final class Context {
        private final MovementContextType type;
        private final String source;
        private final @Nullable String detail;
        private final boolean trusted;
        private final long createdAtNanos;
        private final long expiresAtNanos;

        private Context(MovementContextType type, String source, @Nullable String detail, boolean trusted, long createdAtNanos, long expiresAtNanos) {
            this.type = type;
            this.source = source;
            this.detail = detail;
            this.trusted = trusted;
            this.createdAtNanos = createdAtNanos;
            this.expiresAtNanos = expiresAtNanos;
        }

        public MovementContextType getType() {
            return type;
        }

        public String getSource() {
            return source;
        }

        public @Nullable String getDetail() {
            return detail;
        }

        public boolean isTrusted() {
            return trusted;
        }

        public long getAgeMillis() {
            return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - createdAtNanos));
        }

        public long getRemainingMillis() {
            return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(expiresAtNanos - System.nanoTime()));
        }
    }
}
