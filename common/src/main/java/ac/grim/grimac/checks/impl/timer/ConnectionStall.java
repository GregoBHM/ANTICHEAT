package ac.grim.grimac.checks.impl.timer;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PacketReceiveListener;
import ac.grim.grimac.checks.type.PrePredictionPacketReceiveListener;
import ac.grim.grimac.checks.type.PostPredictionListener;
import ac.grim.grimac.checks.impl.prediction.OffsetHandler;
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

@CheckData(
        name = "ConnectionStall",
        stableKey = "grim.timer.connection_stall",
        description = "Movement packets stalled while the connection remained responsive",
        setback = 0,
        decay = 0.10
)
public final class ConnectionStall extends Check implements PrePredictionPacketReceiveListener, PacketReceiveListener, PostPredictionListener {
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
    private BlinkMitigationProfile blinkProfile;
    private boolean protectFullFreezeAirborne;
    private long releaseGuardMinGapNanos;
    private int releaseGuardMinTransactions;
    private int releaseGuardRepeatThreshold;
    private long recoveryMaxNanos;
    private int recoveryMinMovements;
    private int recoveryMinTransactions;

    private boolean hardReleaseEnabled;
    private long hardReleaseLegacyMinGapNanos;
    private long hardReleaseModernMinGapNanos;
    private long hardReleaseLongSelectiveThresholdNanos;
    private int hardReleaseMinTransactions;
    private double hardReleaseSanctionMinConfidence;
    private double hardReleasePreventionMinServerConfidence;
    private boolean shortSelectiveActionGuardEnabled;
    private boolean shortSelectiveModernActionGuardEnabled;
    private long shortSelectiveLegacyGapNanos;
    private long shortSelectiveModernGapNanos;
    private int shortSelectiveMinTransactions;
    private double shortSelectiveMinServerConfidence;
    private boolean fullFreezeProtectionEnabled;
    private long fullFreezeProtectionMinGapNanos;
    private long fullFreezeReleaseProtectNanos;
    private long hardReleaseBurstConfirmNanos;
    private long hardReleaseCancelWindowNanos;
    private long hardReleaseRecoveryNanos;
    private boolean hardReleaseSetback;
    private int hardReleaseFlagAfterRepeats;
    private long hardReleaseRepeatWindowNanos;
    private long hardReleaseFlagCooldownNanos;

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
    private long hardReleaseCandidateUntilNanos;
    private long hardReleaseCandidateFirstPacketNanos;
    private long hardReleaseCandidateGapNanos;
    private int hardReleaseCandidateTransactionAdvance;

    private boolean hardReleaseEpisodeActive;
    private boolean hardReleaseEpisodeSetbackApplied;
    private boolean hardReleaseEpisodeMitigationOwned;
    private long hardReleaseCancelUntilNanos;
    private long hardReleaseRecoveryUntilNanos;
    private int hardReleaseBlockedPackets;
    private int hardReleaseBlockedActions;
    private int hardReleaseAcceptedRecoveryPackets;

    private int hardReleaseRepeats;
    private long hardReleaseLastNanos;
    private long hardReleaseLastFlagNanos;
    private long hardReleaseSourceGapNanos;
    private int hardReleaseSourceTransactionAdvance;
    private long ambiguousReleaseProtectUntilNanos;

    public ConnectionStall(GrimPlayer player) {
        super(player);
        transactionAtLastMovement = player.lastTransactionReceived.get();
    }

