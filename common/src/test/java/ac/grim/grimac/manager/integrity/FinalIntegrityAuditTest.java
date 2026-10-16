package ac.grim.grimac.manager.integrity;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FinalIntegrityAuditTest {

    private static String source(String relative) throws IOException {
        return Files.readString(Path.of("src/main/java/ac/grim/grimac").resolve(relative));
    }

    @Test
    void regenIsActuallyRegisteredAndResetOnRespawn() throws IOException {
        String manager = source("manager/CheckManager.java");
        String respawn = source("events/packets/PacketPlayerRespawn.java");
        String regen = source("checks/impl/integrity/RegenPacket.java");

        assertTrue(manager.contains(".put(RegenPacket.class, new RegenPacket(player))"));
        assertTrue(respawn.contains("get(RegenPacket.class).handleRespawn()"));
        assertTrue(regen.contains("public void handleRespawn()"));
    }

    @Test
    void trustedStallWindowsDoNotEraseHistoricalBlinkEvidence() throws IOException {
        String stall = source("checks/impl/timer/ConnectionStall.java");

        int method = stall.indexOf("private void acceptTrustedContext(long now)");
        int next = stall.indexOf("private boolean hasTrustedFallReset()", method);
        String block = stall.substring(method, next);

        assertTrue(block.contains("MovementContextType.TELEPORT"));
        assertTrue(block.contains("finishStallLocked(now)"));
        assertTrue(block.contains("clearHardReleaseCandidateLocked()"));
        assertTrue(block.contains("finishHardReleaseEpisodeLocked()"));
        assertTrue(block.contains("if (hardBoundary)"));
    }

    @Test
    void combatDisconnectRequiresSanctionableEvidence() throws IOException {
        String combat = source("manager/integrity/CombatIntegrityManager.java");
        String stall = source("checks/impl/timer/ConnectionStall.java");

        assertTrue(combat.contains("sanctionableEvidence"));
        assertTrue(combat.contains("CombatDisconnectPolicy.shouldPunish("));
        assertTrue(stall.contains("markSanctionableEvidence(player.uuid)"));
        assertFalse(combat.contains(
                "return active && (punishAllCombatQuits || state.selectiveEvidence)"
        ));
    }

    @Test
    void combatPlusRefreshIsEntityScheduled() throws IOException {
        String combat = source("manager/integrity/CombatIntegrityManager.java");

        assertTrue(combat.contains("refreshProtectedProviderTag(@NotNull GrimPlayer player)"));
        assertTrue(combat.contains("getEntityScheduler().execute("));
    }

    @Test
    void badPacketsHasAProtocolComponent() throws IOException {
        String punishment = source("manager/PunishmentManager.java");

        assertTrue(punishment.contains("key.startsWith(\"grim.badpackets.\")"));
        assertTrue(punishment.contains("return \"Protocol\";"));
    }

    @Test
    void noFallDoesNotCreateEvidenceOnTeleportTransitionPacket() throws IOException {
        String noFall = source("checks/impl/groundspoof/NoFall.java");

        assertTrue(noFall.contains(
                "!player.packetStateData.lastPacketWasTeleport"
        ));
        assertTrue(noFall.contains(
                "wrapper.setOnGround(false)"
        ));
    }

    @Test
    void simulationReusesOneEnvironmentClassification() throws IOException {
        String offset = source("checks/impl/prediction/OffsetHandler.java");
        String correlation = source("manager/integrity/IntegrityCorrelationManager.java");

        assertTrue(offset.contains("environment.classify(player)"));
        assertTrue(offset.contains("enforcementMultiplier(environmentContexts)"));
        assertTrue(correlation.contains("environment.classify(player)"));
        assertTrue(correlation.contains("summary(contexts)"));
    }

    @Test
    void packetBurstTrustedUncertaintyDoesNotWipePriorBuffer() throws IOException {
        String burst = source("checks/impl/integrity/PacketBurst.java");

        assertTrue(burst.contains("MovementContextType.TELEPORT"));
        assertTrue(burst.contains("resetTimingWindow(now)"));
        assertTrue(burst.contains(
                "buffer = Math.max(0.0D, buffer - 0.08D)"
        ));
    }

    @Test
    void timerPreventionIsIndependentFromSanctionConfidence() throws IOException {
        String timer = source("checks/impl/timer/Timer.java");

        assertTrue(timer.contains("boolean acceptedViolation = flag()"));
        assertTrue(timer.contains("preventExcessPackets"));
        assertTrue(timer.contains("preventionMinimumServerConfidence"));
        assertTrue(timer.contains("heuristicConfidence() >= preventionMinimumServerConfidence"));
        assertTrue(timer.contains("if (prevent && !event.isCancelled())"));
    }

    @Test
    void timerDoesNotDuplicateConnectionStallCandidateEvidence() throws IOException {
        String timer = source("checks/impl/timer/Timer.java");

        assertTrue(timer.contains("stall.isHardReleaseCandidateActive()"));
        assertTrue(timer.contains("stall.shouldSuppressMovementSetbacks()"));
        assertTrue(timer.contains("ConnectionStall owns the first-release/correction decision"));
        assertTrue(timer.contains("timerBalanceRealTime -= 50e6"));
    }

    @Test
    void timerLimitAlsoYieldsToConnectionStallOwnership() throws IOException {
        String timerLimit = source("checks/impl/timer/TimerLimit.java");

        assertTrue(timerLimit.contains("stall.isHardReleaseCandidateActive()"));
        assertTrue(timerLimit.contains("stall.shouldSuppressMovementSetbacks()"));
        assertTrue(timerLimit.contains("do not cancel the observation packet"));
    }

    @Test
    void timerAndReachFeedIntegritySeverity() throws IOException {
        String correlation = source("manager/integrity/IntegrityCorrelationManager.java");
        String signals = source("manager/integrity/IntegritySignal.java");

        assertTrue(correlation.contains("grim.timer.timer"));
        assertTrue(correlation.contains("IntegritySignal.TIMER"));
        assertTrue(correlation.contains("grim.timer.limit"));
        assertTrue(correlation.contains("IntegritySignal.TIMER_LIMIT"));
        assertTrue(correlation.contains("grim.combat.reach"));
        assertTrue(correlation.contains("IntegritySignal.REACH"));
        assertTrue(signals.contains("TIMER(3.0D, false)"));
        assertTrue(signals.contains("TIMER_LIMIT(2.0D, true)"));
        assertTrue(signals.contains("REACH(3.0D, false)"));
    }

    @Test
    void multiInteractDoesNotDuplicateStallOwnedCombatEvidence() throws IOException {
        for (String relative : new String[]{
                "checks/impl/combat/MultiInteractA.java",
                "checks/impl/combat/MultiInteractB.java"
        }) {
            String source = source(relative);
            assertTrue(source.contains("ConnectionStall.class"));
            assertTrue(source.contains("stall.shouldBlockQueuedActions()"));
            assertTrue(source.contains("flags.clear()"));
        }
    }

    @Test
    void reachYieldsToConnectionStallOwnedQueuedAttacks() throws IOException {
        String reach = source("checks/impl/combat/Reach.java");

        int method = reach.indexOf("private void onInteract(");
        int teleportGuard = reach.indexOf(
                "// Don't let the player teleport to bypass reach",
                method
        );
        String prefix = reach.substring(method, teleportGuard);

        assertTrue(prefix.contains("ConnectionStall.class"));
        assertTrue(prefix.contains("stall.shouldBlockQueuedActions()"));
        assertTrue(prefix.contains("return;"));
    }

    @Test
    void reachUsesAttackTimeCompensatedTargetSnapshot() throws IOException {
        String reach = source("checks/impl/combat/Reach.java");

        assertTrue(reach.contains("SimpleCollisionBox attackTargetBox = getTargetBox(entity).copy()"));
        assertTrue(reach.contains("interactionData.targetBoxSnapshot"));
        assertTrue(reach.contains("interactionData.attackTransaction"));
        assertTrue(reach.contains("interactionData.attackNanos"));
        assertTrue(reach.contains("attackAge="));
        assertTrue(reach.contains("txAdvance="));
    }

    @Test
    void punishmentConditionsExposeSeparateServerAndPlayerConfidence() throws IOException {
        String punishment = source("manager/PunishmentManager.java");
        String lag = source("manager/integrity/LagProtectionManager.java");

        assertTrue(punishment.contains("case \"server_confidence\""));
        assertTrue(punishment.contains("case \"player_confidence\", \"network_confidence\""));
        assertTrue(lag.contains("playerNetworkConfidence("));
        assertTrue(punishment.contains("%lag_state%"));
    }


    @Test
    void punishmentHistorySeparatesRawAndSanctionableEvidence() throws IOException {
        String punishment = source("manager/PunishmentManager.java");

        assertTrue(punishment.contains("sanctionViolations"));
        assertTrue(punishment.contains("boolean sanctionable = sanctionableNow()"));
        assertTrue(punishment.contains("case \"sanction_vl\", \"sanction_check_vl\""));
        assertTrue(punishment.contains("case \"sanction_group_vl\""));
        assertTrue(punishment.contains("sanctionMinimumConfidence"));
        assertTrue(punishment.contains("sanctionMinimumServerConfidence"));
        assertTrue(punishment.contains("sanctionMinimumPlayerConfidence"));
        assertTrue(punishment.contains("sanctionMinimumTps"));
    }


    @Test
    void alertsExposePacketPreventionBypassState() throws IOException {
        String punishment = source("manager/PunishmentManager.java");

        assertTrue(punishment.contains("%packet_modify%"));
        assertTrue(punishment.contains("check.shouldModifyPackets() ? \"ACTIVE\" : \"BYPASS\""));
        assertTrue(punishment.contains("%setback_access%"));
        assertTrue(punishment.contains("check.isNoSetbackPermission() ? \"BYPASS\" : \"ACTIVE\""));
    }

}
