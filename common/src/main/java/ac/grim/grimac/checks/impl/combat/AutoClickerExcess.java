package ac.grim.grimac.checks.impl.combat;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PacketReceiveListener;
import ac.grim.grimac.manager.integrity.IntegritySignal;
import ac.grim.grimac.player.GrimPlayer;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAnimation;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.TimeUnit;

/**
 * Conservative autoclicker coverage for protocol-impossible/excessive swing delivery.
 *
 * <p>This deliberately does not flag high CPS, jitter clicking, butterfly clicking, or
 * statistically regular human clicking. Vanilla can legitimately produce very fast
 * input, but it cannot repeatedly send several primary-hand swing packets inside the
 * same client tick. Network burst evidence makes the sample neutral rather than guilty.</p>
 */
@CheckData(
        name = "Autoclicker",
        stableKey = "grim.combat.autoclicker_excess",
        description = "Sent repeated excessive swing packets inside client ticks",
        setback = -1,
        decay = 0.10
)
public final class AutoClickerExcess extends Check implements PacketReceiveListener {
    private static final int RING_SIZE = 64;
    private final long[] swingTimes = new long[RING_SIZE];

    private int ringIndex;
    private int ringCount;
    private int swingsThisTick;
    private int excessiveTickStreak;
    private long lastFlagNanos;

    private boolean enabled;
    private int perTickThreshold;
    private int requiredExcessiveTicks;
    private int emergencyPerTickThreshold;
    private int sustainedOneSecondLimit;
    private long flagCooldownNanos;
    private long networkCausalWindowMillis;
    private double minimumConfidence;

    public AutoClickerExcess(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!enabled) return;

        if (isTickPacket(event.getPacketType())) {
            evaluateCompletedTick(System.nanoTime());
            swingsThisTick = 0;
        }

        if (!isPrimaryHandSwing(event)) return;

        long now = System.nanoTime();
        if (networkUncertain()) {
            resetEvidence();
            return;
        }

        swingsThisTick++;
        swingTimes[ringIndex] = now;
        ringIndex = (ringIndex + 1) % RING_SIZE;
        if (ringCount < RING_SIZE) ringCount++;
    }

    private void evaluateCompletedTick(long now) {
        if (networkUncertain()) {
            resetEvidence();
            return;
        }

        if (swingsThisTick >= perTickThreshold) {
            excessiveTickStreak++;
        } else {
            excessiveTickStreak = Math.max(0, excessiveTickStreak - 1);
        }

        int oneSecond = countSince(now - TimeUnit.SECONDS.toNanos(1L));
        boolean emergency = swingsThisTick >= emergencyPerTickThreshold;
        boolean persistent = excessiveTickStreak >= requiredExcessiveTicks
                && oneSecond >= sustainedOneSecondLimit;

        if ((!emergency && !persistent)
                || now - lastFlagNanos < flagCooldownNanos) {
            return;
        }

        lastFlagNanos = now;
        double correlation = GrimAPI.INSTANCE.getIntegrityCorrelationManager()
                .record(player, IntegritySignal.AUTOCLICKER_EXCESS);

        flagComponent(
                "autoclicker.excess",
                "swingsTick=" + swingsThisTick
                        + " swings1s=" + oneSecond
                        + " streak=" + excessiveTickStreak
                        + " corr=" + String.format(java.util.Locale.ROOT, "%.2f", correlation)
        );

        // Preserve observation history, but require fresh repeated evidence
        // before another flag.
        excessiveTickStreak = 0;
    }

    private boolean networkUncertain() {
        if (GrimAPI.INSTANCE.getLagProtectionManager()
                .heuristicConfidence(player) < minimumConfidence) {
            return true;
        }

        var correlation = GrimAPI.INSTANCE.getIntegrityCorrelationManager();
        return correlation.hasRecentWithin(
                player.uuid,
                IntegritySignal.SELECTIVE_STALL,
                networkCausalWindowMillis
        ) || correlation.hasRecentWithin(
                player.uuid,
                IntegritySignal.PACKET_BURST,
                networkCausalWindowMillis
        );
    }

    private boolean isPrimaryHandSwing(PacketReceiveEvent event) {
        if (!isAnimation(event.getPacketType())) return false;

        // Protocol 47 PUNCH is a primary-hand swing. Modern ANIMATION packets
        // include the hand explicitly.
        if (event.getPacketType() == PacketType.Play.Client.PUNCH) {
            return true;
        }

        return new WrapperPlayClientAnimation(event).getHand()
                == InteractionHand.MAIN_HAND;
    }

    private int countSince(long minimumNanos) {
        int result = 0;
        for (int i = 0; i < ringCount; i++) {
            int current = ringIndex - 1 - i;
            if (current < 0) current += RING_SIZE;
            long time = swingTimes[current];
            if (time < minimumNanos) break;
            result++;
        }
        return result;
    }

    private void resetEvidence() {
        ringIndex = 0;
        ringCount = 0;
        swingsThisTick = 0;
        excessiveTickStreak = 0;
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        enabled = config.getBooleanElse(
                "autoclicker-excess.enabled",
                true
        );
        perTickThreshold = (int) clamp(
                config.getLongElse("autoclicker-excess.per-tick-threshold", 3L),
                2L,
                10L
        );
        requiredExcessiveTicks = (int) clamp(
                config.getLongElse("autoclicker-excess.required-excessive-ticks", 4L),
                2L,
                20L
        );
        emergencyPerTickThreshold = (int) clamp(
                config.getLongElse("autoclicker-excess.emergency-per-tick-threshold", 6L),
                perTickThreshold + 1L,
                20L
        );
        sustainedOneSecondLimit = (int) clamp(
                config.getLongElse("autoclicker-excess.sustained-one-second-limit", 32L),
                21L,
                80L
        );
        flagCooldownNanos = TimeUnit.MILLISECONDS.toNanos(clamp(
                config.getLongElse("autoclicker-excess.flag-cooldown-ms", 2500L),
                500L,
                30_000L
        ));
        networkCausalWindowMillis = clamp(
                config.getLongElse("autoclicker-excess.network-causal-window-ms", 1500L),
                250L,
                10_000L
        );
        minimumConfidence = clamp(
                config.getDoubleElse("autoclicker-excess.minimum-confidence", 0.80D),
                0.0D,
                1.0D
        );
        resetEvidence();
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
