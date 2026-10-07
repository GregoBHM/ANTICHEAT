package ac.grim.grimac.checks.impl.timer;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PacketReceiveListener;
import ac.grim.grimac.checks.type.PostPredictionListener;
import ac.grim.grimac.manager.integrity.BlinkMitigationProfile;
import ac.grim.grimac.manager.integrity.CombatIntegrityManager;
import ac.grim.grimac.manager.integrity.ConnectionProtectionState;
import ac.grim.grimac.manager.integrity.FallIntegrityManager;
import ac.grim.grimac.manager.integrity.IntegrityProfile;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.PredictionComplete;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.TimeUnit;

import static com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying.isFlying;

/**
 * Detects and mitigates selective movement stalls (Blink/FakeLag style) without treating a complete
 * connection outage as proof of cheating. It also freezes Grim's independent combat ledger from the
 * instant of the last movement packet, so waiting out an external combat tag while frozen gives no benefit.
 */
@CheckData(
        name = "ConnectionStall",
        stableKey = "grim.timer.connection_stall",
        description = "Movement packets stalled while the connection remained responsive",
        setback = 0,
        decay = 0.10
)
public final class ConnectionStall extends Check implements PacketReceiveListener, PostPredictionListener {
    private static final long NANOS_PER_MILLI = 1_000_000L;

    private boolean integrityEnabled;
    private long legacyWatchGapNanos;
    private long modernWatchGapNanos;
    private long airborneWatchGapNanos;
    private long confirmGapNanos;
    private long disconnectProtectGapNanos;
    private long combatHoldGapNanos;
    private long modernCombatHoldGapNanos;
    private int minTransactionAdvance;
    private long ambiguousAirborneSetbackNanos;
    private long recoveryCleanNanos;
    private long flagCooldownNanos;
    private long joinGraceNanos;
    private int outsideCombatRepeatThreshold;
    private boolean protectAirborne;
    private double minProtectedFallDistance;
    private boolean blinkMitigationEnabled;
    private BlinkMitigationProfile blinkProfile = BlinkMitigationProfile.BALANCED;
    private boolean protectFullFreezeAirborne;
    private long releaseGuardMinGapNanos;
    private int releaseGuardMinTransactions;
    private int releaseGuardRepeatThreshold;
    private long recoveryMaxNanos;
    private int recoveryMinMovements;
    private int recoveryMinTransactions;

    private final Object lock = new Object();
    private long lastMovementNanos = System.nanoTime();
    private int transactionAtLastMovement;
    private boolean stallActive;
    private boolean microCombatHold;
    private long stallStartNanos;
    private int transactionAtStallStart;
    private boolean stallStartedAirborne;
    private boolean selectiveConfirmed;
    private boolean flaggedThisStall;
    private boolean setbackApplied;
    private long lastFlagNanos;
    private long recoveryStartNanos;
    private int repeatedStalls;
    private long lastStallEndedNanos;
    private double protectedFallDistance;
    private boolean lastKnownAirborne;
    private double lastKnownFallDistance;
    private ConnectionProtectionState protectionState = ConnectionProtectionState.NORMAL;
    private int recoveryTransactionStart;
    private int recoveryMovementPackets;

    public ConnectionStall(GrimPlayer player) {
        super(player);
        transactionAtLastMovement = player.lastTransactionReceived.get();
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        PacketTypeCommon type = event.getPacketType();
        if (isFlying(type)) {
            GrimAPI.INSTANCE.getLagProtectionManager().observePlayer(player);
            onMovement(System.nanoTime());
        }
    }

