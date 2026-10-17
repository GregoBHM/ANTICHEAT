package ac.grim.grimac.checks.impl.timer;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.manager.integrity.DetectionEpisodeManager;
import ac.grim.grimac.manager.integrity.IntegritySignal;
import ac.grim.grimac.player.GrimPlayer;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import org.jetbrains.annotations.NotNull;

@CheckData(name = "TimerLimit", stableKey = "grim.timer.limit", description = "The player has sent too many packets after high latency", setback = 10)
public class TimerLimit extends Timer {
    private long limitAbuseOverPing;

    private boolean preventBurstRelease;
    private boolean cancelBurstMovement;
    private double preventionMinimumConfidence;
    private long preventionCausalWindowMillis;

    private long blinkMitigationSuppressUntilNanos;

    private long episodeWindowMillis;
    private long episodeFlagIntervalMillis;
    private int maxFlagsPerEpisode;
    private long episodeMaxLifetimeMillis;

    public TimerLimit(GrimPlayer player) {
        super(player);
    }

    @Override
    public void doCheck(final PacketReceiveEvent event) {
        final long now = System.nanoTime();

        // v20: once BlinkRelease already removed the advantage, TimerLimit must not
        // keep "charging" old timer debt and teleport the player again while they
        // are walking normally. Rebase only the positive balance created by that
        // mitigated release; normal TimerA/TimerLimit behavior resumes afterwards.
        if (now < blinkMitigationSuppressUntilNanos) {
            if (timerBalanceRealTime > now) {
                timerBalanceRealTime = now;
            }
            limitFallBehind();
            return;
        }

        if (timerBalanceRealTime > now) {
            ConnectionStall stall =
                    player.checkManager.get(ConnectionStall.class);
            boolean blinkOwned = stall != null
                    && (stall.isHardReleaseCandidateActive()
                    || stall.shouldSuppressMovementSetbacks());

            if (blinkOwned) {
                // The candidate/recovery already belongs to ConnectionStall.
                // Do not create a second TimerLimit episode from the same
                // buffered release and do not cancel the observation packet.
                timerBalanceRealTime -= 50e6;
                limitFallBehind();
                return;
            }

            if (!event.isCancelled()) {
                // A continuous high-latency/debt release is bounded as one
                // physical episode, but it must not become immortal. Rolling
                // the shared token after a maximum lifetime preserves sustained
                // abuse visibility without returning to one VL per packet.
                DetectionEpisodeManager.SharedEpisode releaseEpisode =
                        player.detectionEpisodeManager.resolveSharedEpisode(
                                "timerlimit-release",
                                "timerlimit",
                                episodeWindowMillis,
                                episodeMaxLifetimeMillis
                        );

                DetectionEpisodeManager.Decision episode =
                        player.detectionEpisodeManager.evaluate(
                                "timerlimit.release",
                                "timerlimit|" + releaseEpisode.token(),
                                episodeWindowMillis,
                                episodeFlagIntervalMillis,
                                maxFlagsPerEpisode,
                                releaseEpisode,
                                maxFlagsPerEpisode
                        );

                if (episode.allowFlag()) {
                    boolean flagged = flagComponent(
                            "timerlimit.release",
                            "episode=" + releaseEpisode.token()
                                    + " episodeObservations=" + episode.observations()
                                    + " episodeFlags=" + episode.flags()
                    );

                    if (flagged) {
                        boolean hardPrevention = shouldPreventBurstRelease();

                        if (hardPrevention && cancelBurstMovement && shouldModifyPackets()) {
                            event.setCancelled(true);
                            player.onPacketCancel();
                        }

                        // TimerLimit no longer owns Blink rollback.
                        // Outside a ConnectionStall-owned episode, retain
                        // the causal hard-prevention behavior.
                        if (hardPrevention && shouldSetback()) {
                            GrimAPI.INSTANCE.getMovementReleaseGuard()
                                    .apply(player, true, this);
                        }
                    }
                }
            }

            timerBalanceRealTime -= 50e6;
        }

        limitFallBehind();
    }

    /** Called exactly when the v20 hard-release barrier confirms and mitigates Blink. */
    public void acknowledgeMitigatedBlink(long nowNanos, long recoveryNanos) {
        timerBalanceRealTime = Math.min(timerBalanceRealTime, nowNanos);
        hasGottenMovementAfterTransaction = false;
        blinkMitigationSuppressUntilNanos = Math.max(
                blinkMitigationSuppressUntilNanos,
                nowNanos + Math.max(0L, recoveryNanos)
        );
    }

    private boolean shouldPreventBurstRelease() {
        if (!preventBurstRelease
                || player.disableGrim
                || player.canFly
                || player.isFlying
                || player.inVehicle()
                || GrimAPI.INSTANCE.getMovementContextManager().suppressesConnectionStall(player.uuid)) {
            return false;
        }

        if (GrimAPI.INSTANCE.getLagProtectionManager().heuristicConfidence()
                < preventionMinimumConfidence) {
            return false;
        }

        ConnectionStall stall = player.checkManager.get(ConnectionStall.class);
        boolean stallEvidence = stall != null && stall.shouldBlockQueuedActions();
        boolean burstEvidence = GrimAPI.INSTANCE.getIntegrityCorrelationManager().hasRecentWithin(
                player.uuid,
                IntegritySignal.PACKET_BURST,
                preventionCausalWindowMillis
        );

        return stallEvidence || burstEvidence;
    }

    @Override
    protected void limitFallBehind() {
        long playerClock = lastMovementPlayerClock;
        if (limitAbuseOverPing != -1 && System.nanoTime() - playerClock > limitAbuseOverPing) {
            playerClock = System.nanoTime() - limitAbuseOverPing;
        }
        timerBalanceRealTime = Math.max(timerBalanceRealTime, playerClock - clockDrift);
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        super.onReload(config);

        limitAbuseOverPing = config.getLongElse(getConfigName() + ".ping-abuse-limit-threshold", 1000L);
        if (limitAbuseOverPing != -1) {
            limitAbuseOverPing *= (long) 1e6;
        }

        preventBurstRelease = config.getBooleanElse(getConfigName() + ".hard-prevention.enabled", true);
        cancelBurstMovement = config.getBooleanElse(getConfigName() + ".hard-prevention.cancel-movement", true);
        preventionMinimumConfidence = clamp(
                config.getDoubleElse(getConfigName() + ".hard-prevention.minimum-confidence", 0.55D),
                0.0D,
                1.0D
        );
        preventionCausalWindowMillis = Math.max(250L, Math.min(10_000L,
                config.getLongElse(getConfigName() + ".hard-prevention.causal-window-ms", 1500L)));

        episodeWindowMillis = Math.max(250L, Math.min(
                10_000L,
                config.getLongElse(
                        getConfigName() + ".episode-window-ms",
                        1500L
                )
        ));
        episodeFlagIntervalMillis = Math.max(0L, Math.min(
                10_000L,
                config.getLongElse(
                        getConfigName() + ".episode-flag-interval-ms",
                        750L
                )
        ));
        maxFlagsPerEpisode = (int) Math.max(1L, Math.min(
                4L,
                config.getLongElse(
                        getConfigName() + ".max-flags-per-episode",
                        1L
                )
        ));
        episodeMaxLifetimeMillis = Math.max(
                episodeWindowMillis,
                Math.min(
                        30_000L,
                        config.getLongElse(
                                getConfigName() + ".episode-max-lifetime-ms",
                                4000L
                        )
                )
        );

        blinkMitigationSuppressUntilNanos = 0L;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
