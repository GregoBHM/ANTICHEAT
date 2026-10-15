package ac.grim.grimac.checks.impl.scaffolding;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.BlockPlaceCheck;
import ac.grim.grimac.checks.type.BlockPlaceListener;
import ac.grim.grimac.manager.integrity.IntegritySignal;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.BlockPlace;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.TimeUnit;

/**
 * Sustained impossible block-placement cadence.
 *
 * <p>This check never cancels/resyncs a placement. It intentionally ignores
 * isolated fast clicks and network release bursts, and only emits evidence
 * after repeated dense placement windows on a healthy connection.</p>
 */
@CheckData(
        name = "FastPlace",
        stableKey = "grim.scaffolding.fast_place",
        description = "Placed blocks at a persistently excessive client cadence",
        setback = -1,
        decay = 0.12
)
public final class FastPlace extends BlockPlaceCheck implements BlockPlaceListener {
    private static final int RING_SIZE = 64;
    private final long[] placements = new long[RING_SIZE];

    private int ringIndex;
    private int ringCount;
    private int strongWindows;
    private long lastFlagNanos;

    private boolean enabled;
    private int shortWindowLimit;
    private int oneSecondLimit;
    private int requiredStrongWindows;
    private long shortWindowNanos;
    private long flagCooldownNanos;
    private long networkCausalWindowMillis;
    private double minimumConfidence;

    public FastPlace(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onBlockPlace(BlockPlace place) {
        if (!enabled || !place.isBlock || place.isCancelled()) return;

        long now = System.nanoTime();
        if (networkUncertain()) {
            resetEvidence();
            return;
        }

        placements[ringIndex] = now;
        ringIndex = (ringIndex + 1) % RING_SIZE;
        if (ringCount < RING_SIZE) ringCount++;

        int shortCount = countSince(now - shortWindowNanos);
        int oneSecondCount = countSince(now - TimeUnit.SECONDS.toNanos(1L));

        if (shortCount >= shortWindowLimit
                && oneSecondCount >= oneSecondLimit) {
            strongWindows++;
        } else {
            strongWindows = Math.max(0, strongWindows - 1);
        }

        if (strongWindows < requiredStrongWindows
                || now - lastFlagNanos < flagCooldownNanos) {
            return;
        }

        lastFlagNanos = now;
        double correlation = GrimAPI.INSTANCE.getIntegrityCorrelationManager()
                .record(player, IntegritySignal.FAST_PLACE);

        flagComponent(
                "fastplace.sustained",
                "short=" + shortCount
                        + " oneSecond=" + oneSecondCount
                        + " strong=" + strongWindows
                        + " corr=" + String.format(java.util.Locale.ROOT, "%.2f", correlation)
        );

        // Require a new sustained episode for the next flag. Placements remain
        // untouched so normal building is never resynced by this heuristic.
        strongWindows = 0;
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

    private int countSince(long minimumNanos) {
        int result = 0;
        for (int i = 0; i < ringCount; i++) {
            int current = ringIndex - 1 - i;
            if (current < 0) current += RING_SIZE;
            long time = placements[current];
            if (time < minimumNanos) break;
            result++;
        }
        return result;
    }

    private void resetEvidence() {
        ringIndex = 0;
        ringCount = 0;
        strongWindows = 0;
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        enabled = config.getBooleanElse("fast-place-integrity.enabled", true);
        shortWindowNanos = TimeUnit.MILLISECONDS.toNanos(clamp(
                config.getLongElse("fast-place-integrity.short-window-ms", 300L),
                150L,
                1000L
        ));
        shortWindowLimit = (int) clamp(
                config.getLongElse("fast-place-integrity.short-window-limit", 7L),
                3L,
                20L
        );
        oneSecondLimit = (int) clamp(
                config.getLongElse("fast-place-integrity.one-second-limit", 22L),
                6L,
                40L
        );
        requiredStrongWindows = (int) clamp(
                config.getLongElse("fast-place-integrity.required-strong-windows", 4L),
                2L,
                20L
        );
        flagCooldownNanos = TimeUnit.MILLISECONDS.toNanos(clamp(
                config.getLongElse("fast-place-integrity.flag-cooldown-ms", 2500L),
                500L,
                30_000L
        ));
        networkCausalWindowMillis = clamp(
                config.getLongElse("fast-place-integrity.network-causal-window-ms", 1500L),
                250L,
                10_000L
        );
        minimumConfidence = clamp(
                config.getDoubleElse("fast-place-integrity.minimum-confidence", 0.80D),
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
