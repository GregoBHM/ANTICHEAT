package ac.grim.grimac.manager.integrity;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Bounds repeated evidence produced by one continuous physical episode.
 * This never creates VL by itself; checks decide whether the evidence is valid
 * and ask this manager only whether another flag from the same episode is allowed.
 *
 * <p>Component episodes cap one check/component. Shared episodes additionally
 * coordinate several checks that are observing the same physical movement
 * context, so cobweb + velocity + item use cannot independently explode into
 * NoSlow, AntiKB and Simulation VL.</p>
 */
public final class DetectionEpisodeManager {
    private final Map<String, State> states = new HashMap<>();
    private final Map<String, SharedState> sharedStates = new HashMap<>();
    private long sharedSequence;

    public synchronized @NotNull Decision evaluate(
            @NotNull String component,
            @NotNull String episodeKey,
            long episodeWindowMillis,
            long minimumFlagIntervalMillis,
            int maxFlagsPerEpisode
    ) {
        return evaluate(
                component,
                episodeKey,
                episodeWindowMillis,
                minimumFlagIntervalMillis,
                maxFlagsPerEpisode,
                null,
                Integer.MAX_VALUE
        );
    }

    public synchronized @NotNull Decision evaluate(
            @NotNull String component,
            @NotNull String episodeKey,
            long episodeWindowMillis,
            long minimumFlagIntervalMillis,
            int maxFlagsPerEpisode,
            @Nullable SharedEpisode sharedEpisode,
            int maxFlagsPerSharedEpisode
    ) {
        long now = System.nanoTime();
        long window = TimeUnit.MILLISECONDS.toNanos(
                Math.max(1L, episodeWindowMillis)
        );
        long interval = TimeUnit.MILLISECONDS.toNanos(
                Math.max(0L, minimumFlagIntervalMillis)
        );
        int maximum = Math.max(1, maxFlagsPerEpisode);
        int sharedMaximum = Math.max(1, maxFlagsPerSharedEpisode);

        State state = states.get(component);
        if (state == null
                || !state.episodeKey.equals(episodeKey)
                || now - state.lastSeenNanos > window) {
            state = new State(episodeKey, now);
            states.put(component, state);
        }

        state.lastSeenNanos = now;
        state.observations++;

        SharedState shared = null;
        if (sharedEpisode != null) {
            shared = sharedStates.get(sharedEpisode.family);
            if (shared == null
                    || shared.id != sharedEpisode.id
                    || !shared.contextKey.equals(sharedEpisode.contextKey)) {
                // The supplied token is stale or was cleared/replaced. Treat it
                // as no shared budget rather than accidentally suppressing a
                // fresh physical episode.
                shared = null;
            } else {
                shared.lastSeenNanos = now;
            }
        }

        if (state.flags >= maximum) {
            return decision(false, state, shared);
        }

        if (state.lastFlagNanos != 0L
                && now - state.lastFlagNanos < interval) {
            return decision(false, state, shared);
        }

        if (shared != null && shared.flags >= sharedMaximum) {
            return decision(false, state, shared);
        }

        state.flags++;
        state.lastFlagNanos = now;
        if (shared != null) {
            shared.flags++;
        }

        return decision(true, state, shared);
    }

    /**
     * Resolves one shared physical context token. Repeated calls from different
     * checks return the same token while the context key stays active inside
     * the configured window.
     */
    public synchronized @NotNull SharedEpisode resolveSharedEpisode(
            @NotNull String family,
            @NotNull String contextKey,
            long episodeWindowMillis
    ) {
        return resolveSharedEpisode(
                family,
                contextKey,
                episodeWindowMillis,
                Long.MAX_VALUE
        );
    }

    /**
     * Resolves a shared physical episode with both an idle-gap boundary and an
     * optional maximum lifetime. The lifetime cap is useful for persistent
     * normal cheats: continuous Timer/Fly should roll into bounded independent
     * episodes instead of producing either hundreds of flags or one immortal
     * episode.
     */
    public synchronized @NotNull SharedEpisode resolveSharedEpisode(
            @NotNull String family,
            @NotNull String contextKey,
            long episodeWindowMillis,
            long maximumEpisodeLifetimeMillis
    ) {
        long now = System.nanoTime();
        long window = TimeUnit.MILLISECONDS.toNanos(
                Math.max(1L, episodeWindowMillis)
        );
        long maximumLifetime = maximumEpisodeLifetimeMillis == Long.MAX_VALUE
                ? Long.MAX_VALUE
                : TimeUnit.MILLISECONDS.toNanos(
                Math.max(1L, maximumEpisodeLifetimeMillis)
        );

        SharedState state = sharedStates.get(family);
        if (state == null
                || !state.contextKey.equals(contextKey)
                || now - state.lastSeenNanos > window
                || (maximumLifetime != Long.MAX_VALUE
                && now - state.createdAtNanos >= maximumLifetime)) {
            state = new SharedState(
                    ++sharedSequence,
                    contextKey,
                    now
            );
            sharedStates.put(family, state);
        }

        state.lastSeenNanos = now;
        state.observations++;
        return new SharedEpisode(
                family,
                state.id,
                state.contextKey,
                state.observations,
                state.flags
        );
    }

    public synchronized void clear(@NotNull String component) {
        states.remove(component);
    }

    /**
     * Ends one shared physical family explicitly. Use this for real lifecycle
     * boundaries such as a clean landing so a later independent fall cannot
     * inherit the previous episode merely because it happened quickly.
     */
    public synchronized void clearSharedEpisode(@NotNull String family) {
        sharedStates.remove(family);
    }

    public synchronized void clearAll() {
        states.clear();
        sharedStates.clear();
    }

    private static @NotNull Decision decision(
            boolean allowFlag,
            @NotNull State state,
            @Nullable SharedState shared
    ) {
        return new Decision(
                allowFlag,
                state.observations,
                state.flags,
                shared == null ? 0 : shared.flags
        );
    }

    public record Decision(
            boolean allowFlag,
            int observations,
            int flags,
            int sharedFlags
    ) {
    }

    public record SharedEpisode(
            @NotNull String family,
            long id,
            @NotNull String contextKey,
            int observations,
            int flags
    ) {
        public @NotNull String token() {
            return family + "#" + id;
        }
    }

    private static final class State {
        final String episodeKey;
        long lastSeenNanos;
        long lastFlagNanos;
        int observations;
        int flags;

        State(String episodeKey, long now) {
            this.episodeKey = episodeKey;
            this.lastSeenNanos = now;
        }
    }

    private static final class SharedState {
        final long id;
        final String contextKey;
        final long createdAtNanos;
        long lastSeenNanos;
        int observations;
        int flags;

        SharedState(long id, String contextKey, long now) {
            this.id = id;
            this.contextKey = contextKey;
            this.createdAtNanos = now;
            this.lastSeenNanos = now;
        }
    }
}
