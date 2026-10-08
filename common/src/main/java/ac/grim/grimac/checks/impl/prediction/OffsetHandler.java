package ac.grim.grimac.checks.impl.prediction;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.api.event.events.CompletePredictionEvent;
import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PostPredictionListener;
import ac.grim.grimac.checks.impl.timer.ConnectionStall;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.LogUtil;
import ac.grim.grimac.utils.anticheat.update.PredictionComplete;
import com.github.retrooper.packetevents.util.Vector3d;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@CheckData(
        name = "Simulation",
        stableKey = "grim.prediction.simulation",
        description = "Moved differently than predicted movement simulation",
        decay = 0.02
)
public class OffsetHandler extends Check implements PostPredictionListener {
    private static final Verbose V = Verbose.of("{offset}");
    private static final AtomicInteger flags = new AtomicInteger(0);
    private static final AtomicBoolean ENFORCEMENT_CONFIG_ERROR_LOGGED = new AtomicBoolean();

    private double setbackDecayMultiplier;
    private double threshold;
    private double immediateSetbackThreshold;
    private double maxAdvantage;
    private double maxCeiling;
    private double setbackViolationThreshold;

    private boolean enforcementEnabled;
    private boolean enforcementImmediateSetback;
    private double enforcementQuarantineOffset;
    private double enforcementStrongOffset;
    private double enforcementSevereOffset;
    private int enforcementStrongConsecutiveTicks;
    private int enforcementCleanRecoveryTicks;

    private int environmentRecoveryTicks;
    private double environmentRecoveryMultiplier;
    private boolean disableShortBlinkInSpecialEnvironment;
    private int environmentRecoveryTicksRemaining;
    private boolean wasSpecialEnvironment;

    private boolean shortBlinkEnabled;
    private long shortBlinkMinGapNanos;
    private long shortBlinkMaxGapNanos;
    private long shortBlinkRecoveryWindowNanos;
    private long shortBlinkBurstIntervalNanos;
    private int shortBlinkMinimumReleasePackets;
    private int shortBlinkMinimumTransactionAdvance;
    private double shortBlinkMinimumDistance;
    private double shortBlinkMinimumConfidence;

    private double advantageGained;
    private boolean enforcementQuarantineActive;
    private int enforcementStrongStreak;
    private int enforcementCleanTicks;

    private long lastPredictionNanos;
    private int lastPredictionTransaction;

    private long shortBlinkUntilNanos;
    private long shortBlinkLastPacketNanos;
    private int shortBlinkReleasePackets;
    private Vector3d shortBlinkStart;

    private static final CompletePredictionEvent.Channel COMPLETE_CHANNEL =
            GrimAPI.INSTANCE.getEventBus().get(CompletePredictionEvent.class);