    /** Called once per server tick from GrimPlayer.pollData(), including while no movement packets arrive. */
    public void poll() {
        GrimAPI.INSTANCE.getFallIntegrityManager().tickPlayer(player);
        GrimAPI.INSTANCE.getCombatIntegrityManager().refreshProtectedProviderTag(player.uuid);
        if (!integrityEnabled || player.disableGrim || isExemptPermission()) {
            boolean releaseHold;
            synchronized (lock) {
                releaseHold = stallActive || microCombatHold;
                resetStallLocked();
            }
            if (releaseHold) {
                GrimAPI.INSTANCE.getCombatIntegrityManager().releaseStall(player.uuid, false);
                GrimAPI.INSTANCE.getFallIntegrityManager().clear(player.uuid);
            }
            return;
        }

        long now = System.nanoTime();
        if (GrimAPI.INSTANCE.getMovementContextManager().suppressesConnectionStall(player.uuid)) {
            acceptTrustedContext(now);
            return;
        }
        if (inJoinGrace() && !combatWasActiveAtLastMovement()) return;

        Action action;
        long microHoldStart = 0L;
        synchronized (lock) {
            long gap = now - lastMovementNanos;
            long holdGap = player.canSkipTicks() ? modernCombatHoldGapNanos : combatHoldGapNanos;
            if (!stallActive && !microCombatHold && gap >= holdGap
                    && GrimAPI.INSTANCE.getCombatIntegrityManager().wasTaggedAt(player.uuid, lastMovementNanos)) {
                microCombatHold = true;
                microHoldStart = lastMovementNanos;
            }
            action = evaluateGapLocked(now, false);
        }
        if (microHoldStart != 0L) {
            // This is protection only, not a cheat signal. Even repeated sub-threshold freezes cannot consume PvP time.
            GrimAPI.INSTANCE.getCombatIntegrityManager().beginStall(player.uuid, microHoldStart, false);
        }
        execute(action, now);
    }

    private void onMovement(long now) {
        if (GrimAPI.INSTANCE.getMovementContextManager().suppressesConnectionStall(player.uuid)) {
            acceptTrustedContext(now);
            return;
        }
        if (!integrityEnabled || player.disableGrim || isExemptPermission()) {
            boolean releaseHold;
            synchronized (lock) {
                releaseHold = stallActive || microCombatHold;
                microCombatHold = false;
                lastMovementNanos = now;
                transactionAtLastMovement = player.lastTransactionReceived.get();
                resetStallLocked();
            }
            if (releaseHold) {
                GrimAPI.INSTANCE.getCombatIntegrityManager().releaseStall(player.uuid, false);
                GrimAPI.INSTANCE.getFallIntegrityManager().clear(player.uuid);
            }
            return;
        }

        Action action;
        boolean releaseMicroHold = false;
        synchronized (lock) {
            action = evaluateGapLocked(now, true);
            if (microCombatHold && !stallActive) {
                microCombatHold = false;
                releaseMicroHold = true;
            }
            lastMovementNanos = now;
            transactionAtLastMovement = player.lastTransactionReceived.get();

            if (stallActive) {
                // Once this becomes a full stall, the explicit recovery state owns the combat hold.
                microCombatHold = false;
                if (protectionState != ConnectionProtectionState.RECOVERY) {
                    protectionState = ConnectionProtectionState.RECOVERY;
                    recoveryStartNanos = now;
                    recoveryTransactionStart = player.lastTransactionReceived.get();
                    recoveryMovementPackets = 1;
                } else {
                    recoveryMovementPackets++;
                }
            } else if (lastStallEndedNanos != 0L && now - lastStallEndedNanos > TimeUnit.SECONDS.toNanos(8)) {
                repeatedStalls = 0;
            }
        }
        if (releaseMicroHold) {
            GrimAPI.INSTANCE.getCombatIntegrityManager().releaseStall(player.uuid, false);
        }
        execute(action, now);
    }

