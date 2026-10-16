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
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.TimeUnit;

/**
 * Experimental long-window autoclicker evidence.
 *
 * <p>High CPS alone is intentionally insufficient. The check looks for a long,
 * combat-correlated sequence whose client-tick intervals are both extremely
 * low-variance and strongly periodic. Network withholding/burst windows are
 * excluded from the sequence. It never cancels input and never contributes to
 * the shared sanction score.</p>
 */
@CheckData(
        name = "AutoClickerPattern",
        stableKey = "grim.combat.autoclicker_pattern",
        description = "Repeated combat clicks followed an unusually mechanical tick cadence",
        setback = -1,
        decay = 0.05,
        experimental = true
)
public final class AutoClickerPattern extends Check implements PacketReceiveListener {
    private static final int RING_SIZE = 64;
    private final int[] intervals = new int[RING_SIZE];

    private int ringIndex;
    private int ringCount;
    private long clientTick;
    private long lastSwingTick = -1L;
    private long lastAttackTick = Long.MIN_VALUE;
    private long lastEvaluationTick;
    private int suspiciousWindows;
    private long lastFlagNanos;

    private boolean enabled;
    private int samples;
    private int evaluationStrideTicks;
    private int requiredWindows;
    private int maximumIntervalTicks;
    private long combatRecencyTicks;
    private long networkCausalWindowMillis;
    private long flagCooldownNanos;
    private double minimumConfidence;
    private double minimumCps;
    private double maximumVariance;
    private double minimumPeriodicity;
    private double minimumModeRatio;

    public AutoClickerPattern(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!enabled) return;

        if (isTickPacket(event.getPacketType())) {
            clientTick++;
        }

        if (isAttack(event)) {
            lastAttackTick = clientTick;
            return;
        }

        if (!isPrimaryHandSwing(event)) return;

        if (networkUncertain()) {
            // Do not bridge an interval over an unusable network window.
            lastSwingTick = -1L;
            suspiciousWindows = Math.max(0, suspiciousWindows - 1);
            return;
        }

        if (lastSwingTick >= 0L) {
            long deltaLong = clientTick - lastSwingTick;

            if (deltaLong <= 0L) {
                // Same-tick excess is owned by AutoClickerExcess.
                return;
            }

            if (deltaLong > maximumIntervalTicks) {
                // A genuine long pause ends the mechanical sequence.
                clearPatternSequence();
            } else {
                addInterval((int) deltaLong);
            }
        }

        lastSwingTick = clientTick;

        if (ringCount < samples
                || clientTick - lastEvaluationTick < evaluationStrideTicks
                || !combatContext()) {
            return;
        }

        lastEvaluationTick = clientTick;
        PatternStats stats = analyzeRecent(samples);

        boolean suspicious = stats.cps >= minimumCps
                && stats.variance <= maximumVariance
                && stats.periodicity >= minimumPeriodicity
                && stats.modeRatio >= minimumModeRatio;

        if (suspicious) {
            suspiciousWindows++;
        } else {
            suspiciousWindows = Math.max(0, suspiciousWindows - 1);
        }

        if (suspiciousWindows < requiredWindows) return;

        long now = System.nanoTime();
        if (now - lastFlagNanos < flagCooldownNanos) return;
        lastFlagNanos = now;

        flagComponent(
                "autoclicker.pattern",
                "samples=" + samples
                        + " cps=" + format(stats.cps)
                        + " variance=" + format(stats.variance)
                        + " periodicity=" + format(stats.periodicity)
                        + " modeRatio=" + format(stats.modeRatio)
                        + " windows=" + suspiciousWindows
        );

