package ac.grim.grimac.checks.impl.movement;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PostPredictionListener;
import ac.grim.grimac.manager.integrity.DetectionEpisodeManager;
import ac.grim.grimac.manager.integrity.EnvironmentContext;
import ac.grim.grimac.manager.integrity.EnvironmentContextManager;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.PredictionComplete;
import ac.grim.grimac.utils.item.ItemUseSessionTracker;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import org.jetbrains.annotations.NotNull;

import java.util.Set;
import java.util.concurrent.TimeUnit;

@CheckData(name = "NoSlow", stableKey = "grim.movement.noslow",
        description = "Was not slowed while using an item", setback = 5)
public class NoSlow extends Check implements PostPredictionListener {
    public boolean didSlotChangeLastTick = false;
    public boolean flaggedLastTick = false;

    private double offsetToFlag;
    private double bestOffset = 1.0D;

    private boolean contextPolicyEnabled;
    private double specialMinimumOffset;
    private double velocityMinimumOffset;
    private double recoveryMinimumOffset;
    private int strongConsecutiveTicks;
    private int recoveryTicks;
    private long episodeWindowMillis;
    private long episodeFlagIntervalMillis;
    private int maxFlagsPerEpisode;

    private int contextualStrongStreak;
    private int recoveryTicksRemaining;
    private boolean wasSpecialOrVelocity;
    private String lastContextEvidenceKey = "";
    private long lastCheckedPredictionNanos;

    private long normalEpisodeWindowMillis;
    private long normalEpisodeFlagIntervalMillis;
    private int normalMaxFlagsPerEpisode;
    private long normalEpisodeMaxLifetimeMillis;

