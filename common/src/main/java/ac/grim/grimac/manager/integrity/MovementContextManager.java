package ac.grim.grimac.manager.integrity;

import ac.grim.grimac.utils.math.Vector3dm;
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
 * Stores short-lived observed and explicitly trusted movement contexts.
 *
 * <p>This is deliberately not a permission-based bypass system. Contexts expire automatically,
 * are attached to a source name, and only affect integrity behavior declared by the context type.
 * Generic begin(...) calls are observed by default; trusted velocity requires an explicit
 * beginTrusted(...) call and is additionally limited by the context type.</p>
 */
public final class MovementContextManager {
    private static final long MAX_CONTEXT_MILLIS = 15_000L;
    private static final long MAX_TRUSTED_VELOCITY_MILLIS = 1_500L;
    private static final int MAX_CONTEXTS_PER_PLAYER = 64;
    private static final double DEFAULT_VECTOR_ABSOLUTE_TOLERANCE = 0.035D;
    private static final double DEFAULT_VECTOR_RELATIVE_TOLERANCE = 0.12D;

    private final Map<UUID, CopyOnWriteArrayList<Context>> contexts = new ConcurrentHashMap<>();

    /**
     * Safe default for generic/internal observations. Trusted velocity must
     * always be opted into explicitly through beginTrusted(...) or the
     * integration facade.
     */
    public Context begin(@NotNull UUID uuid,
                         @NotNull MovementContextType type,
                         @NotNull String source,
                         long durationMillis) {
        return beginObserved(uuid, type, source, durationMillis, null);
    }

