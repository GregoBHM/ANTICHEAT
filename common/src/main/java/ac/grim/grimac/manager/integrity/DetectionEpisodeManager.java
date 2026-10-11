package ac.grim.grimac.manager.integrity;

import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Bounds repeated evidence produced by one continuous physical episode.
 * This never creates VL by itself; checks decide whether the evidence is valid
 * and ask this manager only whether another flag from the same episode is allowed.
 */
public final class DetectionEpisodeManager {
    private final Map<String, State> states = new HashMap<>();

    public synchronized @NotNull Decision evaluate(
            @NotNull String component,
            @NotNull String episodeKey,
            long episodeWindowMillis,
            long minimumFlagIntervalMillis,
            int maxFlagsPerEpisode
    ) {
        long now = System.nanoTime();
        long window = TimeUnit.MILLISECONDS.toNanos(Math.max(1L, episodeWindowMillis));
        long interval = TimeUnit.MILLISECONDS.toNanos(Math.max(0L, minimumFlagIntervalMillis));
        int maximum = Math.max(1, maxFlagsPerEpisode);

        State state = states.get(component);
        if (state == null
                || !state.episodeKey.equals(episodeKey)
                || now - state.lastSeenNanos > window) {
            state = new State(episodeKey, now);
            states.put(component, state);
        }

        state.lastSeenNanos = now;
        state.observations++;

        if (state.flags >= maximum) {
            return new Decision(false, state.observations, state.flags);
        }

        if (state.lastFlagNanos != 0L && now - state.lastFlagNanos < interval) {
            return new Decision(false, state.observations, state.flags);
        }

        state.flags++;
        state.lastFlagNanos = now;
        return new Decision(true, state.observations, state.flags);
    }

    public synchronized void clear(@NotNull String component) {
        states.remove(component);
    }

    public synchronized void clearAll() {
        states.clear();
    }

    public record Decision(boolean allowFlag, int observations, int flags) {
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
}