    public NoSlow(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onPredictionComplete(final PredictionComplete predictionComplete) {
        final long now = System.nanoTime();

        if (!predictionComplete.isChecked()) {
            // Unchecked prediction is neutral NoSlow evidence. The per-tick
            // offset cannot be reused, but one discarded sample must not erase
            // a still-live physical ItemUse/environment episode.
            resetTickState();

            long episodeWindowNanos = TimeUnit.MILLISECONDS.toNanos(
                    Math.max(1L, episodeWindowMillis)
            );
            if (lastCheckedPredictionNanos != 0L
                    && now - lastCheckedPredictionNanos > episodeWindowNanos) {
                resetContextState();
            }
            return;
        }

        lastCheckedPredictionNanos = now;

        ItemUseSessionTracker.Session session =
                player.itemUseSessionTracker.activeSession();

        // The physical session is the source of truth. PacketStateData is still
        // consulted because PredictionEngine temporarily flips it while testing
        // alternate vectors and NoSlow only has usable evidence when the final
        // predicted state agrees that the item is being used.
        if (session == null) {
            resetTickState();
            resetContextState();
            return;
        }
        if (!player.packetStateData.isSlowedByUsingItem()) {
            resetTickState();
            contextualStrongStreak = 0;
            lastContextEvidenceKey = "";
            return;
        }

        if (player.getClientVersion().isOlderThanOrEquals(ClientVersion.V_1_8)
                && didSlotChangeLastTick) {
            didSlotChangeLastTick = false;
            resetTickState();
            contextualStrongStreak = 0;
            lastContextEvidenceKey = "";
            return;
        }

        EnvironmentContextManager environment =
                GrimAPI.INSTANCE.getEnvironmentContextManager();
        Set<EnvironmentContext> contexts = environment.classify(player);
        boolean specialEnvironment =
                environment.isSpecialMovementEnvironment(contexts);
        var movementContexts = GrimAPI.INSTANCE.getMovementContextManager();
        boolean trustedVelocityContext =
                movementContexts.hasTrustedVelocityContext(player.uuid);
        boolean observedVelocityContext =
                movementContexts.hasObservedVelocityContext(player.uuid);
        boolean anyVelocityContext =
                trustedVelocityContext || observedVelocityContext;

        if (!contextPolicyEnabled) {
            handleNormal(session);
            return;
        }

        if (specialEnvironment || anyVelocityContext) {
            recoveryTicksRemaining = recoveryTicks;
            wasSpecialOrVelocity = true;
            double contextualMinimum = specialEnvironment
                    ? Math.max(
                    specialMinimumOffset,
                    environment.minimumCorrectionRawOffset(contexts)
            )
                    : velocityMinimumOffset;

            DetectionEpisodeManager.SharedEpisode sharedEpisode =
                    environment.resolveMovementEvidenceEpisode(
                            player,
                            contexts,
                            anyVelocityContext,
                            false,
                            false
                    );

            handleContextual(
                    session,
                    componentFor(
                            contexts,
                            anyVelocityContext,
                            false
                    ),
                    contextualMinimum,
                    environment.summary(contexts),
                    sharedEpisode,
                    environment.evidenceEpisodeMaxTotalFlags()
            );
            return;
        }

        if (wasSpecialOrVelocity && recoveryTicksRemaining > 0) {
            recoveryTicksRemaining--;
            DetectionEpisodeManager.SharedEpisode sharedEpisode =
                    environment.resolveMovementEvidenceEpisode(
                            player,
                            contexts,
                            false,
                            false,
                            true
                    );
            handleContextual(
                    session,
                    "noslow.transition",
                    recoveryMinimumOffset,
                    "recovery",
                    sharedEpisode,
                    environment.evidenceEpisodeMaxTotalFlags()
            );
            if (recoveryTicksRemaining == 0) {
                wasSpecialOrVelocity = false;
                contextualStrongStreak = 0;
            }
            return;
        }

        wasSpecialOrVelocity = false;
        contextualStrongStreak = 0;
        handleNormal(session);
    }

    private void handleNormal(@NotNull ItemUseSessionTracker.Session session) {
        if (bestOffset > offsetToFlag) {
            if (flaggedLastTick) {
                // Eating is naturally short-lived, but blocking/use-item states
                // can remain active for a long time. Keep the physical session
                // identity while rolling persistent NoSlow into bounded
                // sub-episodes so one held session is neither x70 spam nor an
                // immortal single piece of evidence.
                DetectionEpisodeManager.SharedEpisode normalEpisode =
                        player.detectionEpisodeManager.resolveSharedEpisode(
                                "noslow-normal",
                                "itemuse#" + session.sessionId(),
                                normalEpisodeWindowMillis,
                                normalEpisodeMaxLifetimeMillis
                        );

                String episodeKey = "noslow.normal|session="
                        + session.sessionId()
                        + "|" + normalEpisode.token();

                DetectionEpisodeManager.Decision decision =
                        player.detectionEpisodeManager.evaluate(
                                "noslow.normal",
                                episodeKey,
                                normalEpisodeWindowMillis,
                                normalEpisodeFlagIntervalMillis,
                                normalMaxFlagsPerEpisode,
                                normalEpisode,
                                normalMaxFlagsPerEpisode
                        );

                if (decision.allowFlag()) {
                    flagComponentWithSetback(
                            "noslow.normal",
                            "offset=" + formatOffset(bestOffset)
                                    + " session=" + session.sessionId()
                                    + " episode=" + normalEpisode.token()
                                    + " episodeObservations="
                                    + decision.observations()
                                    + " episodeFlags="
                                    + decision.flags(),
                            () -> GrimAPI.INSTANCE.getSanctionEpisodeManager().confirm(
                                    player,
                                    ac.grim.grimac.manager.integrity.SanctionEpisodeManager.FAMILY_ITEM_USE,
                                    "noslow.normal",
                                    normalEpisode.token()
                            )
                    );
                }
            }
            flaggedLastTick = true;
        } else {
            reward();
            flaggedLastTick = false;
        }
        bestOffset = 1.0D;
    }

    private void handleContextual(
            @NotNull ItemUseSessionTracker.Session session,
            @NotNull String component,
            double minimumOffset,
            @NotNull String contextKey,
            @NotNull DetectionEpisodeManager.SharedEpisode sharedEpisode,
            int maxSharedFlags
    ) {
        flaggedLastTick = false;

        String evidenceKey = session.sessionId()
                + "|" + component
                + "|" + contextKey
                + "|" + sharedEpisode.token();

        if (!evidenceKey.equals(lastContextEvidenceKey)) {
            contextualStrongStreak = 0;
            lastContextEvidenceKey = evidenceKey;
        }

        if (bestOffset <= minimumOffset) {
            // Ambiguous special-physics evidence is neutral. Do not award
            // decay here or players could intentionally enter a web/liquid
            // to wash previously accumulated NoSlow VL.
            contextualStrongStreak = 0;
            bestOffset = 1.0D;
            return;
        }

        contextualStrongStreak++;
        if (contextualStrongStreak < strongConsecutiveTicks) {
            bestOffset = 1.0D;
            return;
        }

        String episodeKey = evidenceKey;

        DetectionEpisodeManager.Decision decision =
                player.detectionEpisodeManager.evaluate(
                        component,
                        episodeKey,
                        episodeWindowMillis,
                        episodeFlagIntervalMillis,
                        maxFlagsPerEpisode,
                        sharedEpisode,
                        maxSharedFlags
                );

        if (decision.allowFlag()) {
            flagComponent(
                    component,
                    "offset=" + formatOffset(bestOffset)
                            + " session=" + session.sessionId()
                            + " context=" + contextKey
                            + " strong=" + contextualStrongStreak
                            + " episode=" + sharedEpisode.token()
                            + " episodeFlags=" + decision.flags()
                            + " sharedFlags=" + decision.sharedFlags()
            );
        }

        bestOffset = 1.0D;
    }

    private static @NotNull String componentFor(
            @NotNull Set<EnvironmentContext> contexts,
            boolean velocityContext,
            boolean recovery
    ) {
        if (recovery) return "noslow.transition";
        if (contexts.contains(EnvironmentContext.COBWEB)) {
            return "noslow.cobweb";
        }
        if (contexts.contains(EnvironmentContext.STUCK_MOVEMENT)) {
            return "noslow.stuck";
        }
        if (contexts.contains(EnvironmentContext.CLIMBABLE)) {
            return "noslow.climbable";
        }
        if (contexts.contains(EnvironmentContext.WATER)
                || contexts.contains(EnvironmentContext.LAVA)) {
            return "noslow.liquid";
        }
        if (velocityContext) {
            return "noslow.velocity";
        }
        return "noslow.special";
    }

    private void resetTickState() {
        flaggedLastTick = false;
        bestOffset = 1.0D;
    }

    private void resetContextState() {
        contextualStrongStreak = 0;
        recoveryTicksRemaining = 0;
        wasSpecialOrVelocity = false;
        lastContextEvidenceKey = "";
    }

    public void handlePredictionAnalysis(double offset) {
        bestOffset = Math.min(bestOffset, offset);
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        offsetToFlag = config.getDoubleElse(
                getConfigName() + ".threshold",
                0.001D
        );

        contextPolicyEnabled = config.getBooleanElse(
                "NoSlow.context-policy.enabled",
                true
        );
        specialMinimumOffset = Math.max(
                offsetToFlag,
                config.getDoubleElse(
                        "NoSlow.context-policy.special-minimum-offset",
                        0.120D
                )
        );
        velocityMinimumOffset = Math.max(
                offsetToFlag,
                config.getDoubleElse(
                        "NoSlow.context-policy.velocity-minimum-offset",
                        0.035D
                )
        );
        recoveryMinimumOffset = Math.max(
                specialMinimumOffset,
                config.getDoubleElse(
                        "NoSlow.context-policy.recovery-minimum-offset",
                        0.150D
                )
        );
        strongConsecutiveTicks = (int) clamp(
                config.getLongElse(
                        "NoSlow.context-policy.strong-consecutive-ticks",
                        3L
                ),
                2L,
                20L
        );
        recoveryTicks = (int) clamp(
                config.getLongElse(
                        "NoSlow.context-policy.recovery-ticks",
                        4L
                ),
                0L,
                20L
        );
        episodeWindowMillis = clamp(
                config.getLongElse(
                        "NoSlow.context-policy.episode-window-ms",
                        1200L
                ),
                100L,
                10_000L
        );
        episodeFlagIntervalMillis = clamp(
                config.getLongElse(
                        "NoSlow.context-policy.flag-interval-ms",
                        600L
                ),
                0L,
                10_000L
        );
        maxFlagsPerEpisode = (int) clamp(
                config.getLongElse(
                        "NoSlow.context-policy.max-flags-per-episode",
                        1L
                ),
                1L,
                10L
        );

        normalEpisodeWindowMillis = clamp(
                config.getLongElse(
                        "NoSlow.normal-episode.window-ms",
                        5000L
                ),
                500L,
                15_000L
        );
        normalEpisodeFlagIntervalMillis = clamp(
                config.getLongElse(
                        "NoSlow.normal-episode.flag-interval-ms",
                        1000L
                ),
                0L,
                10_000L
        );
        normalMaxFlagsPerEpisode = (int) clamp(
                config.getLongElse(
                        "NoSlow.normal-episode.max-flags-per-episode",
                        1L
                ),
                1L,
                4L
        );
        normalEpisodeMaxLifetimeMillis = clamp(
                config.getLongElse(
                        "NoSlow.normal-episode.max-lifetime-ms",
                        4000L
                ),
                1000L,
                30_000L
        );

        resetTickState();
        resetContextState();
        lastCheckedPredictionNanos = 0L;
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }
}
