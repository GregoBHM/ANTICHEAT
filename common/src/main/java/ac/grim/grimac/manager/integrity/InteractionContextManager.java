package ac.grim.grimac.manager.integrity;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/** Source-aware interaction exceptions; never a global anticheat bypass. */
public final class InteractionContextManager {
    private static final long MAX_CONTEXT_MILLIS = 15_000L;
    private final ConcurrentHashMap<UUID, CopyOnWriteArrayList<Context>> contexts = new ConcurrentHashMap<>();

    public @NotNull Context begin(@NotNull UUID uuid, @NotNull InteractionContextType type,
                                  @NotNull String source, long durationMillis) {
        return begin(uuid, type, source, durationMillis, null);
    }

    public @NotNull Context begin(@NotNull UUID uuid, @NotNull InteractionContextType type,
                                  @NotNull String source, long durationMillis, @Nullable String detail) {
        long duration = Math.max(1L, Math.min(MAX_CONTEXT_MILLIS, durationMillis));
        long now = System.nanoTime();
        String normalizedSource = sanitize(source);
        Context context = new Context(type, normalizedSource, sanitizeDetail(detail), now,
                now + TimeUnit.MILLISECONDS.toNanos(duration));
        CopyOnWriteArrayList<Context> list = contexts.computeIfAbsent(uuid, ignored -> new CopyOnWriteArrayList<>());
        list.removeIf(existing -> existing.type == type && existing.source.equals(normalizedSource));
        list.add(context);
        return context;
    }

    public boolean suppressesConsumeTiming(@NotNull UUID uuid) {
        return any(uuid, true);
    }

    public boolean suppressesInventoryFrequency(@NotNull UUID uuid) {
        return any(uuid, false);
    }

    private boolean any(UUID uuid, boolean consume) {
        long now = System.nanoTime();
        CopyOnWriteArrayList<Context> list = contexts.get(uuid);
        if (list == null) return false;
        cleanup(uuid, list, now);
        for (Context context : list) {
            if (context.expiresAtNanos <= now) continue;
            if (consume ? context.type.suppressConsumeTiming() : context.type.suppressInventoryFrequency()) return true;
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
        String normalized = sanitize(source);
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

    private static String sanitize(String source) {
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
        private final InteractionContextType type;
        private final String source;
        private final @Nullable String detail;
        private final long createdAtNanos;
        private final long expiresAtNanos;

        private Context(InteractionContextType type, String source, @Nullable String detail,
                        long createdAtNanos, long expiresAtNanos) {
            this.type = type;
            this.source = source;
            this.detail = detail;
            this.createdAtNanos = createdAtNanos;
            this.expiresAtNanos = expiresAtNanos;
        }

        public InteractionContextType getType() { return type; }
        public String getSource() { return source; }
        public @Nullable String getDetail() { return detail; }
        public long getAgeMillis() { return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - createdAtNanos)); }
        public long getRemainingMillis() { return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(expiresAtNanos - System.nanoTime())); }
    }
}