    private Action evaluateGapLocked(long now, boolean movementArrived) {
        long gap = now - lastMovementNanos;
        boolean airborne = lastKnownAirborne;
        long watchGap = airborne ? airborneWatchGapNanos : (player.canSkipTicks() ? modernWatchGapNanos : legacyWatchGapNanos);

        if (!stallActive && gap >= watchGap) {
            stallActive = true;
            microCombatHold = false;
            stallStartNanos = lastMovementNanos;
            transactionAtStallStart = transactionAtLastMovement;
            stallStartedAirborne = airborne;
            selectiveConfirmed = false;
            flaggedThisStall = false;
            setbackApplied = false;
            recoveryStartNanos = 0L;
            protectedFallDistance = Math.max(protectedFallDistance, lastKnownFallDistance);
            protectionState = ConnectionProtectionState.STALL;
            recoveryTransactionStart = 0;
            recoveryMovementPackets = 0;

            if (lastStallEndedNanos == 0L || now - lastStallEndedNanos > TimeUnit.SECONDS.toNanos(8)) {
                repeatedStalls = 1;
            } else {
                repeatedStalls++;
            }
        }

        if (!stallActive) return Action.NONE;

        // Any renewed packet gap during the clean recovery window means recovery was not actually clean.
        if (!movementArrived && recoveryStartNanos != 0L && gap >= watchGap) {
            recoveryStartNanos = 0L;
            recoveryMovementPackets = 0;
            recoveryTransactionStart = player.lastTransactionReceived.get();
            protectionState = selectiveConfirmed ? ConnectionProtectionState.LOCKDOWN : ConnectionProtectionState.PROTECTED;
        }

        protectedFallDistance = Math.max(protectedFallDistance, lastKnownFallDistance);
        int transactionAdvance = player.lastTransactionReceived.get() - transactionAtStallStart;
        boolean selective = gap >= confirmGapNanos && transactionAdvance >= minTransactionAdvance;
        if (selective) {
            selectiveConfirmed = true;
            if (blinkMitigationEnabled && protectionState != ConnectionProtectionState.RECOVERY) {
                protectionState = ConnectionProtectionState.LOCKDOWN;
            }
        }

        CombatIntegrityManager combat = GrimAPI.INSTANCE.getCombatIntegrityManager();
        boolean combatTagged = combat.isTagged(player.uuid)
                || combat.isStallHeld(player.uuid)
                || combat.wasTaggedAt(player.uuid, stallStartNanos);

        if (protectionState == ConnectionProtectionState.STALL && (combatTagged || stallStartedAirborne)) {
            protectionState = ConnectionProtectionState.PROTECTED;
        }

        boolean shouldFlag = selectiveConfirmed
                && !flaggedThisStall
                && now - lastFlagNanos >= flagCooldownNanos
                && (combatTagged || stallStartedAirborne || repeatedStalls >= outsideCombatRepeatThreshold);

        boolean shouldSetback = protectAirborne
                && protectFullFreezeAirborne
                && stallStartedAirborne
                && !selectiveConfirmed
                && !setbackApplied
                && gap >= ambiguousAirborneSetbackNanos
                && protectedFallDistance >= minProtectedFallDistance;

        boolean releaseGuard = movementArrived
                && blinkMitigationEnabled
                && selectiveConfirmed
                && !setbackApplied
                && gap >= releaseGuardMinGapNanos
                && transactionAdvance >= releaseGuardMinTransactions
                && (blinkProfile == BlinkMitigationProfile.LOCKDOWN
                    || combatTagged
                    || stallStartedAirborne
                    || repeatedStalls >= releaseGuardRepeatThreshold);

        long actionStallStart = stallStartNanos;
        boolean actionSelective = selectiveConfirmed;
        double actionFallDistance = protectedFallDistance;
        int actionRepeatedStalls = repeatedStalls;

        boolean releaseCombat = false;
        if (movementArrived && protectionState == ConnectionProtectionState.RECOVERY && recoveryStartNanos != 0L) {
            long recoveryElapsed = now - recoveryStartNanos;
            int recoveryTransactions = player.lastTransactionReceived.get() - recoveryTransactionStart;
            boolean cleanEnough = recoveryElapsed >= recoveryCleanNanos
                    && recoveryMovementPackets >= recoveryMinMovements
                    && recoveryTransactions >= recoveryMinTransactions;
            boolean safetyTimeout = recoveryElapsed >= recoveryMaxNanos && recoveryMovementPackets > 0;
            if (cleanEnough || safetyTimeout) {
                releaseCombat = true;
                finishStallLocked(now);
            }
        }

        return new Action(actionStallStart, actionSelective, shouldFlag, shouldSetback, releaseGuard, releaseCombat,
                gap, transactionAdvance, actionFallDistance, actionRepeatedStalls);
    }