    @Override
    public void onPrePredictionPacketReceive(PacketReceiveEvent event) {
        if (event.isCancelled()) return;

        PacketTypeCommon type = event.getPacketType();
        if (!isFlying(type)) return;

        long now = System.nanoTime();
        GrimAPI.INSTANCE.getLagProtectionManager().observePlayer(player);

        HardReleaseDecision hardDecision = evaluateHardRelease(now);

        if (!hardDecision.cancel || !shouldModifyPackets()) return;

        event.setCancelled(true);
        player.onPacketCancel();

        boolean mitigationOwned = false;
        if (hardDecision.confirmed) {
            if (hardDecision.applySetback && !isNoSetbackPermission()) {
                mitigationOwned = GrimAPI.INSTANCE.getMovementReleaseGuard().apply(player, true, this);
                if (!mitigationOwned && player.getSetbackTeleportUtil().shouldBlockMovement()) {
                    // Another authoritative movement correction is already active.
                    mitigationOwned = true;
                }
            } else if (!hardDecision.applySetback) {
                // Packet-tail cancellation itself owns the episode when rollback
                // is disabled by configuration.
                mitigationOwned = true;
            }

            synchronized (lock) {
                hardReleaseEpisodeSetbackApplied = mitigationOwned && hardDecision.applySetback;
                hardReleaseEpisodeMitigationOwned = mitigationOwned;
                if (mitigationOwned) setbackApplied = true;
            }

            if (mitigationOwned) {
                acknowledgeMitigatedRelease(now);
            }
        }

        boolean regularFlagged;
        synchronized (lock) {
            regularFlagged = flaggedThisStall;
        }

        if (hardDecision.flag && !regularFlagged) {
            double correlation = GrimAPI.INSTANCE.getIntegrityCorrelationManager()
                    .record(player, ac.grim.grimac.manager.integrity.IntegritySignal.SELECTIVE_STALL);

            if (flag("hard-release type=" + hardDecision.releaseType
                    + " gap=" + TimeUnit.NANOSECONDS.toMillis(hardDecision.gapNanos)
                    + "ms trans=" + hardDecision.transactionAdvance
                    + " blockedPackets=" + getHardReleaseBlockedPackets()
                    + " blockedActions=" + getHardReleaseBlockedActions()
                    + " repeated=" + hardDecision.repeats
                    + " prevention=" + String.format(java.util.Locale.ROOT, "%.2f", hardDecision.preventionConfidence)
                    + " sanction=" + String.format(java.util.Locale.ROOT, "%.2f", hardDecision.sanctionConfidence)
                    + " corr=" + String.format(java.util.Locale.ROOT, "%.2f", correlation))) {
                synchronized (lock) {
                    if (stallActive) {
                        flaggedThisStall = true;
                        lastFlagNanos = now;
                    }
                }
            }
        }
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        PacketTypeCommon type = event.getPacketType();
        if (!isFlying(type)) return;

        // Only packets that survived the pre-prediction hard barrier reach this
        // point. This keeps lastMovementNanos tied to accepted movement rather
        // than the discarded tail of a Blink release.
        long now = System.nanoTime();
        onMovement(now);

        synchronized (lock) {
                if (hardReleaseEpisodeActive
                    && now >= hardReleaseCancelUntilNanos
                    && now < hardReleaseRecoveryUntilNanos) {
                hardReleaseAcceptedRecoveryPackets++;
            }
        }
    }

