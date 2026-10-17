package ac.grim.grimac.manager.integrity;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.player.GrimPlayer;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Long-window memory of independently confirmed anti-cheat episodes.
 *
 * <p>This manager deliberately does not create VL, setbacks, kicks or bans.
 * Checks decide when a physical episode is strong enough to be confirmed.
 * The manager only deduplicates episode tokens, applies a post-continuity
 * recovery gate and keeps a bounded history for future punishment conditions.</p>
 */
public final class SanctionEpisodeManager {
    private static final long PURGE_INTERVAL_NANOS =
            TimeUnit.SECONDS.toNanos(30L);

    public static final String FAMILY_VELOCITY = "VELOCITY";
    public static final String FAMILY_MOVEMENT = "MOVEMENT";
    public static final String FAMILY_TIMER = "TIMER";
    public static final String FAMILY_ITEM_USE = "ITEM_USE";
    public static final String FAMILY_GROUND = "GROUND";
    public static final String FAMILY_COMBAT = "COMBAT";
    public static final String FAMILY_NETWORK = "NETWORK";

    private final Map<UUID, State> states = new ConcurrentHashMap<>();

    private volatile boolean enabled = true;
    private volatile long nextPurgeNanos;
    private volatile long retentionNanos = TimeUnit.HOURS.toNanos(2L);
    private volatile int maxEpisodesPerFamily = 32;
    private volatile long postContinuityGraceNanos = TimeUnit.MILLISECONDS.toNanos(1500L);
    private volatile int minimumCleanPredictions = 8;
    private volatile double minimumConfidence = 0.85D;
    private volatile double minimumServerConfidence = 0.85D;
    private volatile double minimumPlayerConfidence = 0.85D;
    private volatile double minimumTps = 18.0D;

    public void reload(@NotNull ConfigManager config) {
        enabled = config.getBooleanElse("sanction-episodes.enabled", true);
        retentionNanos = TimeUnit.SECONDS.toNanos(clamp(
                config.getLongElse("sanction-episodes.retention-seconds", 7200L),
                300L,
                86_400L
        ));
        maxEpisodesPerFamily = (int) clamp(
                config.getLongElse("sanction-episodes.max-episodes-per-family", 32L),
                4L,
                256L
        );
        postContinuityGraceNanos = TimeUnit.MILLISECONDS.toNanos(clamp(
                config.getLongElse("sanction-episodes.post-continuity-grace-ms", 1500L),
                0L,
                10_000L
        ));
        minimumCleanPredictions = (int) clamp(
                config.getLongElse("sanction-episodes.clean-recovery-predictions", 8L),
                0L,
                80L
        );
        minimumConfidence = clamp01(
                config.getDoubleElse("sanction-episodes.minimum-confidence", 0.85D)
        );
        minimumServerConfidence = clamp01(
                config.getDoubleElse("sanction-episodes.minimum-server-confidence", 0.85D)
        );
        minimumPlayerConfidence = clamp01(
                config.getDoubleElse("sanction-episodes.minimum-player-confidence", 0.85D)
        );
        minimumTps = Math.max(
                1.0D,
                config.getDoubleElse("sanction-episodes.minimum-tps", 18.0D)
        );

        nextPurgeNanos = 0L;

        if (!enabled) {
            states.clear();
        } else {
            purgeAll();
        }
    }

    /**
     * Marks a real movement continuity boundary, e.g. /home, teleport, respawn
     * or world change. Confirmed history is retained; only new confirmations
     * are gated until both grace time and clean predictions recover.
     */
    public void markContinuityBreak(@NotNull UUID uuid, @NotNull String reason) {
        if (!enabled) return;

        long now = System.nanoTime();
        State state = states.computeIfAbsent(uuid, ignored -> new State());
        synchronized (state) {
            purgeLocked(state, now);
            state.recoveryUntilNanos = now + postContinuityGraceNanos;
            state.cleanPredictions = 0;
            state.lastContinuityReason = sanitize(reason);
        }
    }