    public Context begin(@NotNull UUID uuid,
                         @NotNull MovementContextType type,
                         @NotNull String source,
                         long durationMillis,
                         @Nullable String detail) {
        return beginObserved(uuid, type, source, durationMillis, detail);
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

    /**
     * Registers an explicitly trusted velocity context tied to the expected vector.
     *
     * <p>A scoped context never globally exempts the player. Consumers may only
     * treat the matching velocity sample as plugin-authored/ambiguous; unrelated
     * combat velocities continue through normal AntiKB analysis.</p>
     */
    public Context beginTrustedVelocity(
            @NotNull UUID uuid,
            @NotNull MovementContextType type,
            @NotNull String source,
            long durationMillis,
            @NotNull Vector3dm expectedVelocity,
            int transaction,
            @Nullable String detail
    ) {
        if (!type.allowsTrustedVelocityOverride()) {
            throw new IllegalArgumentException(
                    "Movement context " + type + " cannot describe velocity"
            );
        }
        return beginInternal(
                uuid,
                type,
                source,
                durationMillis,
                detail,
                true,
                expectedVelocity,
                transaction
        );
    }

    public Context beginTrustedVelocity(
            @NotNull UUID uuid,
            @NotNull MovementContextType type,
            @NotNull String source,
            long durationMillis,
            @NotNull Vector3dm expectedVelocity,
            @Nullable String detail
    ) {
        return beginTrustedVelocity(
                uuid, type, source, durationMillis, expectedVelocity, -1, detail
        );
    }

    /**
     * Records a concrete observed velocity without making it a bypass. This is
     * used by the generic Bukkit bridge so the same vanilla velocity packet does
     * not make itself "uncertain" merely because PlayerVelocityEvent observed it.
     */
    public Context beginObservedVelocity(
            @NotNull UUID uuid,
            @NotNull MovementContextType type,
            @NotNull String source,
            long durationMillis,
            @NotNull Vector3dm expectedVelocity,
            int transaction,
            @Nullable String detail
    ) {
        if (!type.allowsTrustedVelocityOverride()) {
            throw new IllegalArgumentException(
                    "Movement context " + type + " cannot describe velocity"
            );
        }
        return beginInternal(
                uuid,
                type,
                source,
                durationMillis,
                detail,
                false,
                expectedVelocity,
                transaction
        );
    }

    public Context beginObservedVelocity(
            @NotNull UUID uuid,
            @NotNull MovementContextType type,
            @NotNull String source,
            long durationMillis,
            @NotNull Vector3dm expectedVelocity,
            @Nullable String detail
    ) {
        return beginObservedVelocity(
                uuid, type, source, durationMillis, expectedVelocity, -1, detail
        );
    }

    private Context beginInternal(
            @NotNull UUID uuid,
            @NotNull MovementContextType type,
            @NotNull String source,
            long durationMillis,
            @Nullable String detail,
            boolean trusted
    ) {
        return beginInternal(
                uuid, type, source, durationMillis, detail, trusted, null, -1
        );
    }

    private Context beginInternal(
            @NotNull UUID uuid,
            @NotNull MovementContextType type,
            @NotNull String source,
            long durationMillis,
            @Nullable String detail,
            boolean trusted,
            @Nullable Vector3dm expectedVelocity,
            int transaction
    ) {
        long maximum = trusted && type.allowsTrustedVelocityOverride() ? MAX_TRUSTED_VELOCITY_MILLIS : MAX_CONTEXT_MILLIS;
        long duration = Math.max(1L, Math.min(maximum, durationMillis));
        long now = System.nanoTime();
        long expiresAtNanos = now + TimeUnit.MILLISECONDS.toNanos(duration);
        String normalizedSource = sanitizeSource(source);
        Context context = new Context(
                type,
                normalizedSource,
                sanitizeDetail(detail),
                trusted,
                now,
                expiresAtNanos,
                expectedVelocity == null ? null : expectedVelocity.clone(),
                transaction
        );
        CopyOnWriteArrayList<Context> list = contexts.computeIfAbsent(uuid, ignored -> new CopyOnWriteArrayList<>());
        synchronized (list) {
            list.removeIf(existing ->
                    existing.type == type
                            && existing.source.equals(normalizedSource)
                            && existing.trusted == trusted);
            while (list.size() >= MAX_CONTEXTS_PER_PLAYER) {
                list.remove(0);
            }
            list.add(context);
        }
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

    public boolean hasObservedVelocityContext(@NotNull UUID uuid) {
        return hasVelocityContext(uuid, false, true);
    }

    public boolean hasAnyVelocityContext(@NotNull UUID uuid) {
        return hasVelocityContext(uuid, true, true);
    }

    /**
     * Classifies active velocity contexts against one concrete server velocity.
     *
     * <p>Matched observed contexts are informational only: observing the same
     * Bukkit velocity must not weaken AntiKB. Trusted matches and any mismatched
     * or unscoped concurrent velocity context make the sample ambiguous, but do
     * not erase evidence or globally exempt subsequent velocities.</p>
     */
    public @NotNull VelocityContextAssessment assessVelocity(
            @NotNull UUID uuid,
            int transaction,
            @NotNull Vector3dm velocity
    ) {
        long now = System.nanoTime();
        CopyOnWriteArrayList<Context> list = contexts.get(uuid);
        if (list == null) return VelocityContextAssessment.NONE;
        cleanup(uuid, list, now);

        boolean trustedMatch = false;
        boolean ambiguous = false;
        Context matchedTrusted = null;

        for (Context context : list) {
            if (context.expiresAtNanos <= now
                    || !context.type.allowsTrustedVelocityOverride()) {
                continue;
            }

            if (!context.isVelocityScoped()) {
                // Legacy/unscoped integration context is never a bypass. Keep
                // enforcement conservative while still allowing evidence.
                ambiguous = true;
                continue;
            }

            if (context.matchesVelocity(transaction, velocity)) {
                if (context.trusted) {
                    trustedMatch = true;
                    matchedTrusted = context;
                }
                // A matching observed context is just a second observation of
                // this exact server velocity, not uncertainty.
            } else {
                // A different active velocity may be composed with this one.
                ambiguous = true;
            }
        }

        if (!trustedMatch && !ambiguous) {
            return VelocityContextAssessment.NONE;
        }
        return new VelocityContextAssessment(
                trustedMatch,
                ambiguous,
                matchedTrusted
        );
    }

    private boolean hasVelocityContext(
            @NotNull UUID uuid,
            boolean includeTrusted,
            boolean includeObserved
    ) {
        long now = System.nanoTime();
        CopyOnWriteArrayList<Context> list = contexts.get(uuid);
        if (list == null) return false;
        cleanup(uuid, list, now);
        for (Context context : list) {
            if (context.expiresAtNanos <= now
                    || !context.type.allowsTrustedVelocityOverride()) {
                continue;
            }
            if (context.trusted ? includeTrusted : includeObserved) {
                return true;
            }
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

    public void clearSourcePrefix(
            @NotNull UUID uuid,
            @NotNull String sourcePrefix
    ) {
        CopyOnWriteArrayList<Context> list = contexts.get(uuid);
        if (list == null) return;
        String prefix = sanitizeSource(sourcePrefix);
        list.removeIf(context ->
                sourceMatchesPrefix(context.source, prefix));
        if (list.isEmpty()) contexts.remove(uuid, list);
    }

    public void clearSourcePrefix(@NotNull String sourcePrefix) {
        String prefix = sanitizeSource(sourcePrefix);
        contexts.forEach((uuid, list) -> {
            list.removeIf(context ->
                    sourceMatchesPrefix(context.source, prefix));
            if (list.isEmpty()) {
                contexts.remove(uuid, list);
            }
        });
    }

    public void tick() {
        long now = System.nanoTime();
        contexts.forEach((uuid, list) -> cleanup(uuid, list, now));
    }

    private void cleanup(UUID uuid, CopyOnWriteArrayList<Context> list, long now) {
        list.removeIf(context -> context.expiresAtNanos <= now);
        if (list.isEmpty()) contexts.remove(uuid, list);
    }

    private static boolean sourceMatchesPrefix(
            @NotNull String source,
            @NotNull String prefix
    ) {
        return source.equalsIgnoreCase(prefix)
                || (source.length() > prefix.length()
                && source.regionMatches(
                        true,
                        0,
                        prefix,
                        0,
                        prefix.length()
                )
                && source.charAt(prefix.length()) == ':');
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
        private final @Nullable Vector3dm expectedVelocity;
        private final int transaction;

        private Context(
                MovementContextType type,
                String source,
                @Nullable String detail,
                boolean trusted,
                long createdAtNanos,
                long expiresAtNanos,
                @Nullable Vector3dm expectedVelocity,
                int transaction
        ) {
            this.type = type;
            this.source = source;
            this.detail = detail;
            this.trusted = trusted;
            this.createdAtNanos = createdAtNanos;
            this.expiresAtNanos = expiresAtNanos;
            this.expectedVelocity = expectedVelocity;
            this.transaction = transaction;
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

        public boolean isVelocityScoped() {
            return expectedVelocity != null;
        }

        public @Nullable Vector3dm getExpectedVelocity() {
            return expectedVelocity == null ? null : expectedVelocity.clone();
        }

        public int getTransaction() {
            return transaction;
        }

        private boolean matchesVelocity(
                int actualTransaction,
                @NotNull Vector3dm actualVelocity
        ) {
            if (expectedVelocity == null) return false;

            if (transaction >= 0
                    && actualTransaction >= 0
                    && Math.abs(actualTransaction - transaction) > 1) {
                return false;
            }

            return componentMatches(expectedVelocity.getX(), actualVelocity.getX())
                    && componentMatches(expectedVelocity.getY(), actualVelocity.getY())
                    && componentMatches(expectedVelocity.getZ(), actualVelocity.getZ());
        }

        private static boolean componentMatches(
                double expected,
                double actual
        ) {
            double tolerance = DEFAULT_VECTOR_ABSOLUTE_TOLERANCE
                    + DEFAULT_VECTOR_RELATIVE_TOLERANCE
                    * Math.max(Math.abs(expected), Math.abs(actual));
            return Math.abs(expected - actual) <= tolerance;
        }

        public long getAgeMillis() {
            return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - createdAtNanos));
        }

        public long getRemainingMillis() {
            return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(expiresAtNanos - System.nanoTime()));
        }
    }

    public static final class VelocityContextAssessment {
        private static final VelocityContextAssessment NONE =
                new VelocityContextAssessment(false, false, null);

        private final boolean trustedMatch;
        private final boolean ambiguous;
        private final @Nullable Context matchedTrustedContext;

        private VelocityContextAssessment(
                boolean trustedMatch,
                boolean ambiguous,
                @Nullable Context matchedTrustedContext
        ) {
            this.trustedMatch = trustedMatch;
            this.ambiguous = ambiguous;
            this.matchedTrustedContext = matchedTrustedContext;
        }

        /** Exact trusted plugin velocity; evidence remains possible but destructive enforcement is suppressed. */
        public boolean hasTrustedMatch() {
            return trustedMatch;
        }

        /** Another/unscoped velocity context overlaps this sample. */
        public boolean isAmbiguous() {
            return ambiguous;
        }

        public boolean isUncertain() {
            return trustedMatch || ambiguous;
        }

        public @Nullable Context getMatchedTrustedContext() {
            return matchedTrustedContext;
        }
    }
}
