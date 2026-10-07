package ac.grim.grimac.manager.integrity;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.player.GrimPlayer;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Correlates independent integrity evidence using bounded short and long windows.
 * This manager never executes punishments. It only produces context/confidence for staff and mitigations.
 *
 * <p>Phase 4A adds two precision rules:</p>
 * <ul>
 *   <li>Simulation bursts from one continuous environment are treated as an evidence episode instead of
 *       allowing one prediction edge case to inflate confidence without bound.</li>
 *   <li>Consumers can ask for causal windows shorter than the long historical window.</li>
 * </ul>
 */
public final class IntegrityCorrelationManager {
    private static final int MAX_EVENTS_PER_PLAYER = 192;
    private final Map<UUID, State> states = new ConcurrentHashMap<>();
    private volatile Map<IntegritySignal, Double> weights = defaultWeights();

    private volatile boolean enabled = true;
    private volatile long shortWindowNanos = TimeUnit.MILLISECONDS.toNanos(2500L);
    private volatile long longWindowNanos = TimeUnit.MILLISECONDS.toNanos(15_000L);
    private volatile long retentionNanos = TimeUnit.SECONDS.toNanos(30L);
    private volatile double decayPerSecond = 0.18D;
    private volatile double longWindowMultiplier = 0.35D;

    private volatile boolean simulationEpisodesEnabled = true;
    private volatile long simulationEpisodeWindowNanos = TimeUnit.MILLISECONDS.toNanos(1200L);
    private volatile double simulationRepeatMultiplier = 0.15D;
    private volatile double simulationMaxContribution = 1.0D;

    public void reload(@NotNull ConfigManager config) {
        enabled = config.getBooleanElse("integrity-correlation.enabled", true);
        shortWindowNanos = TimeUnit.MILLISECONDS.toNanos(clamp(config.getLongElse("integrity-correlation.short-window-ms", 2500L), 250L, 30_000L));
        longWindowNanos = TimeUnit.MILLISECONDS.toNanos(clamp(config.getLongElse("integrity-correlation.long-window-ms", 15_000L), 1000L, 120_000L));
        if (longWindowNanos < shortWindowNanos) longWindowNanos = shortWindowNanos;
        retentionNanos = TimeUnit.SECONDS.toNanos(clamp(config.getLongElse("integrity-correlation.retention-seconds", 30L), 5L, 300L));
        decayPerSecond = Math.max(0.0D, config.getDoubleElse("integrity-correlation.decay-per-second", 0.18D));
        longWindowMultiplier = clamp(config.getDoubleElse("integrity-correlation.long-window-multiplier", 0.35D), 0.0D, 1.0D);

        simulationEpisodesEnabled = config.getBooleanElse("integrity-correlation.simulation-episodes.enabled", true);
        simulationEpisodeWindowNanos = TimeUnit.MILLISECONDS.toNanos(clamp(
                config.getLongElse("integrity-correlation.simulation-episodes.window-ms", 1200L), 100L, 10_000L));
        simulationRepeatMultiplier = clamp(config.getDoubleElse(
                "integrity-correlation.simulation-episodes.repeated-weight-multiplier", 0.15D), 0.0D, 1.0D);
        simulationMaxContribution = Math.max(0.0D, config.getDoubleElse(
                "integrity-correlation.simulation-episodes.max-contribution", 1.0D));

        EnumMap<IntegritySignal, Double> configuredWeights = new EnumMap<>(IntegritySignal.class);
        for (IntegritySignal signal : IntegritySignal.values()) {
            configuredWeights.put(signal, Math.max(0.0D, config.getDoubleElse(
                    "integrity-correlation.weights." + signal.configKey(), signal.getWeight())));
        }
        weights = Map.copyOf(configuredWeights);
        if (!enabled) states.clear();
    }

    public double record(@NotNull UUID uuid, @NotNull IntegritySignal signal) {
        return record(uuid, signal, 1.0D);
    }