    /**
     * Advances continuity recovery only after a genuinely clean prediction.
     * Callers must not use merely "checked" or still-suspicious movement here.
     */
    public void noteCleanPrediction(@NotNull UUID uuid) {
        if (!enabled) return;

        State state = states.get(uuid);
        if (state == null) return;

        synchronized (state) {
            purgeLocked(state, System.nanoTime());
            if (state.recoveryUntilNanos == 0L
                    && state.cleanPredictions >= minimumCleanPredictions) {
                return;
            }
            state.cleanPredictions = Math.min(
                    minimumCleanPredictions,
                    state.cleanPredictions + 1
            );
        }
    }

    /**
     * Records one independently confirmed episode. The same token can only be
     * recorded once inside retention, regardless of how many packets/ticks the
     * underlying check observed.
     */
    public @NotNull Decision confirm(
            @NotNull GrimPlayer player,
            @NotNull String family,
            @NotNull String component,
            @NotNull String episodeToken
    ) {
        String normalizedFamily = normalizeFamily(family);

        // Disabled means fully inert: do not even allocate per-player state,
        // because tick() is also disabled and therefore cannot purge it.
        if (!enabled) {
            return new Decision(
                    false,
                    normalizedFamily,
                    sanitize(component),
                    0,
                    0,
                    0.0D,
                    true,
                    "disabled"
            );
        }

        long now = System.nanoTime();
        Eligibility eligibility = eligibility(player, now);

        State state = states.computeIfAbsent(
                player.uuid,
                ignored -> new State()
        );

        synchronized (state) {
            purgeLocked(state, now);

            // Re-check continuity while holding the same state lock that will
            // record the episode. A teleport/rejoin boundary may arrive on a
            // different event thread between the optimistic eligibility check
            // above and this critical section.
            if (recoveryBlockedLocked(state, now)) {
                eligibility = new Eligibility(
                        false,
                        0.0D,
                        "recovery:" + state.lastContinuityReason
                );
            }

            if (!enabled || !eligibility.allowed) {
                return decision(
                        false,
                        state,
                        normalizedFamily,
                        eligibility,
                        component
                );
            }

            String normalizedComponent = sanitize(component);
            String normalizedToken = "join="
                    + player.joinTime
                    + "|"
                    + sanitize(episodeToken);

            Deque<Episode> episodes = state.episodes.computeIfAbsent(
                    normalizedFamily,
                    ignored -> new ArrayDeque<>()
            );

            for (Episode episode : episodes) {
                if (episode.token.equals(normalizedToken)) {
                    return decision(
                            false,
                            state,
                            normalizedFamily,
                            eligibility,
                            normalizedComponent
                    );
                }
            }

            episodes.addLast(new Episode(
                    now,
                    normalizedToken,
                    normalizedComponent,
                    eligibility.confidence
            ));

            while (episodes.size() > maxEpisodesPerFamily) {
                episodes.pollFirst();
            }

            state.lastConfirmedNanos = now;
            state.lastConfirmedFamily = normalizedFamily;
            state.lastConfirmedComponent = normalizedComponent;
            state.lastConfirmedConfidence = eligibility.confidence;

            return decision(
                    true,
                    state,
                    normalizedFamily,
                    eligibility,
                    normalizedComponent
            );
        }
    }

    public @NotNull Snapshot snapshot(
            @NotNull UUID uuid,
            @NotNull String family
    ) {
        State state = states.get(uuid);
        String normalizedFamily = normalizeFamily(family);

        if (!enabled || state == null) {
            return new Snapshot(
                    normalizedFamily,
                    0,
                    0,
                    0,
                    0.0D,
                    false,
                    ""
            );
        }

        long now = System.nanoTime();

        synchronized (state) {
            purgeLocked(state, now);

            Deque<Episode> episodes =
                    state.episodes.get(normalizedFamily);

            double confidence =
                    episodes == null || episodes.isEmpty()
                            ? 0.0D
                            : episodes.peekLast().confidence;

            boolean recovery = recoveryBlockedLocked(state, now);

            return new Snapshot(
                    normalizedFamily,
                    countLocked(
                            state,
                            normalizedFamily,
                            now,
                            TimeUnit.MINUTES.toNanos(10L)
                    ),
                    countLocked(
                            state,
                            normalizedFamily,
                            now,
                            TimeUnit.HOURS.toNanos(2L)
                    ),
                    countLocked(
                            state,
                            normalizedFamily,
                            now,
                            retentionNanos
                    ),
                    confidence,
                    recovery,
                    recovery ? state.lastContinuityReason : ""
            );
        }
    }

