package ac.grim.grimac.manager.integrity;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfirmedSanctionEpisodesAuditTest {

    private static String source(String relative) throws IOException {
        return Files.readString(
                Path.of("src/main/java/ac/grim/grimac").resolve(relative)
        );
    }

    @Test
    void confirmedEpisodeLedgerIsLongWindowAndNonDestructive() throws IOException {
        String manager = source(
                "manager/integrity/SanctionEpisodeManager.java"
        );

        assertTrue(manager.contains("retention-seconds"));
        assertTrue(manager.contains("7200L"));
        assertTrue(manager.contains("max-episodes-per-family"));
        assertTrue(manager.contains("post-continuity-grace-ms"));
        assertTrue(manager.contains("clean-recovery-predictions"));
        assertTrue(manager.contains("minimum-server-confidence"));
        assertTrue(manager.contains("minimum-player-confidence"));
        assertTrue(manager.contains("minimum-tps"));
        assertFalse(manager.contains("kick "));
        assertFalse(manager.contains("ban "));
        assertFalse(manager.contains("event.setCancelled"));
    }

    @Test
    void episodeTokensAreNamespacedPerConnection() throws IOException {
        String manager = source(
                "manager/integrity/SanctionEpisodeManager.java"
        );

        assertTrue(manager.contains("\"join=\""));
        assertTrue(manager.contains("player.joinTime"));
    }

    @Test
    void everyFreshConnectionStartsInSanctionRecovery() throws IOException {
        String playerData = source("utils/anticheat/PlayerDataManager.java");

        assertTrue(playerData.contains(
                "markContinuityBreak(player.uuid, \"join\")"
        ));
    }

    @Test
    void confirmedHistoryIsNotClearedOnPlayerDisconnect() throws IOException {
        String playerData = source("utils/anticheat/PlayerDataManager.java");

        assertFalse(playerData.contains(
                "getSanctionEpisodeManager().clear(uuid)"
        ));
        assertTrue(playerData.contains(
                "detectionEpisodeManager.clearAll()"
        ));
    }

    @Test
    void bukkitContinuityBreaksGateFutureConfirmations() throws IOException {
        String listener = Files.readString(Path.of(
                "../bukkit/src/main/java/ac/grim/grimac/platform/bukkit/events/MovementContextListener.java"
        ));

        assertTrue(listener.contains("markContinuityBreak("));
        assertTrue(listener.contains("\"teleport:\""));
        assertTrue(listener.contains("\"world-change\""));
        assertTrue(listener.contains("\"respawn\""));
        assertTrue(listener.contains("\"gamemode-change\""));
    }

    @Test
    void onlyCleanPredictionsRecoverEpisodeEligibility() throws IOException {
        String simulation = source(
                "checks/impl/prediction/OffsetHandler.java"
        );
        String episodes = source(
                "manager/integrity/SanctionEpisodeManager.java"
        );

        assertFalse(simulation.contains("noteCheckedPrediction(player.uuid)"));
        assertTrue(simulation.contains("noteCleanPrediction(player.uuid)"));
        assertTrue(episodes.contains("public void noteCleanPrediction("));

        int suspiciousBranch = simulation.indexOf(
                "if (offset >= threshold || offset >= immediateSetbackThreshold)"
        );
        int cleanRecovery = simulation.indexOf(
                "noteCleanPrediction(player.uuid)"
        );
        assertTrue(suspiciousBranch >= 0 && cleanRecovery > suspiciousBranch);

        assertTrue(simulation.contains("\"simulation-normal\""));
        assertTrue(simulation.contains("normalMaxFlagsPerEpisode"));
        assertTrue(simulation.contains("normalEpisodeMaxLifetimeMillis"));
        assertTrue(simulation.contains("normalEpisodeFlagAccepted"));
        assertTrue(simulation.contains("normalEpisodeConfirmed"));
        assertTrue(simulation.contains("normalEpisodeCorrectionApplied"));
        assertTrue(simulation.contains("resetNormalEpisodeState(true)"));
        assertTrue(simulation.contains(
                "if (predictionComplete.getData().isTeleport())"
        ));
        assertTrue(simulation.contains(
                "clearSharedEpisode(\n                    \"simulation-normal\""
        ));
        assertFalse(simulation.contains("if (!specialFlagAllowed)"));
        assertTrue(simulation.contains(
                "Episode budgets cap VL/alerts only"
        ));
        assertTrue(simulation.contains(
                "SanctionEpisodeManager.FAMILY_MOVEMENT"
        ));
        assertTrue(simulation.contains("\"simulation.normal\""));
    }

    @Test
    void timerAndTimerLimitAreBoundedByPhysicalEpisodes() throws IOException {
        String timer = source("checks/impl/timer/Timer.java");
        String limit = source("checks/impl/timer/TimerLimit.java");

        assertTrue(timer.contains("resolveSharedEpisode("));
        assertTrue(timer.contains("\"timer-normal\""));
        assertTrue(timer.contains("maxFlagsPerEpisode"));
        assertTrue(timer.contains("episodeMaxLifetimeMillis"));
        assertTrue(timer.contains(
                "SanctionEpisodeManager.FAMILY_TIMER"
        ));

        assertTrue(limit.contains("DetectionEpisodeManager.Decision episode"));
        assertTrue(limit.contains("\"timerlimit.release\""));
        assertTrue(limit.contains("resolveSharedEpisode("));
        assertTrue(limit.contains("\"timerlimit-release\""));
        assertTrue(limit.contains("episodeMaxLifetimeMillis"));
        assertTrue(limit.contains("maxFlagsPerEpisode"));
        assertFalse(limit.contains(
                "getSanctionEpisodeManager().confirm("
        ));
    }

    @Test
    void noSlowNormalUsesPhysicalItemUseSessionAsEpisodeIdentity()
            throws IOException {
        String noSlow = source(
                "checks/impl/movement/NoSlow.java"
        );

        assertTrue(noSlow.contains(
                "\"noslow.normal|session=\""
        ));
        assertTrue(noSlow.contains(
                "\"itemuse#\" + session.sessionId()"
        ));
        assertTrue(noSlow.contains("\"noslow-normal\""));
        assertTrue(noSlow.contains("resolveSharedEpisode("));
        assertTrue(noSlow.contains("normalMaxFlagsPerEpisode"));
        assertTrue(noSlow.contains("normalEpisodeMaxLifetimeMillis"));
        assertTrue(noSlow.contains(
                "SanctionEpisodeManager.FAMILY_ITEM_USE"
        ));
    }

    @Test
    void groundChecksShareEpisodeButOnlyNoFallConfirmsHistory()
            throws IOException {
        String noFall = source(
                "checks/impl/groundspoof/NoFall.java"
        );
        String groundSpoof = source(
                "checks/impl/prediction/GroundSpoof.java"
        );

        assertTrue(noFall.contains("\"ground-integrity\""));
        assertTrue(groundSpoof.contains("\"ground-integrity\""));
        assertTrue(noFall.contains("episodeMaxLifetimeMillis"));
        assertTrue(groundSpoof.contains("episodeMaxLifetimeMillis"));
        assertTrue(groundSpoof.contains(
                "clearSharedEpisode(\n                    \"ground-integrity\""
        ));
        assertTrue(noFall.contains("boolean blinkRecovery"));
        assertTrue(noFall.contains(
                "if (blinkRecovery)"
        ));
        assertTrue(noFall.contains(
                "Do not create NoFall evidence or rewrite"
        ));
        assertTrue(groundSpoof.contains("boolean blinkRecovery"));
        assertTrue(groundSpoof.contains(
                "if (blinkRecovery)"
        ));
        assertTrue(groundSpoof.contains(
                "must neither create evidence nor schedule a second packet-state"
        ));
        assertTrue(noFall.contains(
                "SanctionEpisodeManager.FAMILY_GROUND"
        ));
        assertFalse(groundSpoof.contains(
                "getSanctionEpisodeManager().confirm("
        ));
    }

    @Test
    void antiKbAndCombatChecksFeedIndependentConfirmedEpisodes()
            throws IOException {
        String knockback = source(
                "checks/impl/velocity/KnockbackHandler.java"
        );
        String reach = source(
                "checks/impl/combat/Reach.java"
        );

        assertTrue(knockback.contains(
                "SanctionEpisodeManager.FAMILY_VELOCITY"
        ));
        assertTrue(knockback.contains(
                "\"kb#tx=\" + player.likelyKB.transaction"
        ));
        assertTrue(reach.contains(
                "SanctionEpisodeManager.FAMILY_COMBAT"
        ));
        assertTrue(reach.contains(
                "\"attack#tx=\""
        ));
    }

    @Test
    void recoveryIsScopedToNewEpisodesNotLegacySanctionVl() throws IOException {
        String punishment = source("manager/PunishmentManager.java");
        String episodes = source(
                "manager/integrity/SanctionEpisodeManager.java"
        );

        int method = punishment.indexOf("private boolean sanctionableNow()");
        int next = punishment.indexOf("private static double clamp01", method);
        String block = punishment.substring(method, next);

        assertFalse(block.contains("isRecoveryBlocked(player.uuid)"));
        assertTrue(episodes.contains("recoveryBlockedLocked("));
        assertTrue(episodes.contains("if (recoveryBlockedLocked(existing, now))"));
    }

    @Test
    void newMovementFamiliesUseConfirmedEpisodesNotLegacySanctionVl()
            throws IOException {
        String punishment = source("manager/PunishmentManager.java");

        assertFalse(punishment.contains(
                "case \"grim.prediction.simulation\" ->"
        ));
        assertFalse(punishment.contains(
                "case \"grim.movement.noslow\" ->"
        ));
        assertFalse(punishment.contains(
                "case \"grim.groundspoof.no_fall\" ->"
        ));
        assertFalse(punishment.contains(
                "case \"grim.timer.limit\" -> true"
        ));
        assertFalse(punishment.contains(
                "case \"grim.groundspoof.fake\" -> true"
        ));
    }

    @Test
    void punishmentLayerExposesEpisodeMetricsButDoesNotAutobanByItself()
            throws IOException {
        String punishment = source("manager/PunishmentManager.java");

        assertTrue(punishment.contains(
                "case \"confirmed_episodes_10m\""
        ));
        assertTrue(punishment.contains(
                "case \"confirmed_episodes_2h\""
        ));
        assertTrue(punishment.contains(
                "TimeUnit.HOURS.toMillis(2L)"
        ));
        assertTrue(punishment.contains(
                "case \"confirmed_episodes\","
        ));
        assertTrue(punishment.contains(
                "\"confirmed_episodes_retention\""
        ));
        assertTrue(punishment.contains(
                "case \"episode_confidence\""
        ));
        assertTrue(punishment.contains(
                "case \"episode_recovery\""
        ));
        assertTrue(punishment.contains(
                "%confirmed_episodes_10m%"
        ));
        assertTrue(punishment.contains(
                "%confirmed_episodes_2h%"
        ));
        assertTrue(punishment.contains(
                "%confirmed_episodes_retention%"
        ));
        assertTrue(punishment.contains(
                "%confirmed_episodes%"
        ));
        assertTrue(punishment.contains(
                "%episode_recovery_reason%"
        ));
        assertTrue(punishment.contains(
                "SanctionEpisodeManager.Snapshot episodeSnapshot"
        ));
        assertTrue(punishment.contains(
                ".snapshot(player.uuid, episodeFamily)"
        ));
    }

    @Test
    void confirmedEpisodeRetentionIsPurgedFromTheGlobalTick() throws IOException {
        String manager = source(
                "manager/integrity/SanctionEpisodeManager.java"
        );
        String tick = source(
                "manager/tick/impl/ClientVersionSetter.java"
        );

        assertTrue(manager.contains("public void tick()"));
        assertTrue(manager.contains("purgeAll()"));
        assertTrue(tick.contains(
                "getSanctionEpisodeManager().tick()"
        ));
    }
    @Test
    void continuityIsRecheckedAtomicallyBeforeConfirmation()
            throws IOException {
        String manager = source(
                "manager/integrity/SanctionEpisodeManager.java"
        );

        int lock = manager.indexOf("synchronized (state)");
        int comment = manager.indexOf(
                "Re-check continuity while holding the same state lock"
        );
        int blocked = manager.indexOf(
                "if (recoveryBlockedLocked(state, now))",
                comment
        );
        int add = manager.indexOf("episodes.addLast(", blocked);

        assertTrue(lock >= 0);
        assertTrue(comment > lock);
        assertTrue(blocked > comment);
        assertTrue(add > blocked);
    }


    @Test
    void confirmedEpisodeBookkeepingRunsBeforeAcceptedFlagAlerts()
            throws IOException {
        String check = source("checks/Check.java");
        String timer = source("checks/impl/timer/Timer.java");
        String simulation = source(
                "checks/impl/prediction/OffsetHandler.java"
        );
        String noSlow = source(
                "checks/impl/movement/NoSlow.java"
        );
        String noFall = source(
                "checks/impl/groundspoof/NoFall.java"
        );
        String knockback = source(
                "checks/impl/velocity/KnockbackHandler.java"
        );
        String reach = source(
                "checks/impl/combat/Reach.java"
        );

        int callback = check.indexOf("afterRecordBeforeAlert.run()");
        int alert = check.indexOf("alert(alertText)", callback);
        assertTrue(callback >= 0 && alert > callback);

        assertTrue(timer.contains(
                "flagComponent(\n                    \"timer.normal\""
        ));
        assertTrue(timer.contains(
                "() -> {\n                        if (confirmEpisode)"
        ));

        assertTrue(simulation.contains(
                "confirmNormalOnAcceptedFlag"
        ));
        assertTrue(simulation.contains(
                "afterRecordBeforeAlert"
        ) || check.contains("afterRecordBeforeAlert"));

        assertTrue(noSlow.contains(
                "flagComponentWithSetback("
        ));
        assertTrue(noFall.contains(
                "() -> confirmSanctionEpisode("
        ));
        assertTrue(knockback.contains(
                "if (strongEpisode)"
        ));
        assertTrue(reach.contains(
                "() -> confirmSanctionEpisode("
        ));
    }


    @Test
    void episodeSnapshotIsAtomicPerFamily() throws IOException {
        String manager = source(
                "manager/integrity/SanctionEpisodeManager.java"
        );

        assertTrue(manager.contains(
                "public @NotNull Snapshot snapshot("
        ));
        assertTrue(manager.contains(
                "synchronized (state)"
        ));
        assertTrue(manager.contains(
                "confirmedEpisodes10m"
        ));
        assertTrue(manager.contains(
                "confirmedEpisodes2h"
        ));
        assertTrue(manager.contains(
                "confirmedEpisodesRetention"
        ));
    }


    @Test
    void disabledEpisodeLedgerDoesNotAllocatePlayerState()
            throws IOException {
        String manager = source(
                "manager/integrity/SanctionEpisodeManager.java"
        );

        int confirm = manager.indexOf("public @NotNull Decision confirm(");
        int disabled = manager.indexOf("if (!enabled)", confirm);
        int allocate = manager.indexOf("states.computeIfAbsent(", confirm);

        assertTrue(confirm >= 0);
        assertTrue(disabled > confirm);
        assertTrue(allocate > disabled);
        assertTrue(manager.contains(
                "Disabled means fully inert"
        ));
    }


    @Test
    void blockedEpisodeTokensCanNeverBePromotedLater()
            throws IOException {
        String manager = source(
                "manager/integrity/SanctionEpisodeManager.java"
        );

        int confirmedLookup = manager.indexOf(
                "state.episodes.get(normalizedFamily)"
        );
        int rejectedLookup = manager.indexOf(
                "state.rejectedEpisodes.get(normalizedFamily)"
        );
        int remember = manager.indexOf(
                "rememberRejectedLocked("
        );
        int confirmedAdd = manager.indexOf(
                "episodes.addLast("
        );

        assertTrue(confirmedLookup >= 0);
        assertTrue(rejectedLookup > confirmedLookup);
        assertTrue(remember > rejectedLookup);
        assertTrue(confirmedAdd > remember);
        assertTrue(manager.contains(
                "final Map<String, Deque<RejectedEpisode>> rejectedEpisodes"
        ));
        assertTrue(manager.contains(
                "state.rejectedEpisodes.isEmpty()"
        ));
        assertTrue(manager.contains(
                "now - rejected.peekFirst().timeNanos > storageRetentionNanos"
        ));
    }


    @Test
    void onlyStrongRepeatedHardBlinkFeedsNetworkEpisodeHistory()
            throws IOException {
        String stall = source(
                "checks/impl/timer/ConnectionStall.java"
        );

        int ambiguousFreeze = stall.indexOf(
                "if (transactionAdvance < hardReleaseMinTransactions)"
        );
        int ambiguousReturn = stall.indexOf(
                "return HardReleaseDecision.NONE;",
                ambiguousFreeze
        );
        int repeatGate = stall.indexOf(
                "hardReleaseRepeats >= hardReleaseFlagAfterRepeats"
        );
        int confidenceGate = stall.indexOf(
                "sanctionConfidence >= hardReleaseSanctionMinConfidence",
                repeatGate
        );
        int hardFlag = stall.indexOf(
                "if (hardDecision.flag)"
        );
        int networkEpisode = stall.indexOf(
                "SanctionEpisodeManager.FAMILY_NETWORK",
                hardFlag
        );

        assertTrue(ambiguousFreeze >= 0);
        assertTrue(ambiguousReturn > ambiguousFreeze);
        assertTrue(repeatGate >= 0);
        assertTrue(confidenceGate > repeatGate);
        assertTrue(hardFlag >= 0);
        assertTrue(networkEpisode > hardFlag);

        assertTrue(stall.contains(
                "\"connectionstall.hard-release\""
        ));
        assertTrue(stall.contains(
                "\"blink#release=\" + hardDecision.episodeId"
        ));
        assertTrue(stall.contains(
                "long episodeId"
        ));
        assertTrue(stall.contains(
                "true,\n                now,\n                releaseType"
        ));
    }

    @Test
    void ordinaryConnectionStallFlagsDoNotFeedNetworkEpisodeHistory()
            throws IOException {
        String stall = source(
                "checks/impl/timer/ConnectionStall.java"
        );

        int execute = stall.indexOf(
                "private void execute(Action action, long now)"
        );
        int prediction = stall.indexOf(
                "@Override\n    public void onPredictionComplete",
                execute
        );

        assertTrue(execute >= 0 && prediction > execute);

        String ordinaryStallPath = stall.substring(
                execute,
                prediction
        );

        assertFalse(ordinaryStallPath.contains(
                "SanctionEpisodeManager.FAMILY_NETWORK"
        ));
    }


    @Test
    void fixedTwoHourWindowSurvivesShorterConfiguredRetention()
            throws IOException {
        String manager = source(
                "manager/integrity/SanctionEpisodeManager.java"
        );

        assertTrue(manager.contains(
                "private volatile long storageRetentionNanos"
        ));
        assertTrue(manager.contains(
                "storageRetentionNanos = Math.max("
        ));
        assertTrue(manager.contains(
                "TimeUnit.HOURS.toNanos(2L)"
        ));
        assertTrue(manager.contains(
                "now - episodes.peekFirst().timeNanos > storageRetentionNanos"
        ));
        assertTrue(manager.contains(
                "now - rejected.peekFirst().timeNanos > storageRetentionNanos"
        ));
        assertTrue(manager.contains(
                "TimeUnit.NANOSECONDS.toMillis(retentionNanos)"
        ));
    }


    @Test
    void unrelatedChecksDoNotDisplayNetworkEpisodeHistory()
            throws IOException {
        String manager = source(
                "manager/integrity/SanctionEpisodeManager.java"
        );

        assertTrue(manager.contains(
                "public static final String FAMILY_OTHER = \"OTHER\""
        ));
        assertTrue(manager.contains(
                "case \"grim.timer.connection_stall\","
        ));
        assertTrue(manager.contains(
                "\"grim.timer.packet_burst\","
        ));
        assertTrue(manager.contains(
                "\"grim.exploit.stall_actions\" -> FAMILY_NETWORK"
        ));
        assertTrue(manager.contains(
                "default -> FAMILY_OTHER"
        ));
    }


    @Test
    void confirmedEpisodeDecisionCarriesAtomicTwoHourCount()
            throws IOException {
        String manager = source(
                "manager/integrity/SanctionEpisodeManager.java"
        );

        assertTrue(manager.contains(
                "int confirmedEpisodes2h"
        ));
        assertTrue(manager.contains(
                "TimeUnit.HOURS.toNanos(2L)"
        ));
        assertTrue(manager.contains(
                "tenMinutes,\n                twoHours,\n                retained"
        ));
    }

    @Test
    void newConfirmedEpisodeIsTheOnlyAutomaticKickTrigger()
            throws IOException {
        String check = source("checks/Check.java");
        String punishment = source("manager/PunishmentManager.java");

        assertTrue(check.contains(
                "public final @NotNull SanctionEpisodeManager.Decision confirmSanctionEpisode("
        ));
        assertTrue(check.contains(
                "if (decision.recorded())"
        ));
        assertTrue(check.contains(
                "player.punishmentManager.handleConfirmedEpisode(this, decision)"
        ));

        assertTrue(punishment.contains(
                "public void handleConfirmedEpisode("
        ));
        assertTrue(punishment.contains(
                "|| !decision.recorded()"
        ));
        assertTrue(punishment.contains(
                "confirmedEpisodeKickExecuted.compareAndSet("
        ));
        assertFalse(punishment.contains(
                "handleConfirmedEpisode(this"
        ));
    }

    @Test
    void phaseThreeAKicksOnlyReliableFamiliesAndNeverBans()
            throws IOException {
        String punishment = source("manager/PunishmentManager.java");

        assertTrue(punishment.contains(
                "case SanctionEpisodeManager.FAMILY_TIMER"
        ));
        assertTrue(punishment.contains(
                "case SanctionEpisodeManager.FAMILY_VELOCITY"
        ));
        assertTrue(punishment.contains(
                "case SanctionEpisodeManager.FAMILY_COMBAT"
        ));
        assertTrue(punishment.contains(
                "case SanctionEpisodeManager.FAMILY_MOVEMENT,"
        ));
        assertTrue(punishment.contains(
                "SanctionEpisodeManager.FAMILY_NETWORK ->\n                    false"
        ));
        assertTrue(punishment.contains(
                "startsWith(\"kick \")"
        ));
        assertFalse(punishment.contains(
                "startsWith(\"ban \")"
        ));
        assertTrue(punishment.contains(
                "\"grim.velocity.anti_knockback\".equals(key)"
        ));
        assertTrue(punishment.contains(
                "sensitiveLegacyEvidenceAllowed()"
        ));
    }

    @Test
    void legacyVlKickConditionsAreDisabledByDefaultOutsideCrash()
            throws IOException {
        String punishment = source("manager/PunishmentManager.java");

        assertTrue(punishment.contains(
                "\"sanctions.legacy-vl-actions-enabled\",\n                false"
        ));
        assertTrue(punishment.contains(
                "command.legacySanctionVlCondition"
        ));
        assertTrue(punishment.contains(
                "&& !legacyVlActionsEnabled"
        ));
        assertTrue(punishment.contains(
                "&& !isCrashCheck(check)"
        ));
    }

    @Test
    void allConfirmedEpisodeProducersUseCentralSanctionBridge()
            throws IOException {
        String timer = source("checks/impl/timer/Timer.java");
        String simulation = source(
                "checks/impl/prediction/OffsetHandler.java"
        );
        String noSlow = source(
                "checks/impl/movement/NoSlow.java"
        );
        String noFall = source(
                "checks/impl/groundspoof/NoFall.java"
        );
        String knockback = source(
                "checks/impl/velocity/KnockbackHandler.java"
        );
        String reach = source("checks/impl/combat/Reach.java");
        String stall = source("checks/impl/timer/ConnectionStall.java");

        for (String producer : new String[]{
                timer,
                simulation,
                noSlow,
                noFall,
                knockback,
                reach,
                stall
        }) {
            assertTrue(producer.contains("confirmSanctionEpisode("));
            assertFalse(producer.contains(
                    ".getSanctionEpisodeManager().confirm("
            ));
        }
    }


}