    /** Applies central server/player lag confidence only to heuristic signals and environment confidence to Simulation. */
    public double record(@NotNull GrimPlayer player, @NotNull IntegritySignal signal) {
        double confidence = signal.isHeuristic()
                ? GrimAPI.INSTANCE.getLagProtectionManager().heuristicConfidence(player)
                : 1.0D;
        String episodeKey = null;
        if (signal == IntegritySignal.SIMULATION) {
            EnvironmentContextManager environment = GrimAPI.INSTANCE.getEnvironmentContextManager();
            confidence *= environment.simulationCorrelationMultiplier(player);
            episodeKey = environment.summary(player);
        }
        return recordInternal(player.uuid, signal, confidence, episodeKey);
    }

    public double record(@NotNull UUID uuid, @NotNull IntegritySignal signal, double confidence) {
        return recordInternal(uuid, signal, confidence, null);
    }

    private double recordInternal(@NotNull UUID uuid, @NotNull IntegritySignal signal,
                                  double confidence, String episodeKey) {
        if (!enabled) return 0.0D;
        long now = System.nanoTime();
        double weight = weights.getOrDefault(signal, signal.getWeight()) * clamp(confidence, 0.0D, 1.0D);
        if (weight <= 0.0D) return getScore(uuid);

        State state = states.computeIfAbsent(uuid, ignored -> new State(now));
        synchronized (state) {
            purgeLocked(state, now);

            if (signal == IntegritySignal.SIMULATION && simulationEpisodesEnabled) {
                String key = episodeKey == null || episodeKey.isEmpty() ? "unknown" : episodeKey;
                SimulationEpisode episode = state.simulationEpisode;
                if (episode != null && episode.contextKey.equals(key)
                        && now - episode.lastNanos <= simulationEpisodeWindowNanos) {
                    weight *= simulationRepeatMultiplier;
                    double remaining = Math.max(0.0D, simulationMaxContribution - episode.contribution);
                    weight = Math.min(weight, remaining);
                    episode.lastNanos = now;
                    episode.contribution += weight;
                } else {
                    weight = Math.min(weight, simulationMaxContribution);
                    state.simulationEpisode = new SimulationEpisode(key, now, weight);
                }
            }

            state.lastSignalNanos = now;
            if (weight <= 0.0D) return scoreLocked(state, now);
            if (state.events.size() >= MAX_EVENTS_PER_PLAYER) state.events.pollFirst();
            state.events.addLast(new Evidence(now, signal, weight));
            return scoreLocked(state, now);
        }
    }

    /** Generic hooks for existing Grim checks that are useful correlation inputs. */
    public void recordCheckFlag(@NotNull GrimPlayer player, @NotNull Check check) {
        if (!enabled) return;
        String key = check.getStableKey();
        if (key == null) return;
        if ("grim.prediction.simulation".equals(key)) {
            record(player, IntegritySignal.SIMULATION);
        } else if (key.startsWith("grim.packetorder.")) {
            record(player, IntegritySignal.PACKET_ORDER);
        } else if (key.startsWith("grim.multiactions.")) {
            record(player, IntegritySignal.MULTI_ACTION);
        } else if ("grim.scaffolding.multi_place".equals(key)) {
            record(player, IntegritySignal.BLOCK_PLACE_BURST);
        }
    }

    public double getScore(@NotNull UUID uuid) {
        if (!enabled) return 0.0D;
        State state = states.get(uuid);
        if (state == null) return 0.0D;
        synchronized (state) {
            long now = System.nanoTime();
            purgeLocked(state, now);
            return scoreLocked(state, now);
        }
    }

    public double getShortScore(@NotNull UUID uuid) {
        State state = states.get(uuid);
        if (!enabled || state == null) return 0.0D;
        synchronized (state) {
            long now = System.nanoTime();
            purgeLocked(state, now);
            return windowScoreLocked(state, now, shortWindowNanos);
        }
    }