    public int countWithin(
            @NotNull UUID uuid,
            @NotNull String family,
            long windowMillis
    ) {
        State state = states.get(uuid);
        if (!enabled || state == null) return 0;

        long now = System.nanoTime();
        long window = TimeUnit.MILLISECONDS.toNanos(
                Math.max(1L, windowMillis)
        );

        synchronized (state) {
            purgeLocked(state, now);
            Deque<Episode> episodes = state.episodes.get(
                    normalizeFamily(family)
            );
            if (episodes == null) return 0;

            int count = 0;
            for (Episode episode : episodes) {
                if (now - episode.timeNanos <= window) {
                    count++;
                }
            }
            return count;
        }
    }

    public int countRetention(
            @NotNull UUID uuid,
            @NotNull String family
    ) {
        return countWithin(
                uuid,
                family,
                TimeUnit.NANOSECONDS.toMillis(retentionNanos)
        );
    }

    public boolean isRecoveryBlocked(@NotNull UUID uuid) {
        State state = states.get(uuid);
        if (!enabled || state == null) return false;

        long now = System.nanoTime();
        synchronized (state) {
            purgeLocked(state, now);
            return recoveryBlockedLocked(state, now);
        }
    }

    public double latestConfidence(@NotNull UUID uuid) {
        State state = states.get(uuid);
        if (!enabled || state == null) return 0.0D;

        synchronized (state) {
            purgeLocked(state, System.nanoTime());
            return state.lastConfirmedConfidence;
        }
    }

    public double latestConfidence(
            @NotNull UUID uuid,
            @NotNull String family
    ) {
        State state = states.get(uuid);
        if (!enabled || state == null) return 0.0D;

        synchronized (state) {
            purgeLocked(state, System.nanoTime());
            Deque<Episode> episodes = state.episodes.get(
                    normalizeFamily(family)
            );
            if (episodes == null || episodes.isEmpty()) return 0.0D;
            return episodes.peekLast().confidence;
        }
    }

    public @NotNull String recoveryReason(@NotNull UUID uuid) {
        State state = states.get(uuid);
        if (!enabled || state == null) return "";

        synchronized (state) {
            purgeLocked(state, System.nanoTime());
            return recoveryBlockedLocked(state, System.nanoTime())
                    ? state.lastContinuityReason
                    : "";
        }
    }

    public void clear(@NotNull UUID uuid) {
        states.remove(uuid);
    }

    public void tick() {
        if (!enabled) return;

        long now = System.nanoTime();
        if (now < nextPurgeNanos) return;

        nextPurgeNanos = now + PURGE_INTERVAL_NANOS;
        purgeAll();
    }

    private @NotNull Eligibility eligibility(
            @NotNull GrimPlayer player,
            long now
    ) {
        if (!enabled) {
            return new Eligibility(false, 0.0D, "disabled");
        }

        State existing = states.get(player.uuid);
        if (existing != null) {
            synchronized (existing) {
                purgeLocked(existing, now);
                if (recoveryBlockedLocked(existing, now)) {
                    return new Eligibility(
                            false,
                            0.0D,
                            "recovery:" + existing.lastContinuityReason
                    );
                }
            }
        }

        var lag = GrimAPI.INSTANCE.getLagProtectionManager();
        double serverConfidence = lag.heuristicConfidence();
        double playerConfidence = lag.playerNetworkConfidence(player);
        double confidence = Math.min(
                serverConfidence,
                playerConfidence
        );
        double tps = GrimAPI.INSTANCE.getPlatformServer().getTPS();

        if (confidence < minimumConfidence
                || serverConfidence < minimumServerConfidence
                || playerConfidence < minimumPlayerConfidence
                || tps < minimumTps) {
            return new Eligibility(
                    false,
                    confidence,
                    "confidence"
            );
        }

        return new Eligibility(true, confidence, "ok");
    }

    private @NotNull Decision decision(
            boolean recorded,
            @NotNull State state,
            @NotNull String family,
            @NotNull Eligibility eligibility,
            @NotNull String component
    ) {
        long now = System.nanoTime();
        int tenMinutes = countLocked(
                state,
                family,
                now,
                TimeUnit.MINUTES.toNanos(10L)
        );
        int retained = countLocked(
                state,
                family,
                now,
                retentionNanos
        );

        return new Decision(
                recorded,
                family,
                sanitize(component),
                tenMinutes,
                retained,
                eligibility.confidence,
                !eligibility.allowed,
                eligibility.reason
        );
    }

