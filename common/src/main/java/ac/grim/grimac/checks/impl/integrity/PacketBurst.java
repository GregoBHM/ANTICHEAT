package ac.grim.grimac.checks.impl.integrity;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PacketReceiveListener;
import ac.grim.grimac.manager.integrity.IntegritySignal;
import ac.grim.grimac.player.GrimPlayer;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.TimeUnit;

import static com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying.isFlying;

/** Frequency/burst evidence layered on top of Grim's Timer and ConnectionStall checks. */
@CheckData(name = "PacketBurst", alternativeName = "Timer", stableKey = "grim.timer.packet_burst",
        description = "Released an abnormal burst of movement packets after a packet gap", setback = -1, decay = 0.20)
public final class PacketBurst extends Check implements PacketReceiveListener {
    private static final int RING_SIZE = 128;
    private final long[] movementTimes = new long[RING_SIZE];
    private int ringIndex;
    private int ringCount;

    private long lastMovementNanos;
    private int transactionAtLastMovement;
    private long recoveryUntilNanos;
    private long recoveryGapMillis;
    private int transactionAdvanceAtGap;
    private int recoveryPackets;
    private boolean recoveryEvidenceRecorded;
    private double buffer;
    private long lastFlagNanos;

    private boolean enabled;
    private long legacyGapMs;
    private long modernGapMs;
    private long recoveryWindowMs;
    private int minimumBurstPackets;
    private int minimumTransactionAdvance;
    private double shortWindowLimit;
    private double oneSecondLimit;
    private double flagBuffer;
    private long flagCooldownNanos;
    private long selectiveStallCausalWindowMillis;

    public PacketBurst(GrimPlayer player) {
        super(player);
        transactionAtLastMovement = player.lastTransactionReceived.get();
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!enabled || !isFlying(event.getPacketType())) return;
        if (GrimAPI.INSTANCE.getMovementContextManager().suppressesConnectionStall(player.uuid)) {
            reset();
            return;
        }
        long now = System.nanoTime();
        long gapMillis = lastMovementNanos == 0L ? 0L : TimeUnit.NANOSECONDS.toMillis(now - lastMovementNanos);
        long gapThreshold = player.canSkipTicks() ? modernGapMs : legacyGapMs;

        if (lastMovementNanos != 0L && gapMillis >= gapThreshold) {
            recoveryGapMillis = gapMillis;
            transactionAdvanceAtGap = Math.max(0, player.lastTransactionReceived.get() - transactionAtLastMovement);
            recoveryPackets = 0;
            recoveryEvidenceRecorded = false;
            recoveryUntilNanos = now + TimeUnit.MILLISECONDS.toNanos(recoveryWindowMs);
        }

        addMovement(now);
        if (recoveryUntilNanos >= now) recoveryPackets++;

        double shortCount = countSince(now - TimeUnit.MILLISECONDS.toNanos(250L));
        double oneSecondCount = countSince(now - TimeUnit.SECONDS.toNanos(1L));
        boolean selectiveEvidence = transactionAdvanceAtGap >= minimumTransactionAdvance
                || GrimAPI.INSTANCE.getIntegrityCorrelationManager().hasRecentWithin(
                player.uuid, IntegritySignal.SELECTIVE_STALL, selectiveStallCausalWindowMillis);
        boolean burst = recoveryUntilNanos >= now
                && recoveryPackets >= minimumBurstPackets
                && shortCount >= shortWindowLimit
                && oneSecondCount >= oneSecondLimit;

        if (burst && selectiveEvidence) {
            double confidence = GrimAPI.INSTANCE.getLagProtectionManager().heuristicConfidence(player);
            if (confidence >= 0.45D) {
                buffer += confidence;
                double corr;
                if (!recoveryEvidenceRecorded) {
                    corr = GrimAPI.INSTANCE.getIntegrityCorrelationManager().record(player, IntegritySignal.PACKET_BURST);
                    recoveryEvidenceRecorded = true;
                } else {
                    corr = GrimAPI.INSTANCE.getIntegrityCorrelationManager().getScore(player.uuid);
                }
                if (buffer >= flagBuffer && now - lastFlagNanos >= flagCooldownNanos) {
                    lastFlagNanos = now;
                    flag("gap=" + recoveryGapMillis + "ms burst250=" + (int) shortCount
                            + " pps=" + (int) oneSecondCount + " tx=" + transactionAdvanceAtGap
                            + " corr=" + String.format(java.util.Locale.ROOT, "%.2f", corr));
                    recoveryUntilNanos = 0L;
                    recoveryPackets = 0;
                }
            }
        } else {
            buffer = Math.max(0.0D, buffer - 0.08D);
        }

        lastMovementNanos = now;
        transactionAtLastMovement = player.lastTransactionReceived.get();
    }

    private void addMovement(long now) {
        movementTimes[ringIndex] = now;
        ringIndex = (ringIndex + 1) % RING_SIZE;
        if (ringCount < RING_SIZE) ringCount++;
    }

    private int countSince(long minimumNanos) {
        int count = 0;
        for (int i = 0; i < ringCount; i++) {
            int index = ringIndex - 1 - i;
            if (index < 0) index += RING_SIZE;
            long value = movementTimes[index];
            if (value < minimumNanos) break;
            count++;
        }
        return count;
    }

    public void reset() {
        ringIndex = 0;
        ringCount = 0;
        lastMovementNanos = 0L;
        transactionAtLastMovement = player.lastTransactionReceived.get();
        recoveryUntilNanos = 0L;
        recoveryGapMillis = 0L;
        transactionAdvanceAtGap = 0;
        recoveryPackets = 0;
        recoveryEvidenceRecorded = false;
        buffer = 0.0D;
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        super.onReload(config);
        enabled = config.getBooleanElse("packet-integrity.enabled", true);
        legacyGapMs = clamp(config.getLongElse("packet-integrity.legacy-gap-ms", 250L), 100L, 5000L);
        modernGapMs = clamp(config.getLongElse("packet-integrity.modern-gap-ms", 700L), 200L, 10_000L);
        recoveryWindowMs = clamp(config.getLongElse("packet-integrity.recovery-window-ms", 500L), 100L, 3000L);
        minimumBurstPackets = (int) clamp(config.getLongElse("packet-integrity.minimum-burst-packets", 5L), 2L, 40L);
        minimumTransactionAdvance = (int) clamp(config.getLongElse("packet-integrity.minimum-transaction-advance", 2L), 1L, 20L);
        shortWindowLimit = Math.max(2.0D, config.getDoubleElse("packet-integrity.short-window-limit", 7.0D));
        oneSecondLimit = Math.max(shortWindowLimit, config.getDoubleElse("packet-integrity.one-second-limit", 25.0D));
        flagBuffer = Math.max(1.0D, config.getDoubleElse("packet-integrity.flag-buffer", 2.0D));
        flagCooldownNanos = TimeUnit.MILLISECONDS.toNanos(clamp(config.getLongElse("packet-integrity.flag-cooldown-ms", 1500L), 250L, 30_000L));
        selectiveStallCausalWindowMillis = clamp(config.getLongElse(
                "packet-integrity.selective-stall-causal-window-ms", 2000L), 250L, 10_000L);
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }
}