    private HardReleaseDecision evaluateHardRelease(long now) {
        if (!hardReleaseEnabled
                || !integrityEnabled
                || player.disableGrim
                || isExemptPermission()
                || !shouldModifyPackets()
                || player.canFly
                || player.isFlying
                || player.inVehicle()
                || inJoinGrace()
                || GrimAPI.INSTANCE.getMovementContextManager().suppressesConnectionStall(player.uuid)) {
            synchronized (lock) {
                resetHardReleaseLocked();
            }
            return HardReleaseDecision.NONE;
        }

        long gap;
        int transactionAdvance;

        synchronized (lock) {
            if (hardReleaseEpisodeActive && now >= hardReleaseRecoveryUntilNanos) {
                finishHardReleaseEpisodeLocked();
            }

            if (hardReleaseEpisodeActive && now < hardReleaseCancelUntilNanos) {
                hardReleaseBlockedPackets++;
                return HardReleaseDecision.cancelOnly(
                        hardReleaseSourceGapNanos,
                        hardReleaseSourceTransactionAdvance,
                        hardReleaseRepeats
                );
            }

            if (hardReleaseEpisodeActive
                    && now < hardReleaseRecoveryUntilNanos
                    && hardReleaseAcceptedRecoveryPackets == 0) {
                clearHardReleaseCandidateLocked();
                return HardReleaseDecision.NONE;
            }

            if (hardReleaseCandidateUntilNanos != 0L) {
                long spacing = now - hardReleaseCandidateFirstPacketNanos;

                if (spacing > 0L
                        && spacing <= hardReleaseBurstConfirmNanos
                        && now <= hardReleaseCandidateUntilNanos) {
                    long sourceGap = hardReleaseCandidateGapNanos;
                    int sourceTransactions = hardReleaseCandidateTransactionAdvance;
                    clearHardReleaseCandidateLocked();
                    return confirmHardReleaseLocked(
                            now, sourceGap, sourceTransactions, "SELECTIVE"
                    );
                }

                if (now > hardReleaseCandidateUntilNanos
                        || spacing > hardReleaseBurstConfirmNanos) {
                    clearHardReleaseCandidateLocked();
                }
            }

            gap = now - lastMovementNanos;
            transactionAdvance = Math.max(
                    0,
                    player.lastTransactionReceived.get() - transactionAtLastMovement
            );
        }

        // Modern clients may legally omit movement packets while idle, so they
        // still require the state machine to confirm a selective stall first.
        if (player.canSkipTicks()) {
            synchronized (lock) {
                if (!stallActive || !selectiveConfirmed) {
                    return HardReleaseDecision.NONE;
                }
            }
        }

        // On legacy clients BALANCED/SAFE no longer perform destructive
        // prevention from the old 80 ms + 1 transaction fast path. LOCKDOWN
        // intentionally preserves that strict behaviour.
        if (ConnectionStallPolicy.requiresConfirmedLegacyRelease(
                player.canSkipTicks(),
                blinkProfile
        ) && !ConnectionStallPolicy.hasConfirmedSelectiveEvidence(
                gap,
                transactionAdvance,
                confirmGapNanos,
                minTransactionAdvance
        )) {
            return HardReleaseDecision.NONE;
        }

        long minimumGap = player.canSkipTicks()
                ? hardReleaseModernMinGapNanos
                : hardReleaseLegacyMinGapNanos;

        if (gap < minimumGap) {
            return HardReleaseDecision.NONE;
        }

        // Full connection freezes remain ambiguous. They are protection-only,
        // never sanctionable from the freeze itself.
        if (transactionAdvance < hardReleaseMinTransactions) {
            if (fullFreezeProtectionEnabled && gap >= fullFreezeProtectionMinGapNanos) {
                synchronized (lock) {
                    ambiguousReleaseProtectUntilNanos = Math.max(
                            ambiguousReleaseProtectUntilNanos,
                            now + fullFreezeReleaseProtectNanos
                    );
                }
            }
            return HardReleaseDecision.NONE;
        }

        // Prevention depends on server health, not player RTT. High ping therefore
        // lowers sanction confidence but cannot become a Blink bypass.
        double preventionConfidence = GrimAPI.INSTANCE.getLagProtectionManager()
                .heuristicConfidence();
        if (preventionConfidence < hardReleasePreventionMinServerConfidence) {
            return HardReleaseDecision.NONE;
        }

        // Long selective stalls are stronger evidence. Cancel the FIRST released
        // movement packet; never turn a long gap into an exemption.
        if (gap >= hardReleaseLongSelectiveThresholdNanos) {
            synchronized (lock) {
                return confirmHardReleaseLocked(
                        now, gap, transactionAdvance, "LONG_SELECTIVE"
                );
            }
        }

        // Short/normal selective release: first packet arms/freeze-safe-position,
        // second compressed/tick-paced packet confirms the episode.
        synchronized (lock) {
            hardReleaseCandidateFirstPacketNanos = now;
            hardReleaseCandidateUntilNanos = now + hardReleaseBurstConfirmNanos;
            hardReleaseCandidateGapNanos = gap;
            hardReleaseCandidateTransactionAdvance = transactionAdvance;
        }

        return HardReleaseDecision.NONE;
    }

