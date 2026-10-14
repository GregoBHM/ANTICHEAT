package ac.grim.grimac.manager.integrity;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyOneEightHardeningAuditTest {

    private static String source(String relative) throws IOException {
        return Files.readString(
                Path.of("src/main/java/ac/grim/grimac").resolve(relative)
        );
    }

    @Test
    void hangingEntitiesDoNotCreateLegacyAttackSlow() throws IOException {
        String attack = source("events/packets/PacketPlayerAttack.java");
        assertTrue(attack.contains("EntityTypes.ABSTRACT_HANGING"));
        assertTrue(attack.contains("!EntityTypes.isTypeInstanceOf"));
    }

    @Test
    void timerLimitRollbackRequiresCausalBurstEvidence() throws IOException {
        String limit = source("checks/impl/timer/TimerLimit.java");
        assertTrue(limit.contains(
                "if (hardPrevention && !suppressSetback && shouldSetback())"
        ));
    }

    @Test
    void complexLegacyCollisionsUseUncertainVelocityPath() throws IOException {
        String environment = source(
                "manager/integrity/EnvironmentContextManager.java"
        );
        assertTrue(environment.contains(
                "case COBWEB, STUCK_MOVEMENT, CLIMBABLE, WATER, LAVA"
        ));
        assertTrue(environment.contains(
                "COMPLEX_COLLISION, PISTON, RECENT_BLOCK_CHANGE"
        ));
    }

    @Test
    void autonomousSanctionLedgerIsAnAllowlist() throws IOException {
        String punishment = source("manager/PunishmentManager.java");
        assertTrue(punishment.contains("sanctionEvidenceEligible(check)"));
        assertTrue(punishment.contains("case \"grim.timer.timer\" -> true"));
        assertTrue(punishment.contains(
                "\"velocity.normal\".equals(check.getLastComponent())"
        ));
        assertTrue(punishment.contains(
                "case \"grim.combat.reach\", \"grim.combat.hitboxes\""
        ));
        assertTrue(punishment.contains(
                "default -> key.startsWith(\"grim.crash.\")"
        ));
        assertTrue(punishment.contains(
                "player.getClientVersion() == ClientVersion.V_1_8"
        ));
    }

    @Test
    void legacyRotationPlaceUsesPostFlyingPersistence() throws IOException {
        String rotation = source("checks/impl/scaffolding/RotationPlace.java");
        assertTrue(rotation.contains("legacyInvalidPostStreak"));
        assertTrue(rotation.contains("legacyInvalidPostStreak >= 2"));
        assertTrue(rotation.contains(
                "isOlderThanOrEquals(ClientVersion.V_1_8)"
        ));
    }

    @Test
    void serverLagCannotCreateDestructiveSimulationOrPacketSpamKicks() throws IOException {
        String offset = source("checks/impl/prediction/OffsetHandler.java");
        String player = source("player/GrimPlayer.java");

        assertTrue(offset.contains("enforcementMinimumServerConfidence"));
        assertTrue(offset.contains("enforcementServerHealthy"));
        assertTrue(offset.contains(
                "getLagProtectionManager().heuristicConfidence()"
        ));

        assertTrue(player.contains("packetSpamMinimumServerConfidence"));
        assertTrue(player.contains(
                "serverConfidence < packetSpamMinimumServerConfidence"
        ));
        assertTrue(player.contains(
                "Keep cancelling invalid/excess packets"
        ));
    }

}
