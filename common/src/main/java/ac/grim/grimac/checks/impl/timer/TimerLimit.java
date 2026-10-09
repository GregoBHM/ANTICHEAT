package ac.grim.grimac.checks.impl.timer;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.CheckData;
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
            if (!event.isCancelled()) {
                boolean flagged = flag();
                if (flagged) {
                    boolean hardPrevention = shouldPreventBurstRelease();

                    if (hardPrevention && cancelBurstMovement && shouldModifyPackets()) {
                        event.setCancelled(true);
                        player.onPacketCancel();
                    }

                    ConnectionStall stall = player.checkManager.get(ConnectionStall.class);
                    boolean suppressSetback = stall != null && stall.shouldSuppressMovementSetbacks();

                    // TimerLimit no longer owns Blink rollback. ConnectionStall's
                    // one-shot hard release barrier does. Outside such an episode,
                    // preserve Grim's original TimerLimit setback behavior.
                    if (!suppressSetback && shouldSetback()) {
                        GrimAPI.INSTANCE.getMovementReleaseGuard().apply(player, true);
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

        blinkMitigationSuppressUntilNanos = 0L;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