    private void execute(Action action, long now) {
        if (action == Action.NONE) return;
        CombatIntegrityManager combat = GrimAPI.INSTANCE.getCombatIntegrityManager();

        // beginStall itself checks whether combat was active at the historical stall start. Calling it
        // unconditionally is important when the normal tag expired during the gap before we detected it.
        combat.beginStall(player.uuid, action.stallStartNanos, action.selective);
        if (action.selective) combat.markSelectiveEvidence(player.uuid);

        FallIntegrityManager fall = GrimAPI.INSTANCE.getFallIntegrityManager();
        if (action.airborneFallDistance > 0.0D) {
            fall.beginProtection(player.uuid, action.airborneFallDistance, action.selective);
        }

        if (action.releaseGuard && !action.flag && !isNoSetbackPermission()) {
            if (GrimAPI.INSTANCE.getMovementReleaseGuard().apply(player, true)) {
                synchronized (lock) {
                    setbackApplied = true;
                }
            }
        }

        if (action.flag) {
            synchronized (lock) {
                lastFlagNanos = now;
                flaggedThisStall = true;
                setbackApplied = true;
            }
            double correlation = GrimAPI.INSTANCE.getIntegrityCorrelationManager()
                    .record(player, ac.grim.grimac.manager.integrity.IntegritySignal.SELECTIVE_STALL);
            if (action.airborneFallDistance > 0.0D) {
                correlation = GrimAPI.INSTANCE.getIntegrityCorrelationManager()
                        .record(player, ac.grim.grimac.manager.integrity.IntegritySignal.AIRBORNE_STALL);
            }
            flagWithSetback("gap=" + TimeUnit.NANOSECONDS.toMillis(action.gapNanos)
                    + "ms trans=" + action.transactionAdvance
                    + " repeated=" + action.repeatedStalls
                    + " fall=" + formatOffset(action.airborneFallDistance)
                    + " corr=" + String.format("%.2f", correlation));
        } else if (action.setback && !isNoSetbackPermission()) {
            // Ambiguous full freezes are mitigated but not flagged. A real network outage can look the same;
            // forcing the last validated position removes the exploit without turning packet silence into a ban signal.
            player.getSetbackTeleportUtil().executeNonSimulatingSetback();
            synchronized (lock) {
                setbackApplied = true;
            }
        }

        if (action.releaseCombat) {
            combat.releaseStall(player.uuid);
            fall.releaseProtection(player.uuid);
        }
    }

    @Override
    public void onPredictionComplete(PredictionComplete predictionComplete) {
        if (!integrityEnabled || player.disableGrim || isExemptPermission()) return;

        FallIntegrityManager fall = GrimAPI.INSTANCE.getFallIntegrityManager();
        if (hasTrustedFallReset()) {
            fall.clear(player.uuid);
        }

        boolean airborne = computeAirborneFromPlayer();
        double fallDistance = player.fallDistance;
        synchronized (lock) {
            lastKnownAirborne = airborne;
            lastKnownFallDistance = fallDistance;
            if (stallActive) {
                protectedFallDistance = Math.max(protectedFallDistance, fallDistance);
                fall.updateProtection(player.uuid, protectedFallDistance);
            }
        }
    }


