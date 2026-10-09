package ac.grim.grimac.checks.impl.timer;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PrePredictionPacketReceiveListener;
import ac.grim.grimac.player.GrimPlayer;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import org.jetbrains.annotations.NotNull;

@CheckData(name = "Timer", stableKey = "grim.timer.timer", configName = "TimerA", description = "The players game is running faster than normal", setback = 10)
public class Timer extends Check implements PrePredictionPacketReceiveListener {
    protected long timerBalanceRealTime = 0;
    private volatile long lastAheadNanos;

    protected long knownPlayerClockTime = (long) (System.nanoTime() - 6e10);
    protected long lastMovementPlayerClock = (long) (System.nanoTime() - 6e10);

    protected long clockDrift;
    protected boolean hasGottenMovementAfterTransaction = false;

    public Timer(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onPrePredictionPacketReceive(final PacketReceiveEvent event) {
        if (event.isCancelled()) return;

        if (hasGottenMovementAfterTransaction && checkForTransaction(event.getPacketType())) {
            knownPlayerClockTime = lastMovementPlayerClock;
            lastMovementPlayerClock = player.getPlayerClockAtLeast();
            hasGottenMovementAfterTransaction = false;
        }

        if (!shouldCountPacketForTimer(event.getPacketType())) return;

        hasGottenMovementAfterTransaction = true;
        timerBalanceRealTime += 50e6;

        doCheck(event);
    }

    public void doCheck(final PacketReceiveEvent event) {
        long now = System.nanoTime();
        lastAheadNanos = Math.max(0L, timerBalanceRealTime - now);

        if (timerBalanceRealTime > now) {
            if (flag()) {
                if (shouldModifyPackets()) {
                    event.setCancelled(true);
                    player.onPacketCancel();
                }

                ConnectionStall stall = player.checkManager.get(ConnectionStall.class);
                boolean blinkOwned = stall != null
                        && (stall.shouldSuppressMovementSetbacks()
                        || stall.isHardReleaseCandidateActive());

                if (!blinkOwned && shouldSetback()) {
                    player.getSetbackTeleportUtil().executeNonSimulatingSetback(this);
                }
            }

            timerBalanceRealTime -= 50e6;
        }

        limitFallBehind();
    }

    /**
     * Removes only the positive timer balance already consumed by a Blink release
     * that v20 has rolled back. Violation history is intentionally preserved.
     */
    public void acknowledgeMitigatedBlink(long nowNanos) {
        timerBalanceRealTime = Math.min(timerBalanceRealTime, nowNanos);
        hasGottenMovementAfterTransaction = false;
    }

    /** Last positive client-clock lead observed on the current/most recent tick packet. */
    public long getLastAheadMillis() {
        return lastAheadNanos / 1_000_000L;
    }

    protected void limitFallBehind() {
        timerBalanceRealTime = Math.max(timerBalanceRealTime, lastMovementPlayerClock - clockDrift);
    }

    public boolean checkForTransaction(PacketTypeCommon packetType) {
        return packetType == PacketType.Play.Client.PONG ||
                packetType == PacketType.Play.Client.WINDOW_CONFIRMATION;
    }

    public boolean shouldCountPacketForTimer(PacketTypeCommon packetType) {
        return isTickPacket(packetType);
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        clockDrift = (long) (config.getDoubleElse(getConfigName() + ".drift", 120.0) * 1e6);
    }
}