    private HardReleaseDecision confirmHardReleaseLocked(
            long now,
            long sourceGap,
            int sourceTransactions,
            String releaseType
    ) {
        boolean rollbackAlreadyApplied = setbackApplied || hardReleaseEpisodeSetbackApplied;

        hardReleaseEpisodeActive = true;
        hardReleaseEpisodeSetbackApplied = rollbackAlreadyApplied;
        hardReleaseEpisodeMitigationOwned = rollbackAlreadyApplied;
        hardReleaseCancelUntilNanos = now + hardReleaseCancelWindowNanos;
        hardReleaseRecoveryUntilNanos = hardReleaseCancelUntilNanos + hardReleaseRecoveryNanos;
        hardReleaseBlockedPackets = 1;
        hardReleaseBlockedActions = 0;
        hardReleaseAcceptedRecoveryPackets = 0;
        hardReleaseSourceGapNanos = sourceGap;
        hardReleaseSourceTransactionAdvance = sourceTransactions;

        if (hardReleaseLastNanos == 0L
                || now - hardReleaseLastNanos > hardReleaseRepeatWindowNanos) {
            hardReleaseRepeats = 1;
        } else {
            hardReleaseRepeats++;
        }
        hardReleaseLastNanos = now;

        double preventionConfidence = GrimAPI.INSTANCE.getLagProtectionManager()
                .heuristicConfidence();
        double sanctionConfidence = GrimAPI.INSTANCE.getLagProtectionManager()
                .heuristicConfidence(player);

        boolean shouldFlag = hardReleaseRepeats >= hardReleaseFlagAfterRepeats
                && sanctionConfidence >= hardReleaseSanctionMinConfidence
                && now - hardReleaseLastFlagNanos >= hardReleaseFlagCooldownNanos;
        if (shouldFlag) hardReleaseLastFlagNanos = now;

        return new HardReleaseDecision(
                true,
                hardReleaseSetback && !rollbackAlreadyApplied,
                shouldFlag,
                true,
                releaseType,
                sourceGap,
                sourceTransactions,
                hardReleaseRepeats,
                preventionConfidence,
                sanctionConfidence
        );
    }

    private boolean hasStrongPreReleaseSelectiveEvidence(long now) {
        if (!shortSelectiveActionGuardEnabled
                || !hardReleaseEnabled
                || !blinkMitigationEnabled
                || player.disableGrim
                || isExemptPermission()
                || !shouldModifyPackets()
                || player.canFly
                || player.isFlying
                || player.inVehicle()
                || inJoinGrace()
                || GrimAPI.INSTANCE.getMovementContextManager().suppressesConnectionStall(player.uuid)) {
            return false;
        }

        // Modern clients may legally skip normal movement packets while idle.
        // Action-first protection therefore stays legacy-only by default.
        if (player.canSkipTicks() && !shortSelectiveModernActionGuardEnabled) {
            return false;
        }

        long gap;
        int transactionAdvance;
        synchronized (lock) {
            gap = Math.max(0L, now - lastMovementNanos);
            transactionAdvance = Math.max(
                    0,
                    player.lastTransactionReceived.get() - transactionAtLastMovement
            );
        }

        long minimumGap = player.canSkipTicks()
                ? shortSelectiveModernGapNanos
                : shortSelectiveLegacyGapNanos;

        double serverConfidence = GrimAPI.INSTANCE.getLagProtectionManager()
                .heuristicConfidence();
        double playerConfidence = GrimAPI.INSTANCE.getLagProtectionManager()
                .heuristicConfidence(player);

        return ConnectionStallPolicy.hasStrongPreReleaseEvidence(
                gap,
                transactionAdvance,
                minimumGap,
                confirmGapNanos,
                shortSelectiveMinTransactions,
                minTransactionAdvance,
                serverConfidence,
                playerConfidence,
                shortSelectiveMinServerConfidence
        );
    }

    private void acknowledgeMitigatedRelease(long now) {
        Timer timer = player.checkManager.get(Timer.class);
        if (timer != null) {
            timer.acknowledgeMitigatedBlink(now);
        }

        TimerLimit timerLimit = player.checkManager.get(TimerLimit.class);
        if (timerLimit != null) {
            timerLimit.acknowledgeMitigatedBlink(
                    now,
                    hardReleaseCancelWindowNanos + hardReleaseRecoveryNanos
            );
        }

        OffsetHandler simulation = player.checkManager.get(OffsetHandler.class);
        if (simulation != null) {
            simulation.acknowledgeBlinkMitigation();
        }

    }