    public OffsetHandler(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onPredictionComplete(final PredictionComplete predictionComplete) {
        final long now = System.nanoTime();
        final int currentTransaction = player.lastTransactionReceived.get();

        final long previousPredictionNanos = lastPredictionNanos;
        final int previousTransaction = lastPredictionTransaction;

        lastPredictionNanos = now;
        lastPredictionTransaction = currentTransaction;

        final long movementGapNanos = previousPredictionNanos == 0L
                ? 0L
                : Math.max(0L, now - previousPredictionNanos);

        final int transactionAdvance = previousPredictionNanos == 0L
                ? 0
                : Math.max(0, currentTransaction - previousTransaction);

        if (!predictionComplete.isChecked()) {
            resetEnforcementState();
            resetShortBlinkState();
            return;
        }

        final double offset = predictionComplete.getOffset();

        final ConnectionStall blinkOwner = player.checkManager.get(ConnectionStall.class);
        final boolean blinkMitigationOwned = blinkOwner != null && blinkOwner.ownsBlinkMitigation();
        final boolean blinkRecovery = blinkOwner != null && blinkOwner.shouldSuppressMovementSetbacks();
        final boolean blinkFreezeSafe = blinkOwner != null && blinkOwner.shouldFreezeSafePosition();

        if (blinkRecovery) {
            // The release was already mitigated by the one-shot Blink owner.
            // Remove any Simulation debt/quarantine left by the discarded burst.
            advantageGained = 0.0D;
            resetEnforcementState();
            resetShortBlinkState();

            // Only buffered packets still being discarded may not become safe.
            // Recovery movement itself is accepted so normal walking resumes.
            if (blinkFreezeSafe) {
                predictionComplete.setSafePositionUpdateBlocked(true);
            }
        }

        final boolean enforcementEligible = enforcementEnabled
                && !predictionComplete.getData().isTeleport()
                && !player.canFly
                && !player.isFlying
                && !player.inVehicle()
                && !player.getSetbackTeleportUtil().shouldBlockMovement()
                && !blinkRecovery
                && !GrimAPI.INSTANCE.getMovementContextManager()
                .suppressesConnectionStall(player.uuid);

        double enforcementMultiplier = 1.0D;
        boolean specialEnvironment = false;

        if (enforcementEligible) {
            specialEnvironment = GrimAPI.INSTANCE.getEnvironmentContextManager()
                    .isSpecialMovementEnvironment(player);

            double configuredEnvironmentMultiplier =
                    GrimAPI.INSTANCE.getEnvironmentContextManager()
                            .enforcementMultiplier(player);

            if (specialEnvironment) {
                environmentRecoveryTicksRemaining = environmentRecoveryTicks;

                if (!wasSpecialEnvironment) {
                    resetEnforcementState();
                }

                enforcementMultiplier = configuredEnvironmentMultiplier;
            } else if (environmentRecoveryTicksRemaining > 0) {
                environmentRecoveryTicksRemaining--;
                enforcementMultiplier = environmentRecoveryMultiplier;
            }

            wasSpecialEnvironment = specialEnvironment;
        }

        final double enforcementOffset = offset * enforcementMultiplier;
        final double setbackEvidenceOffset =
                enforcementEligible ? enforcementOffset : offset;

        if (!enforcementEligible) {
            resetEnforcementState();
            resetShortBlinkState();
            resetEnvironmentState();
        } else {
            boolean allowShortBlink = !blinkMitigationOwned
                    && (!disableShortBlinkInSpecialEnvironment
                    || (!specialEnvironment && environmentRecoveryTicksRemaining == 0));

            if (allowShortBlink) {
                processShortBlink(
                        predictionComplete,
                        now,
                        movementGapNanos,
                        transactionAdvance
                );
            } else {
                resetShortBlinkState();
            }

            if (enforcementQuarantineActive) {
                if (enforcementOffset < enforcementQuarantineOffset) {
                    enforcementCleanTicks++;

                    if (enforcementCleanTicks >= enforcementCleanRecoveryTicks) {
                        resetEnforcementState();
                    } else {
                        predictionComplete.setSafePositionUpdateBlocked(true);
                    }
                } else {
                    enforcementCleanTicks = 0;
                    predictionComplete.setSafePositionUpdateBlocked(true);
                }
            }
        }

        if (COMPLETE_CHANNEL.fire(player, this, offset)) {
            resetEnforcementState();
            resetShortBlinkState();
            return;
        }

        // Buffered movement that v20 already cancelled must not also become
        // Simulation VL. The one-shot Blink owner has already removed that
        // advantage and records the release as ConnectionStall evidence.
        if (blinkFreezeSafe) {
            removeOffsetLenience();
            return;
        }

        if (offset >= threshold || offset >= immediateSetbackThreshold) {
            if (!blinkRecovery) {
                advantageGained += setbackEvidenceOffset;
            }
            giveOffsetLenienceNextTick(offset);

            synchronized (flags) {
                int flagId = (flags.get() & 255) + 1;

                boolean accepted = flag(
                        V.write(verbose()).f64(offset),
                        () -> humanFormattedOffset(offset) + " /gl " + flagId
                );

                if (accepted) {
                    flags.incrementAndGet();
                    predictionComplete.setIdentifier(flagId);

                    boolean severeMovement = false;

                    if (enforcementEligible) {
                        if (enforcementOffset >= enforcementQuarantineOffset) {
                            enforcementQuarantineActive = true;
                            enforcementCleanTicks = 0;
                            predictionComplete.setSafePositionUpdateBlocked(true);
                        }

                        if (enforcementOffset >= enforcementStrongOffset) {
                            enforcementStrongStreak++;
                        } else {
                            enforcementStrongStreak = 0;
                        }

                        severeMovement = enforcementOffset >= enforcementSevereOffset
                                || enforcementStrongStreak >= enforcementStrongConsecutiveTicks;
                    }

                    if (!blinkRecovery
                            && severeMovement
                            && enforcementImmediateSetback
                            && !isNoSetbackPermission()) {
                        predictionComplete.setSafePositionUpdateBlocked(true);
                        player.getSetbackTeleportUtil().executeViolationSetback();
                    } else if (!blinkRecovery
                            && (advantageGained >= maxAdvantage
                            || setbackEvidenceOffset >= immediateSetbackThreshold)
                            && !isNoSetbackPermission()
                            && violations >= setbackViolationThreshold) {
                        player.getSetbackTeleportUtil().executeViolationSetback();
                    }
                } else if (enforcementEligible) {
                    resetEnforcementState();
                    resetShortBlinkState();
                }
            }

            advantageGained = Math.min(advantageGained, maxCeiling);
        } else {
            advantageGained *= setbackDecayMultiplier;

            if (enforcementEligible && !enforcementQuarantineActive) {
                enforcementStrongStreak = 0;
                enforcementCleanTicks = 0;
            }
        }

        removeOffsetLenience();
    }

    private void processShortBlink(
            PredictionComplete predictionComplete,
            long now,
            long movementGapNanos,
            int transactionAdvance
    ) {
        if (!shortBlinkEnabled) {
            resetShortBlinkState();
            return;
        }

        if (shortBlinkUntilNanos != 0L && now > shortBlinkUntilNanos) {
            resetShortBlinkState();
        }

        double confidence = GrimAPI.INSTANCE.getLagProtectionManager()
                .heuristicConfidence(player);

        if (movementGapNanos >= shortBlinkMinGapNanos
                && movementGapNanos <= shortBlinkMaxGapNanos
                && transactionAdvance >= shortBlinkMinimumTransactionAdvance
                && confidence >= shortBlinkMinimumConfidence) {

            shortBlinkUntilNanos = now + shortBlinkRecoveryWindowNanos;
            shortBlinkLastPacketNanos = now;
            shortBlinkReleasePackets = 1;
            shortBlinkStart = predictionComplete.getData().getFrom();

            predictionComplete.setSafePositionUpdateBlocked(true);
            return;
        }

        if (shortBlinkUntilNanos == 0L || now > shortBlinkUntilNanos) {
            return;
        }

        long packetSpacing = now - shortBlinkLastPacketNanos;

        if (packetSpacing > shortBlinkBurstIntervalNanos) {
            resetShortBlinkState();
            return;
        }

        shortBlinkLastPacketNanos = now;
        shortBlinkReleasePackets++;
        predictionComplete.setSafePositionUpdateBlocked(true);

        if (shortBlinkStart == null
                || shortBlinkReleasePackets < shortBlinkMinimumReleasePackets
                || confidence < shortBlinkMinimumConfidence) {
            return;
        }

        Vector3d to = predictionComplete.getData().getTo();

        double dx = to.getX() - shortBlinkStart.getX();
        double dy = to.getY() - shortBlinkStart.getY();
        double dz = to.getZ() - shortBlinkStart.getZ();

        double distanceSquared = dx * dx + dy * dy + dz * dz;
        double requiredDistanceSquared =
                shortBlinkMinimumDistance * shortBlinkMinimumDistance;

        if (distanceSquared < requiredDistanceSquared) {
            return;
        }

        if (!isNoSetbackPermission()
                && GrimAPI.INSTANCE.getMovementReleaseGuard()
                .apply(player, true)) {
            predictionComplete.setSafePositionUpdateBlocked(true);
            resetShortBlinkState();
        }
    }

    public void acknowledgeBlinkMitigation() {
        advantageGained = 0.0D;
        resetEnforcementState();
        resetShortBlinkState();
        removeOffsetLenience();
    }

    public static String humanFormattedOffset(double offset) {
        String humanFormattedOffset;

        if (offset < 0.001) {
            humanFormattedOffset = String.format("%.4E", offset);
            humanFormattedOffset =
                    humanFormattedOffset.replace("E-0", "E-");
        } else {
            humanFormattedOffset = String.format("%6f", offset);
            humanFormattedOffset =
                    humanFormattedOffset.replace("0.", ".");
        }

        return humanFormattedOffset;
    }

    private void giveOffsetLenienceNextTick(double offset) {
        double minimizedOffset = Math.min(offset, 1);

        player.uncertaintyHandler.lastHorizontalOffset = minimizedOffset;
        player.uncertaintyHandler.lastVerticalOffset = minimizedOffset;
    }

    private void removeOffsetLenience() {
        player.uncertaintyHandler.lastHorizontalOffset = 0;
        player.uncertaintyHandler.lastVerticalOffset = 0;
    }

    private void resetEnforcementState() {
        enforcementQuarantineActive = false;
        enforcementStrongStreak = 0;
        enforcementCleanTicks = 0;
    }

    private void resetShortBlinkState() {
        shortBlinkUntilNanos = 0L;
        shortBlinkLastPacketNanos = 0L;
        shortBlinkReleasePackets = 0;
        shortBlinkStart = null;
    }

    private void resetEnvironmentState() {
        environmentRecoveryTicksRemaining = 0;
        wasSpecialEnvironment = false;
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        setbackDecayMultiplier =
                config.getDoubleElse("Simulation.setback-decay-multiplier", 0.999);

        threshold =
                config.getDoubleElse("Simulation.threshold", 0.001);

        immediateSetbackThreshold =
                config.getDoubleElse("Simulation.immediate-setback-threshold", 0.1);

        maxAdvantage =
                config.getDoubleElse("Simulation.max-advantage", 1);

        maxCeiling =
                config.getDoubleElse("Simulation.max-ceiling", 4);

        setbackViolationThreshold =
                config.getDoubleElse("Simulation.setback-violation-threshold", 1);

        if (maxAdvantage == -1) {
            maxAdvantage = Double.MAX_VALUE;
        }

        if (immediateSetbackThreshold == -1) {
            immediateSetbackThreshold = Double.MAX_VALUE;
        }

        resetEnforcementState();
        resetShortBlinkState();
        resetEnvironmentState();

        try {
            enforcementEnabled =
                    requireBoolean(config, "movement-enforcement.enabled");

            enforcementImmediateSetback =
                    requireBoolean(config, "movement-enforcement.immediate-setback");

            enforcementQuarantineOffset =
                    requirePositiveDouble(config, "movement-enforcement.quarantine-offset");

            enforcementStrongOffset =
                    requirePositiveDouble(config, "movement-enforcement.strong-offset");

            enforcementSevereOffset =
                    requirePositiveDouble(config, "movement-enforcement.severe-offset");

            enforcementStrongConsecutiveTicks =
                    requirePositiveInt(
                            config,
                            "movement-enforcement.strong-consecutive-ticks"
                    );

            enforcementCleanRecoveryTicks =
                    requirePositiveInt(
                            config,
                            "movement-enforcement.clean-recovery-ticks"
                    );

            environmentRecoveryTicks =
                    requireNonNegativeInt(
                            config,
                            "movement-enforcement.environment.recovery-ticks"
                    );

            environmentRecoveryMultiplier =
                    requireUnitDouble(
                            config,
                            "movement-enforcement.environment.recovery-multiplier"
                    );

            disableShortBlinkInSpecialEnvironment =
                    requireBoolean(
                            config,
                            "movement-enforcement.environment.disable-short-blink"
                    );

            shortBlinkEnabled =
                    requireBoolean(
                            config,
                            "movement-enforcement.short-blink.enabled"
                    );

            long shortBlinkMinGapMillis =
                    requirePositiveLong(
                            config,
                            "movement-enforcement.short-blink.min-gap-ms"
                    );

            long shortBlinkMaxGapMillis =
                    requirePositiveLong(
                            config,
                            "movement-enforcement.short-blink.max-gap-ms"
                    );

            long shortBlinkRecoveryWindowMillis =
                    requirePositiveLong(
                            config,
                            "movement-enforcement.short-blink.recovery-window-ms"
                    );

            long shortBlinkBurstIntervalMillis =
                    requirePositiveLong(
                            config,
                            "movement-enforcement.short-blink.burst-interval-ms"
                    );

            shortBlinkMinimumReleasePackets =
                    requirePositiveInt(
                            config,
                            "movement-enforcement.short-blink.minimum-release-packets"
                    );

            shortBlinkMinimumTransactionAdvance =
                    requireNonNegativeInt(
                            config,
                            "movement-enforcement.short-blink.minimum-transaction-advance"
                    );

            shortBlinkMinimumDistance =
                    requirePositiveDouble(
                            config,
                            "movement-enforcement.short-blink.minimum-distance"
                    );

            shortBlinkMinimumConfidence =
                    requireUnitDouble(
                            config,
                            "movement-enforcement.short-blink.minimum-confidence"
                    );

            if (enforcementQuarantineOffset < threshold) {
                throw new IllegalStateException(
                        "movement-enforcement.quarantine-offset must be >= Simulation.threshold"
                );
            }

            if (enforcementStrongOffset < enforcementQuarantineOffset) {
                throw new IllegalStateException(
                        "movement-enforcement.strong-offset must be >= movement-enforcement.quarantine-offset"
                );
            }

            if (enforcementSevereOffset < enforcementStrongOffset) {
                throw new IllegalStateException(
                        "movement-enforcement.severe-offset must be >= movement-enforcement.strong-offset"
                );
            }

            if (shortBlinkMaxGapMillis < shortBlinkMinGapMillis) {
                throw new IllegalStateException(
                        "movement-enforcement.short-blink.max-gap-ms must be >= min-gap-ms"
                );
            }

            if (shortBlinkMinimumReleasePackets < 2) {
                throw new IllegalStateException(
                        "movement-enforcement.short-blink.minimum-release-packets must be >= 2"
                );
            }

            shortBlinkMinGapNanos =
                    TimeUnit.MILLISECONDS.toNanos(shortBlinkMinGapMillis);

            shortBlinkMaxGapNanos =
                    TimeUnit.MILLISECONDS.toNanos(shortBlinkMaxGapMillis);

            shortBlinkRecoveryWindowNanos =
                    TimeUnit.MILLISECONDS.toNanos(shortBlinkRecoveryWindowMillis);

            shortBlinkBurstIntervalNanos =
                    TimeUnit.MILLISECONDS.toNanos(shortBlinkBurstIntervalMillis);

            ENFORCEMENT_CONFIG_ERROR_LOGGED.set(false);

        } catch (RuntimeException ex) {
            enforcementEnabled = false;
            shortBlinkEnabled = false;

            resetEnforcementState();
            resetShortBlinkState();

            if (ENFORCEMENT_CONFIG_ERROR_LOGGED.compareAndSet(false, true)) {
                LogUtil.error(
                        "Movement enforcement configuration is invalid. "
                                + "Advantage prevention is disabled until the config is fixed "
                                + "and Grim is reloaded.",
                        ex
                );
            }
        }
    }

    private static boolean requireBoolean(ConfigManager config, String key) {
        Object raw = config.get(key);

        if (!(raw instanceof Boolean value)) {
            throw invalidConfig(key, "boolean", raw);
        }

        return value;
    }

    private static double requirePositiveDouble(
            ConfigManager config,
            String key
    ) {
        Object raw = config.get(key);

        if (!(raw instanceof Number number)) {
            throw invalidConfig(key, "positive number", raw);
        }

        double value = number.doubleValue();

        if (!Double.isFinite(value) || value <= 0.0D) {
            throw invalidConfig(key, "positive finite number", raw);
        }

        return value;
    }

    private static double requireUnitDouble(
            ConfigManager config,
            String key
    ) {
        Object raw = config.get(key);

        if (!(raw instanceof Number number)) {
            throw invalidConfig(key, "number between 0 and 1", raw);
        }

        double value = number.doubleValue();

        if (!Double.isFinite(value) || value < 0.0D || value > 1.0D) {
            throw invalidConfig(key, "finite number between 0 and 1", raw);
        }

        return value;
    }

    private static int requirePositiveInt(
            ConfigManager config,
            String key
    ) {
        Object raw = config.get(key);

        if (!(raw instanceof Number number)) {
            throw invalidConfig(key, "positive integer", raw);
        }

        int value = number.intValue();

        if (value <= 0) {
            throw invalidConfig(key, "positive integer", raw);
        }

        return value;
    }

    private static int requireNonNegativeInt(
            ConfigManager config,
            String key
    ) {
        Object raw = config.get(key);

        if (!(raw instanceof Number number)) {
            throw invalidConfig(key, "non-negative integer", raw);
        }

        int value = number.intValue();

        if (value < 0) {
            throw invalidConfig(key, "non-negative integer", raw);
        }

        return value;
    }

    private static long requirePositiveLong(
            ConfigManager config,
            String key
    ) {
        Object raw = config.get(key);

        if (!(raw instanceof Number number)) {
            throw invalidConfig(key, "positive integer", raw);
        }

        long value = number.longValue();

        if (value <= 0L) {
            throw invalidConfig(key, "positive integer", raw);
        }

        return value;
    }

    private static IllegalStateException invalidConfig(
            String key,
            String expected,
            Object actual
    ) {
        String actualType =
                actual == null ? "missing" : actual.getClass().getSimpleName();

        return new IllegalStateException(
                "Invalid SparkGrim config key '" + key
                        + "': expected " + expected
                        + ", got " + actualType
                        + ". Update to config-version 20."
        );
    }
}
