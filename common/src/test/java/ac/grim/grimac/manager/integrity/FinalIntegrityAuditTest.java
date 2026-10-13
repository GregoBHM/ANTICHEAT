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
    void simulationReusesOneEnvironmentClassification() throws IOException {
        String offset = source("checks/impl/prediction/OffsetHandler.java");
        String correlation = source("manager/integrity/IntegrityCorrelationManager.java");

        assertTrue(offset.contains("environment.classify(player)"));
        assertTrue(offset.contains("enforcementMultiplier(environmentContexts)"));
        assertTrue(correlation.contains("environment.classify(player)"));
        assertTrue(correlation.contains("summary(contexts)"));
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
    void punishmentConditionsExposeSeparateServerAndPlayerConfidence() throws IOException {
        String punishment = source("manager/PunishmentManager.java");
        String lag = source("manager/integrity/LagProtectionManager.java");

        assertTrue(punishment.contains("case \"server_confidence\""));
        assertTrue(punishment.contains("case \"player_confidence\", \"network_confidence\""));
        assertTrue(lag.contains("playerNetworkConfidence("));
        assertTrue(punishment.contains("%lag_state%"));
    }

}