    /**
     * Last-chance protection for quit ordering. A player can otherwise start a very short stall just before
     * the tag expires and disconnect before the normal polling threshold is reached. No flag is generated here;
     * we only preserve combat evidence that existed at the last movement packet.
     */
    public void prepareForDisconnect() {
        if (!integrityEnabled || player.disableGrim || isExemptPermission()) return;
        long now = System.nanoTime();
        long start;
        synchronized (lock) {
            long gap = now - lastMovementNanos;
            // Legacy 1.8 must tick with flying packets, so a very small disconnect look-back is useful.
            // Modern clients can legally skip idle movement packets; only use the short threshold while airborne.
            long requiredGap = (!player.canSkipTicks() || lastKnownAirborne)
                    ? disconnectProtectGapNanos
                    : modernWatchGapNanos;
            if (gap < requiredGap) return;
            start = lastMovementNanos;
        }
        GrimAPI.INSTANCE.getCombatIntegrityManager().beginStall(player.uuid, start, false);
    }

    /** True while the current movement gap is under integrity protection. */
    public boolean isStalling() {
        synchronized (lock) {
            return stallActive;
        }
    }

    /** True only after transaction progress proves that movement is being selectively withheld. */
    public boolean isSelectiveStall() {
        synchronized (lock) {
            return stallActive && selectiveConfirmed;
        }
    }

    /** True while packets have resumed but the configured clean recovery window has not completed. */
    public boolean isRecovering() {
        synchronized (lock) {
            return stallActive && protectionState == ConnectionProtectionState.RECOVERY;
        }
    }

    /**
     * Used by StallActions to stop queued combat/world actions from being cashed in after a confirmed Blink.
     * Ambiguous full network outages do not reach this state.
     */
    public boolean shouldBlockQueuedActions() {
        synchronized (lock) {
            return stallActive && selectiveConfirmed
                    && (protectionState == ConnectionProtectionState.LOCKDOWN
                        || protectionState == ConnectionProtectionState.RECOVERY);
        }
    }

    public ConnectionProtectionState getProtectionState() {
        synchronized (lock) {
            return protectionState;
        }
    }

