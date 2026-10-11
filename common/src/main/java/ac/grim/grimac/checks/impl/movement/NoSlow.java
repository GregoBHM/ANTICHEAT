package ac.grim.grimac.checks.impl.movement;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PostPredictionListener;
import ac.grim.grimac.manager.integrity.DetectionEpisodeManager;
import ac.grim.grimac.manager.integrity.EnvironmentContext;
import ac.grim.grimac.manager.integrity.EnvironmentContextManager;
import ac.grim.grimac.manager.integrity.MovementContextManager;
import ac.grim.grimac.manager.integrity.MovementContextType;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.PredictionComplete;
import ac.grim.grimac.utils.item.ItemUseSessionTracker;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

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

    public NoSlow(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onPredictionComplete(final PredictionComplete predictionComplete) {
        if (!predictionComplete.isChecked()) {
            resetTickState();
            resetContextState();
            return;
        }

        ItemUseSessionTracker.Session session =
                player.itemUseSessionTracker.activeSession();

        // The physical session is the source of truth. PacketStateData is still
        // consulted because PredictionEngine temporarily flips it while testing
        // alternate vectors and NoSlow only has usable evidence when the final
        // predicted state agrees that the item is being used.
        if (session == null || !player.packetStateData.isSlowedByUsingItem()) {
            resetTickState();
            return;
        }

        if (player.getClientVersion().isOlderThanOrEquals(ClientVersion.V_1_8)
                && didSlotChangeLastTick) {
            didSlotChangeLastTick = false;
            resetTickState();
            return;
        }

        EnvironmentContextManager environment =
                GrimAPI.INSTANCE.getEnvironmentContextManager();
        Set<EnvironmentContext> contexts = environment.classify(player);
        boolean specialEnvironment =
                environment.isSpecialMovementEnvironment(contexts);
        boolean velocityContext = hasVelocityContext();

        if (!contextPolicyEnabled) {
            handleNormal(session);
            return;
        }

        if (specialEnvironment || velocityContext) {
            recoveryTicksRemaining = recoveryTicks;
            wasSpecialOrVelocity = true;
            handleContextual(
                    session,
                    componentFor(contexts, velocityContext, false),
                    specialEnvironment
                            ? specialMinimumOffset
                            : velocityMinimumOffset,
                    environment.summary(contexts)
            );
            return;
        }

        if (wasSpecialOrVelocity && recoveryTicksRemaining > 0) {
            recoveryTicksRemaining--;
            handleContextual(
                    session,
                    "noslow.transition",
                    recoveryMinimumOffset,
                    "recovery"
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
                flagComponentWithSetback(
                        "noslow.normal",
                        "offset=" + formatOffset(bestOffset)
                                + " session=" + session.sessionId()
                );
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
            @NotNull String contextKey
    ) {
        flaggedLastTick = false;

        if (bestOffset <= minimumOffset) {
            reward();
            contextualStrongStreak = 0;
            bestOffset = 1.0D;
            return;
        }

        contextualStrongStreak++;
        if (contextualStrongStreak < strongConsecutiveTicks) {
            bestOffset = 1.0D;
            return;
        }

        String episodeKey = session.sessionId()
                + "|" + component
                + "|" + contextKey;

        DetectionEpisodeManager.Decision decision =
                player.detectionEpisodeManager.evaluate(
                        component,
                        episodeKey,
                        episodeWindowMillis,
                        episodeFlagIntervalMillis,
                        maxFlagsPerEpisode
                );

        if (decision.allowFlag()) {
            flagComponent(
                    component,
                    "offset=" + formatOffset(bestOffset)
                            + " session=" + session.sessionId()
                            + " context=" + contextKey
                            + " strong=" + contextualStrongStreak
                            + " episodeFlags=" + decision.flags()
            );
        }

        bestOffset = 1.0D;
    }

    private boolean hasVelocityContext() {
        for (MovementContextManager.Context context :
                GrimAPI.INSTANCE.getMovementContextManager()
                        .getActive(player.uuid)) {
            MovementContextType type = context.getType();
            if (type == MovementContextType.KNOCKBACK
                    || type == MovementContextType.LAUNCH
                    || type == MovementContextType.DASH
                    || type == MovementContextType.PULL) {
                return true;
            }
        }
        return false;
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
                        0.040D
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
                        0.050D
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

        resetTickState();
        resetContextState();
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }
}