    public double getLongScore(@NotNull UUID uuid) {
        State state = states.get(uuid);
        if (!enabled || state == null) return 0.0D;
        synchronized (state) {
            long now = System.nanoTime();
            purgeLocked(state, now);
            return windowScoreLocked(state, now, longWindowNanos);
        }
    }

    public int getCount(@NotNull UUID uuid, @NotNull IntegritySignal signal) {
        return getCountWithin(uuid, signal, TimeUnit.NANOSECONDS.toMillis(longWindowNanos));
    }

    public int getCountWithin(@NotNull UUID uuid, @NotNull IntegritySignal signal, long windowMillis) {
        State state = states.get(uuid);
        if (!enabled || state == null) return 0;
        long windowNanos = TimeUnit.MILLISECONDS.toNanos(clamp(windowMillis, 1L, 120_000L));
        synchronized (state) {
            long now = System.nanoTime();
            purgeLocked(state, now);
            int count = 0;
            for (Evidence evidence : state.events) {
                if (evidence.signal == signal && now - evidence.timeNanos <= windowNanos) count++;
            }
            return count;
        }
    }

    public boolean hasRecent(@NotNull UUID uuid, @NotNull IntegritySignal signal) {
        return getCount(uuid, signal) > 0;
    }

    /** Use this for causal relationships; the normal long window is historical context, not proof of causation. */
    public boolean hasRecentWithin(@NotNull UUID uuid, @NotNull IntegritySignal signal, long windowMillis) {
        return getCountWithin(uuid, signal, windowMillis) > 0;
    }

    public void clear(@NotNull UUID uuid) {
        states.remove(uuid);
    }

    public void tick() {
        if (!enabled) return;
        long now = System.nanoTime();
        states.entrySet().removeIf(entry -> {
            State state = entry.getValue();
            synchronized (state) {
                purgeLocked(state, now);
                return state.events.isEmpty() && now - state.lastSignalNanos >= retentionNanos;
            }
        });
    }

    private double scoreLocked(State state, long now) {
        double shortScore = windowScoreLocked(state, now, shortWindowNanos);
        double longScore = windowScoreLocked(state, now, longWindowNanos);
        return shortScore + Math.max(0.0D, longScore - shortScore) * longWindowMultiplier;
    }

    private double windowScoreLocked(State state, long now, long windowNanos) {
        double score = 0.0D;
        for (Evidence evidence : state.events) {
            long age = now - evidence.timeNanos;
            if (age < 0L || age > windowNanos) continue;
            double ageSeconds = age / 1_000_000_000.0D;
            score += Math.max(0.0D, evidence.weight - decayPerSecond * ageSeconds);
        }
        return score;
    }

    private void purgeLocked(State state, long now) {
        long maxAge = Math.max(longWindowNanos, retentionNanos);
        while (!state.events.isEmpty() && now - state.events.peekFirst().timeNanos > maxAge) {
            state.events.pollFirst();
        }
        if (state.simulationEpisode != null
                && now - state.simulationEpisode.lastNanos > Math.max(simulationEpisodeWindowNanos, retentionNanos)) {
            state.simulationEpisode = null;
        }
    }

    private static Map<IntegritySignal, Double> defaultWeights() {
        EnumMap<IntegritySignal, Double> defaults = new EnumMap<>(IntegritySignal.class);
        for (IntegritySignal signal : IntegritySignal.values()) defaults.put(signal, signal.getWeight());
        return Map.copyOf(defaults);
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private record Evidence(long timeNanos, IntegritySignal signal, double weight) {}

    private static final class SimulationEpisode {
        final String contextKey;
        long lastNanos;
        double contribution;

        SimulationEpisode(String contextKey, long now, double contribution) {
            this.contextKey = contextKey;
            this.lastNanos = now;
            this.contribution = contribution;
        }
    }

    private static final class State {
        final Deque<Evidence> events = new ArrayDeque<>();
        SimulationEpisode simulationEpisode;
        long lastSignalNanos;

        State(long now) {
            lastSignalNanos = now;
        }
    }
}