    public void poll() {
        GrimAPI.INSTANCE.getFallIntegrityManager().tickPlayer(player);
        GrimAPI.INSTANCE.getCombatIntegrityManager().refreshProtectedProviderTag(player);
        if (!integrityEnabled || player.disableGrim || isExemptPermission()) {
            boolean releaseHold;
            synchronized (lock) {
                releaseHold = stallActive || microCombatHold;
                resetStallLocked();
                resetHardReleaseLocked();
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
            GrimAPI.INSTANCE.getCombatIntegrityManager().beginStall(player, microHoldStart);
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
                resetHardReleaseLocked();
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
            setbackApplied = hardReleaseEpisodeSetbackApplied;
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

        double sanctionConfidence = GrimAPI.INSTANCE.getLagProtectionManager()
                .heuristicConfidence(player);
        boolean shouldFlag = selectiveConfirmed
                && sanctionConfidence >= hardReleaseSanctionMinConfidence
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
        if (protectionState == ConnectionProtectionState.RECOVERY && recoveryStartNanos != 0L) {
            long recoveryElapsed = now - recoveryStartNanos;
            int recoveryTransactions = player.lastTransactionReceived.get() - recoveryTransactionStart;
            int observedRecoveryMovements = recoveryMovementPackets + (movementArrived ? 1 : 0);

            if (ConnectionStallPolicy.shouldFinishRecovery(
                    movementArrived,
                    recoveryElapsed,
                    observedRecoveryMovements,
                    recoveryTransactions,
                    recoveryCleanNanos,
                    recoveryMinMovements,
                    recoveryMinTransactions,
                    recoveryMaxNanos
            )) {
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

        combat.beginStall(player, action.stallStartNanos);

        FallIntegrityManager fall = GrimAPI.INSTANCE.getFallIntegrityManager();
        if (action.airborneFallDistance > 0.0D) {
            fall.beginProtection(player.uuid, action.airborneFallDistance);
        }

        if (action.releaseGuard
                && !action.flag
                && !shouldSuppressMovementSetbacks()
                && !isNoSetbackPermission()) {
            if (GrimAPI.INSTANCE.getMovementReleaseGuard().apply(player, true, this)) {
                synchronized (lock) {
                    setbackApplied = true;
                }
            }
        }

        if (action.flag) {
            double correlation = GrimAPI.INSTANCE.getIntegrityCorrelationManager()
                    .record(player, ac.grim.grimac.manager.integrity.IntegritySignal.SELECTIVE_STALL);
            if (action.airborneFallDistance > 0.0D) {
                correlation = GrimAPI.INSTANCE.getIntegrityCorrelationManager()
                        .record(player, ac.grim.grimac.manager.integrity.IntegritySignal.AIRBORNE_STALL);
            }
            String verbose = "gap=" + TimeUnit.NANOSECONDS.toMillis(action.gapNanos)
                    + "ms trans=" + action.transactionAdvance
                    + " repeated=" + action.repeatedStalls
                    + " fall=" + formatOffset(action.airborneFallDistance)
                    + " corr=" + String.format("%.2f", correlation);

            boolean suppressMovementSetback = shouldSuppressMovementSetbacks();
            boolean accepted = flag(verbose);
            boolean correctionApplied = false;

            if (accepted && !suppressMovementSetback && shouldSetback()) {
                correctionApplied = executeViolationSetback();
            }

            if (accepted) {
                synchronized (lock) {
                    lastFlagNanos = now;
                    flaggedThisStall = true;
                    if (correctionApplied) {
                        setbackApplied = true;
                    }
                }
                combat.markSanctionableEvidence(player.uuid);
            }
        } else if (action.setback
                && !shouldSuppressMovementSetbacks()
                && !isNoSetbackPermission()) {
            if (player.getSetbackTeleportUtil().tryExecuteNonSimulatingSetback(this)) {
                synchronized (lock) {
                    setbackApplied = true;
                }
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

    public void prepareForDisconnect() {
        if (!integrityEnabled || player.disableGrim || isExemptPermission()) return;
        long now = System.nanoTime();
        long start;
        synchronized (lock) {
            long gap = now - lastMovementNanos;
            long requiredGap = (!player.canSkipTicks() || lastKnownAirborne)
                    ? disconnectProtectGapNanos
                    : modernWatchGapNanos;
            if (gap < requiredGap) return;
            start = lastMovementNanos;
        }
        GrimAPI.INSTANCE.getCombatIntegrityManager().beginStall(player, start);
    }

    public boolean isStalling() {
        synchronized (lock) {
            return stallActive;
        }
    }

    public boolean isSelectiveStall() {
        synchronized (lock) {
            return stallActive && selectiveConfirmed;
        }
    }

    public boolean isRecovering() {
        synchronized (lock) {
            return stallActive && protectionState == ConnectionProtectionState.RECOVERY;
        }
    }

    public boolean isHardReleaseCandidateActive() {
        synchronized (lock) {
            return hardReleaseCandidateUntilNanos != 0L
                    && System.nanoTime() <= hardReleaseCandidateUntilNanos;
        }
    }

    /**
     * Stable token for the movement-correction episode currently owned by
     * ConnectionStall. Zero means ConnectionStall does not own physical
     * correction at this moment.
     */
    public long getCorrectionEpisodeId() {
        synchronized (lock) {
            long now = System.nanoTime();

            if (hardReleaseEpisodeActive && now < hardReleaseRecoveryUntilNanos) {
                if (hardReleaseLastNanos != 0L) {
                    return hardReleaseLastNanos;
                }
                return stallStartNanos;
            }

            if (!stallActive || !selectiveConfirmed) {
                return 0L;
            }

            if (protectionState == ConnectionProtectionState.LOCKDOWN) {
                return stallStartNanos;
            }

            if (protectionState == ConnectionProtectionState.RECOVERY && setbackApplied) {
                return stallStartNanos;
            }

            return 0L;
        }
    }

    public boolean ownsBlinkMitigation() {
        return hardReleaseEnabled && blinkMitigationEnabled;
    }

    public boolean shouldFreezeSafePosition() {
        synchronized (lock) {
            long now = System.nanoTime();
            boolean candidate = hardReleaseCandidateUntilNanos != 0L
                    && now <= hardReleaseCandidateUntilNanos;
            boolean discardingRelease = hardReleaseEpisodeActive
                    && now < hardReleaseCancelUntilNanos;
            boolean ambiguousFreeze = fullFreezeProtectionEnabled
                    && stallActive
                    && !selectiveConfirmed
                    && now - lastMovementNanos >= fullFreezeProtectionMinGapNanos;
            boolean ambiguousRelease = now < ambiguousReleaseProtectUntilNanos;
            return candidate || discardingRelease || ambiguousFreeze || ambiguousRelease;
        }
    }

    public boolean shouldSuppressMovementSetbacks() {
        synchronized (lock) {
            long now = System.nanoTime();

            if (hardReleaseCandidateUntilNanos != 0L) {
                if (now <= hardReleaseCandidateUntilNanos) {
                    return true;
                }
                clearHardReleaseCandidateLocked();
            }

            if (!hardReleaseEpisodeActive) return false;

            if (now >= hardReleaseRecoveryUntilNanos) {
                finishHardReleaseEpisodeLocked();
                return false;
            }

            // Only suppress competing movement setbacks after the one-shot
            // mitigation actually owns the episode. If rollback failed, let
            // Simulation/Timer repair the first released movement normally.
            return hardReleaseEpisodeMitigationOwned;
        }
    }

    public boolean shouldBlockQueuedActions() {
        long now = System.nanoTime();
        boolean strongPreReleaseEvidence = hasStrongPreReleaseSelectiveEvidence(now);

        synchronized (lock) {
            return ConnectionStallPolicy.shouldBlockQueuedActions(
                    strongPreReleaseEvidence,
                    hardReleaseEpisodeActive,
                    now,
                    hardReleaseCancelUntilNanos,
                    stallActive,
                    selectiveConfirmed,
                    protectionState
            );
        }
    }

    /** Only confirmed mitigation windows are sanctionable queued-action evidence. */
    public boolean shouldFlagQueuedActions() {
        synchronized (lock) {
            long now = System.nanoTime();
            return ConnectionStallPolicy.shouldFlagQueuedActions(
                    hardReleaseEpisodeActive,
                    now,
                    hardReleaseCancelUntilNanos,
                    stallActive,
                    selectiveConfirmed,
                    protectionState
            );
        }
    }

    public void recordBlockedAction() {
        synchronized (lock) {
            hardReleaseBlockedActions++;
        }
    }

    public int getHardReleaseBlockedPackets() {
        synchronized (lock) {
            return hardReleaseBlockedPackets;
        }
    }

    public int getHardReleaseBlockedActions() {
        synchronized (lock) {
            return hardReleaseBlockedActions;
        }
    }

    public ConnectionProtectionState getProtectionState() {
        synchronized (lock) {
            return protectionState;
        }
    }

    public long getCurrentGapMillis() {
        synchronized (lock) {
            long now = System.nanoTime();

            if (!stallActive
                    && hardReleaseCandidateUntilNanos != 0L
                    && now <= hardReleaseCandidateUntilNanos
                    && hardReleaseCandidateGapNanos > 0L) {
                return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(hardReleaseCandidateGapNanos));
            }

            if (!stallActive
                    && hardReleaseEpisodeActive
                    && now < hardReleaseRecoveryUntilNanos
                    && hardReleaseSourceGapNanos > 0L) {
                return Math.max(
                        0L,
                        TimeUnit.NANOSECONDS.toMillis(hardReleaseSourceGapNanos)
                );
            }

            if (!stallActive) {
                return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(now - lastMovementNanos));
            }

            return Math.max(
                    0L,
                    TimeUnit.NANOSECONDS.toMillis(now - lastMovementNanos)
            );
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
            resetHardReleaseLocked();
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
        ambiguousReleaseProtectUntilNanos = 0L;
    }

    private void clearHardReleaseCandidateLocked() {
        hardReleaseCandidateUntilNanos = 0L;
        hardReleaseCandidateFirstPacketNanos = 0L;
        hardReleaseCandidateGapNanos = 0L;
        hardReleaseCandidateTransactionAdvance = 0;
    }

    private void finishHardReleaseEpisodeLocked() {
        hardReleaseEpisodeActive = false;
        hardReleaseEpisodeSetbackApplied = false;
        hardReleaseEpisodeMitigationOwned = false;
        hardReleaseCancelUntilNanos = 0L;
        hardReleaseRecoveryUntilNanos = 0L;
        hardReleaseBlockedPackets = 0;
        hardReleaseBlockedActions = 0;
        hardReleaseAcceptedRecoveryPackets = 0;
        hardReleaseSourceGapNanos = 0L;
        hardReleaseSourceTransactionAdvance = 0;
    }

    private void resetHardReleaseLocked() {
        clearHardReleaseCandidateLocked();
        finishHardReleaseEpisodeLocked();
        hardReleaseRepeats = 0;
        hardReleaseLastNanos = 0L;
        hardReleaseLastFlagNanos = 0L;
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

        hardReleaseEnabled = config.getBooleanElse("blink-mitigation.hard-release.enabled", true);
        hardReleaseLegacyMinGapNanos = millis(
                config.getLongElse("blink-mitigation.hard-release.legacy-min-gap-ms", 80L),
                50L,
                5000L
        );
        hardReleaseModernMinGapNanos = millis(
                config.getLongElse("blink-mitigation.hard-release.modern-min-gap-ms", 700L),
                250L,
                10_000L
        );
        long legacyLongSelectiveThreshold = config.getLongElse(
                "blink-mitigation.hard-release.max-gap-ms",
                1600L
        );
        hardReleaseLongSelectiveThresholdNanos = millis(
                config.getLongElse(
                        "blink-mitigation.hard-release.long-selective-threshold-ms",
                        legacyLongSelectiveThreshold
                ),
                250L,
                15_000L
        );
        hardReleaseMinTransactions = (int) clamp(
                config.getLongElse("blink-mitigation.hard-release.min-transaction-advance", 1L),
                1L,
                20L
        );
        hardReleaseSanctionMinConfidence = clampDouble(
                config.getDoubleElse(
                        "blink-mitigation.hard-release.sanction-minimum-confidence",
                        config.getDoubleElse("blink-mitigation.hard-release.minimum-confidence", 0.80D)
                ),
                0.0D,
                1.0D
        );
        hardReleasePreventionMinServerConfidence = clampDouble(
                config.getDoubleElse(
                        "blink-mitigation.hard-release.prevention-minimum-server-confidence",
                        0.55D
                ),
                0.0D,
                1.0D
        );
        shortSelectiveActionGuardEnabled = config.getBooleanElse(
                "blink-mitigation.short-selective-action-guard.enabled", false
        );
        shortSelectiveModernActionGuardEnabled = config.getBooleanElse(
                "blink-mitigation.short-selective-action-guard.modern-enabled", false
        );
        shortSelectiveLegacyGapNanos = millis(
                config.getLongElse("blink-mitigation.short-selective-action-guard.legacy-min-gap-ms", 80L),
                50L,
                2000L
        );
        shortSelectiveModernGapNanos = millis(
                config.getLongElse("blink-mitigation.short-selective-action-guard.modern-min-gap-ms", 700L),
                250L,
                5000L
        );
        shortSelectiveMinTransactions = (int) clamp(
                config.getLongElse("blink-mitigation.short-selective-action-guard.min-transaction-advance", 1L),
                1L,
                20L
        );
        shortSelectiveMinServerConfidence = clampDouble(
                config.getDoubleElse("blink-mitigation.short-selective-action-guard.minimum-server-confidence", 0.55D),
                0.0D,
                1.0D
        );

        fullFreezeProtectionEnabled = config.getBooleanElse(
                "blink-mitigation.full-freeze-protection.enabled",
                true
        );
        fullFreezeProtectionMinGapNanos = millis(
                config.getLongElse("blink-mitigation.full-freeze-protection.min-gap-ms", 1500L),
                500L,
                15_000L
        );
        fullFreezeReleaseProtectNanos = millis(
                config.getLongElse("blink-mitigation.full-freeze-protection.release-protect-ms", 350L),
                100L,
                2000L
        );
        hardReleaseBurstConfirmNanos = millis(
                config.getLongElse("blink-mitigation.hard-release.burst-confirm-max-interval-ms", 100L),
                20L,
                200L
        );
        hardReleaseCancelWindowNanos = millis(
                config.getLongElse("blink-mitigation.hard-release.cancel-window-ms", 180L),
                50L,
                1000L
        );
        hardReleaseRecoveryNanos = millis(
                config.getLongElse("blink-mitigation.hard-release.recovery-no-setback-ms", 600L),
                100L,
                3000L
        );
        hardReleaseSetback = config.getBooleanElse("blink-mitigation.hard-release.setback", true);
        hardReleaseFlagAfterRepeats = (int) clamp(
                config.getLongElse("blink-mitigation.hard-release.flag-after-repeats", 3L),
                2L,
                20L
        );
        hardReleaseRepeatWindowNanos = millis(
                config.getLongElse("blink-mitigation.hard-release.repeat-window-ms", 8000L),
                1000L,
                30_000L
        );
        hardReleaseFlagCooldownNanos = millis(
                config.getLongElse("blink-mitigation.hard-release.flag-cooldown-ms", 3000L),
                500L,
                30_000L
        );

        resetHardReleaseLocked();
    }

    private static long millis(long value, long min, long max) {
        return clamp(value, min, max) * NANOS_PER_MILLI;
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clampDouble(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private record HardReleaseDecision(
            boolean cancel,
            boolean applySetback,
            boolean flag,
            boolean confirmed,
            String releaseType,
            long gapNanos,
            int transactionAdvance,
            int repeats,
            double preventionConfidence,
            double sanctionConfidence
    ) {
        static final HardReleaseDecision NONE =
                new HardReleaseDecision(false, false, false, false, "NONE", 0L, 0, 0, 0.0D, 0.0D);

        static HardReleaseDecision cancelOnly(
                long gapNanos,
                int transactionAdvance,
                int repeats
        ) {
            return new HardReleaseDecision(
                    true, false, false, false, "OWNED",
                    gapNanos, transactionAdvance, repeats, 1.0D, 0.0D
            );
        }
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
