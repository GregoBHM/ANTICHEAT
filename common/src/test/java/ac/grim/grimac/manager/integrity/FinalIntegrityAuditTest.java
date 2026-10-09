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
}