    private int countLocked(
            @NotNull State state,
            @NotNull String family,
            long now,
            long windowNanos
    ) {
        Deque<Episode> episodes = state.episodes.get(family);
        if (episodes == null) return 0;

        int count = 0;
        for (Episode episode : episodes) {
            if (now - episode.timeNanos <= windowNanos) {
                count++;
            }
        }
        return count;
    }

    private boolean recoveryBlockedLocked(
            @NotNull State state,
            long now
    ) {
        return now < state.recoveryUntilNanos
                || state.cleanPredictions < minimumCleanPredictions;
    }

    private void purgeAll() {
        long now = System.nanoTime();
        states.entrySet().removeIf(entry -> {
            State state = entry.getValue();
            synchronized (state) {
                purgeLocked(state, now);
                return state.episodes.isEmpty()
                        && !recoveryBlockedLocked(state, now)
                        && now - state.lastConfirmedNanos > retentionNanos;
            }
        });
    }

    private void purgeLocked(@NotNull State state, long now) {
        for (Deque<Episode> episodes : state.episodes.values()) {
            while (!episodes.isEmpty()
                    && now - episodes.peekFirst().timeNanos > retentionNanos) {
                episodes.pollFirst();
            }
        }
        state.episodes.entrySet().removeIf(
                entry -> entry.getValue().isEmpty()
        );

        if (now >= state.recoveryUntilNanos
                && state.cleanPredictions >= minimumCleanPredictions) {
            state.recoveryUntilNanos = 0L;
            state.lastContinuityReason = "";
        }
    }

    public static @NotNull String familyFor(
            String stableKey,
            String component
    ) {
        if (stableKey == null) return FAMILY_NETWORK;

        return switch (stableKey) {
            case "grim.velocity.anti_knockback" -> FAMILY_VELOCITY;
            case "grim.prediction.simulation" -> FAMILY_MOVEMENT;
            case "grim.timer.timer", "grim.timer.limit" -> FAMILY_TIMER;
            case "grim.movement.noslow" -> FAMILY_ITEM_USE;
            case "grim.groundspoof.no_fall",
                 "grim.groundspoof.fake" -> FAMILY_GROUND;
            case "grim.combat.reach",
                 "grim.combat.hitboxes" -> FAMILY_COMBAT;
            default -> stableKey.startsWith("grim.connection.")
                    ? FAMILY_NETWORK
                    : FAMILY_NETWORK;
        };
    }

    private static @NotNull String normalizeFamily(String family) {
        if (family == null || family.isBlank()) {
            return FAMILY_NETWORK;
        }
        return family.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private static @NotNull String sanitize(String value) {
        if (value == null) return "";
        String trimmed = value.trim();
        return trimmed.length() <= 128
                ? trimmed
                : trimmed.substring(0, 128);
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp01(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    public record Snapshot(
            @NotNull String family,
            int confirmedEpisodes10m,
            int confirmedEpisodes2h,
            int confirmedEpisodesRetention,
            double latestConfidence,
            boolean recoveryBlocked,
            @NotNull String recoveryReason
    ) {
    }

    public record Decision(
            boolean recorded,
            @NotNull String family,
            @NotNull String component,
            int confirmedEpisodes10m,
            int confirmedEpisodesRetention,
            double confidence,
            boolean blocked,
            @NotNull String reason
    ) {
    }

    private record Eligibility(
            boolean allowed,
            double confidence,
            @NotNull String reason
    ) {
    }

    private record Episode(
            long timeNanos,
            @NotNull String token,
            @NotNull String component,
            double confidence
    ) {
    }

    private static final class State {
        final Map<String, Deque<Episode>> episodes = new HashMap<>();
        long recoveryUntilNanos;
        int cleanPredictions = Integer.MAX_VALUE;
        String lastContinuityReason = "";
        long lastConfirmedNanos;
        String lastConfirmedFamily = "";
        String lastConfirmedComponent = "";
        double lastConfirmedConfidence;
    }
}