        suspiciousWindows = Math.max(0, requiredWindows - 1);
    }

    private PatternStats analyzeRecent(int requestedSamples) {
        int count = Math.min(requestedSamples, ringCount);
        if (count <= 1) return PatternStats.NONE;

        double sum = 0.0D;
        int modeValue = -1;
        int modeCount = 0;

        for (int value = 1; value <= maximumIntervalTicks; value++) {
            int occurrences = 0;
            for (int i = 0; i < count; i++) {
                int sample = recent(i);
                sum += value == 1 ? 0.0D : 0.0D;
                if (sample == value) occurrences++;
            }
            if (occurrences > modeCount) {
                modeCount = occurrences;
                modeValue = value;
            }
        }

        sum = 0.0D;
        for (int i = 0; i < count; i++) {
            sum += recent(i);
        }

        double mean = sum / count;
        double variance = 0.0D;
        for (int i = 0; i < count; i++) {
            double diff = recent(i) - mean;
            variance += diff * diff;
        }
        variance /= count;

        double bestPeriodicity = 0.0D;
        for (int period = 1; period <= 4; period++) {
            if (count <= period) break;
            int compared = 0;
            int equal = 0;
            for (int i = 0; i < count - period; i++) {
                compared++;
                if (recent(i) == recent(i + period)) equal++;
            }
            if (compared > 0) {
                bestPeriodicity = Math.max(
                        bestPeriodicity,
                        equal / (double) compared
                );
            }
        }

        double cps = mean <= 0.0D ? 0.0D : 20.0D / mean;
        double modeRatio = modeValue < 0
                ? 0.0D
                : modeCount / (double) count;

        return new PatternStats(cps, variance, bestPeriodicity, modeRatio);
    }

    private void addInterval(int ticks) {
        intervals[ringIndex] = ticks;
        ringIndex = (ringIndex + 1) % RING_SIZE;
        if (ringCount < RING_SIZE) ringCount++;
    }

    private int recent(int back) {
        int index = ringIndex - 1 - back;
        while (index < 0) index += RING_SIZE;
        return intervals[index];
    }

    private boolean combatContext() {
        if (GrimAPI.INSTANCE.getCombatIntegrityManager()
                .isTagged(player.uuid)) {
            return true;
        }

        return lastAttackTick != Long.MIN_VALUE
                && clientTick - lastAttackTick <= combatRecencyTicks;
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

        if (event.getPacketType() == PacketType.Play.Client.PUNCH) {
            return true;
        }

        return new WrapperPlayClientAnimation(event).getHand()
                == InteractionHand.MAIN_HAND;
    }

    private static boolean isAttack(PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.ATTACK) {
            return true;
        }

        return event.getPacketType() == PacketType.Play.Client.INTERACT_ENTITY
                && new WrapperPlayClientInteractEntity(event).getAction()
                == WrapperPlayClientInteractEntity.InteractAction.ATTACK;
    }

    private void clearPatternSequence() {
        ringIndex = 0;
        ringCount = 0;
        suspiciousWindows = 0;
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        enabled = config.getBooleanElse(
                "autoclicker-pattern.enabled",
                true
        );
        samples = (int) clamp(
                config.getLongElse("autoclicker-pattern.samples", 32L),
                24L,
                RING_SIZE
        );
        evaluationStrideTicks = (int) clamp(
                config.getLongElse("autoclicker-pattern.evaluation-stride-ticks", 8L),
                4L,
                40L
        );
        requiredWindows = (int) clamp(
                config.getLongElse("autoclicker-pattern.required-windows", 3L),
                2L,
                10L
        );
        maximumIntervalTicks = (int) clamp(
                config.getLongElse("autoclicker-pattern.maximum-interval-ticks", 6L),
                2L,
                12L
        );
        combatRecencyTicks = clamp(
                config.getLongElse("autoclicker-pattern.combat-recency-ticks", 40L),
                10L,
                200L
        );
        networkCausalWindowMillis = clamp(
                config.getLongElse("autoclicker-pattern.network-causal-window-ms", 2000L),
                250L,
                10_000L
        );
        flagCooldownNanos = TimeUnit.MILLISECONDS.toNanos(clamp(
                config.getLongElse("autoclicker-pattern.flag-cooldown-ms", 5000L),
                1000L,
                60_000L
        ));
        minimumConfidence = clamp(
                config.getDoubleElse("autoclicker-pattern.minimum-confidence", 0.85D),
                0.0D,
                1.0D
        );
        minimumCps = clamp(
                config.getDoubleElse("autoclicker-pattern.minimum-cps", 8.0D),
                4.0D,
                20.0D
        );
        maximumVariance = clamp(
                config.getDoubleElse("autoclicker-pattern.maximum-variance", 0.10D),
                0.0D,
                2.0D
        );
        minimumPeriodicity = clamp(
                config.getDoubleElse("autoclicker-pattern.minimum-periodicity", 0.95D),
                0.50D,
                1.0D
        );
        minimumModeRatio = clamp(
                config.getDoubleElse("autoclicker-pattern.minimum-mode-ratio", 0.85D),
                0.50D,
                1.0D
        );

        clientTick = 0L;
        lastSwingTick = -1L;
        lastAttackTick = Long.MIN_VALUE;
        lastEvaluationTick = 0L;
        lastFlagNanos = 0L;
        clearPatternSequence();
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private record PatternStats(
            double cps,
            double variance,
            double periodicity,
            double modeRatio
    ) {
        private static final PatternStats NONE =
                new PatternStats(0.0D, Double.MAX_VALUE, 0.0D, 0.0D);
    }
}