    public long getCurrentGapMillis() {
        synchronized (lock) {
            if (!stallActive) return 0L;
            return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lastMovementNanos));
        }
    }

    private void acceptTrustedContext(long now) {
        boolean release;
        boolean releaseMicro;
        synchronized (lock) {
            release = stallActive;
            releaseMicro = microCombatHold && !stallActive;
            microCombatHold = false;
            lastMovementNanos = now;
            transactionAtLastMovement = player.lastTransactionReceived.get();
            resetStallLocked();
        }
        if (releaseMicro) {
            GrimAPI.INSTANCE.getCombatIntegrityManager().releaseStall(player.uuid, false);
        }
        if (release) {
            GrimAPI.INSTANCE.getCombatIntegrityManager().releaseStall(player.uuid);
            if (GrimAPI.INSTANCE.getMovementContextManager().resetsFallLedger(player.uuid)) {
                GrimAPI.INSTANCE.getFallIntegrityManager().clear(player.uuid);
            } else {
                GrimAPI.INSTANCE.getFallIntegrityManager().releaseProtection(player.uuid);
            }
        }
    }

    private boolean hasTrustedFallReset() {
        if (GrimAPI.INSTANCE.getMovementContextManager().resetsFallLedger(player.uuid)) return true;
        return player.isFlying
                || player.wasFlying
                || player.inVehicle()
                || player.isClimbing
                || player.wasTouchingWater
                || player.wasTouchingLava
                || player.isGliding
                || player.wasGliding
                || player.isRiptidePose
                || player.gamemode == GameMode.CREATIVE
                || player.gamemode == GameMode.SPECTATOR;
    }

    private boolean computeAirborneFromPlayer() {
        return !player.onGround
                && !player.lastOnGround
                && !player.isFlying
                && !player.wasFlying
                && !player.inVehicle()
                && !player.isClimbing
                && !player.wasTouchingWater
                && !player.wasTouchingLava
                && !player.isGliding
                && !player.wasGliding
                && !player.isRiptidePose;
    }


    private boolean combatWasActiveAtLastMovement() {
        long movement;
        synchronized (lock) {
            movement = lastMovementNanos;
        }
        return GrimAPI.INSTANCE.getCombatIntegrityManager().wasTaggedAt(player.uuid, movement);
    }

    private boolean inJoinGrace() {
        return TimeUnit.MILLISECONDS.toNanos(System.currentTimeMillis() - player.joinTime) < joinGraceNanos;
    }

    private void finishStallLocked(long now) {
        stallActive = false;
        selectiveConfirmed = false;
        flaggedThisStall = false;
        setbackApplied = false;
        recoveryStartNanos = 0L;
        lastStallEndedNanos = now;
        protectedFallDistance = 0.0;
        stallStartedAirborne = false;
        stallStartNanos = 0L;
        transactionAtStallStart = player.lastTransactionReceived.get();
        protectionState = ConnectionProtectionState.NORMAL;
        recoveryTransactionStart = 0;
        recoveryMovementPackets = 0;
    }

    private void resetStallLocked() {
        stallActive = false;
        microCombatHold = false;
        selectiveConfirmed = false;
        flaggedThisStall = false;
        setbackApplied = false;
        recoveryStartNanos = 0L;
        protectedFallDistance = 0.0;
        protectionState = ConnectionProtectionState.NORMAL;
        recoveryTransactionStart = 0;
        recoveryMovementPackets = 0;
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        super.onReload(config);
        integrityEnabled = config.getBooleanElse(getConfigName() + ".enabled", true);
        IntegrityProfile profile = IntegrityProfile.parse(config.getStringElse("integrity-profile", "balanced"));

        long legacyMs = profile.scaleWatchMillis(config.getLongElse(getConfigName() + ".legacy-watch-gap-ms", 350L));
        long modernMs = profile.scaleWatchMillis(config.getLongElse(getConfigName() + ".modern-watch-gap-ms", 1200L));
        long airborneMs = profile.scaleWatchMillis(config.getLongElse(getConfigName() + ".airborne-watch-gap-ms", 500L));
        long confirmMs = profile.scaleWatchMillis(config.getLongElse(getConfigName() + ".confirm-gap-ms", 900L));
        long ambiguousSetbackMs = profile.scaleWatchMillis(config.getLongElse(getConfigName() + ".ambiguous-airborne-setback-ms", 1500L));
        long cleanRecoveryMs = profile.scaleRecoveryMillis(config.getLongElse(getConfigName() + ".recovery-clean-ms", 2000L));

        legacyWatchGapNanos = millis(legacyMs, 100L, 5000L);
        modernWatchGapNanos = millis(modernMs, 250L, 10_000L);
        airborneWatchGapNanos = millis(airborneMs, 100L, 5000L);
        confirmGapNanos = millis(confirmMs, 250L, 10_000L);
        disconnectProtectGapNanos = millis(config.getLongElse(getConfigName() + ".disconnect-protect-gap-ms", 150L), 50L, 5000L);
        combatHoldGapNanos = millis(config.getLongElse(getConfigName() + ".combat-hold-gap-ms", 150L), 50L, 2000L);
        modernCombatHoldGapNanos = millis(config.getLongElse(getConfigName() + ".modern-combat-hold-gap-ms", 500L), 100L, 5000L);
        minTransactionAdvance = (int) clamp(config.getLongElse(getConfigName() + ".min-transaction-advance", 3L), 1L, 20L);
        ambiguousAirborneSetbackNanos = millis(ambiguousSetbackMs, 500L, 15_000L);
        recoveryCleanNanos = millis(cleanRecoveryMs, 250L, 15_000L);
        flagCooldownNanos = millis(config.getLongElse(getConfigName() + ".flag-cooldown-ms", 3000L), 500L, 30_000L);
        joinGraceNanos = millis(config.getLongElse(getConfigName() + ".join-grace-ms", 5000L), 0L, 30_000L);
        int repeatBase = (int) clamp(config.getLongElse(getConfigName() + ".outside-combat-repeat-threshold", 2L), 1L, 10L);
        outsideCombatRepeatThreshold = profile.adjustRepeatThreshold(repeatBase);
        protectAirborne = config.getBooleanElse(getConfigName() + ".protect-airborne", true);
        minProtectedFallDistance = Math.max(0.0, config.getDoubleElse(getConfigName() + ".min-protected-fall-distance", 3.0));

        blinkMitigationEnabled = config.getBooleanElse("blink-mitigation.enabled", true);
        blinkProfile = BlinkMitigationProfile.parse(config.getStringElse("blink-mitigation.profile", "balanced"));
        String profileKey = "blink-mitigation.profiles." + blinkProfile.name().toLowerCase(java.util.Locale.ROOT);
        if (blinkMitigationEnabled) {
            legacyWatchGapNanos = millis(config.getLongElse(profileKey + ".legacy-watch-gap-ms", legacyMs), 100L, 5000L);
            modernWatchGapNanos = millis(config.getLongElse(profileKey + ".modern-watch-gap-ms", modernMs), 250L, 10_000L);
            airborneWatchGapNanos = millis(config.getLongElse(profileKey + ".airborne-watch-gap-ms", airborneMs), 100L, 5000L);
            confirmGapNanos = millis(config.getLongElse(profileKey + ".confirm-gap-ms", confirmMs), 250L, 10_000L);
            minTransactionAdvance = (int) clamp(config.getLongElse(profileKey + ".min-transaction-advance", minTransactionAdvance), 1L, 20L);
        }
        long guardGapDefault = blinkProfile == BlinkMitigationProfile.SAFE ? 1400L
                : blinkProfile == BlinkMitigationProfile.LOCKDOWN ? 750L : 1000L;
        long guardTxDefault = blinkProfile == BlinkMitigationProfile.SAFE ? 5L
                : blinkProfile == BlinkMitigationProfile.LOCKDOWN ? 2L : 3L;
        long guardRepeatDefault = blinkProfile == BlinkMitigationProfile.SAFE ? 3L : 2L;
        releaseGuardMinGapNanos = millis(config.getLongElse(profileKey + ".release-guard-min-gap-ms", guardGapDefault), 250L, 10_000L);
        releaseGuardMinTransactions = (int) clamp(config.getLongElse(profileKey + ".release-guard-min-transactions", guardTxDefault), 1L, 20L);
        releaseGuardRepeatThreshold = (int) clamp(config.getLongElse(profileKey + ".release-guard-repeat-threshold", guardRepeatDefault), 1L, 10L);
        protectFullFreezeAirborne = config.getBooleanElse(profileKey + ".protect-full-freeze-airborne", false);
        recoveryMaxNanos = millis(config.getLongElse("blink-mitigation.recovery.max-ms", 8000L), 1000L, 30_000L);
        recoveryMinMovements = (int) clamp(config.getLongElse("blink-mitigation.recovery.min-movement-packets", 8L), 1L, 100L);
        recoveryMinTransactions = (int) clamp(config.getLongElse("blink-mitigation.recovery.min-transaction-advance", 2L), 0L, 20L);
    }

    private static long millis(long value, long min, long max) {
        return clamp(value, min, max) * NANOS_PER_MILLI;
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class Action {
        static final Action NONE = new Action(0L, false, false, false, false, false, 0L, 0, 0.0, 0);

        final long stallStartNanos;
        final boolean selective;
        final boolean flag;
        final boolean setback;
        final boolean releaseGuard;
        final boolean releaseCombat;
        final long gapNanos;
        final int transactionAdvance;
        final double airborneFallDistance;
        final int repeatedStalls;

        Action(long stallStartNanos, boolean selective, boolean flag, boolean setback, boolean releaseGuard, boolean releaseCombat,
               long gapNanos, int transactionAdvance, double airborneFallDistance, int repeatedStalls) {
            this.stallStartNanos = stallStartNanos;
            this.selective = selective;
            this.flag = flag;
            this.setback = setback;
            this.releaseGuard = releaseGuard;
            this.releaseCombat = releaseCombat;
            this.gapNanos = gapNanos;
            this.transactionAdvance = transactionAdvance;
            this.airborneFallDistance = airborneFallDistance;
            this.repeatedStalls = repeatedStalls;
        }
    }
}
