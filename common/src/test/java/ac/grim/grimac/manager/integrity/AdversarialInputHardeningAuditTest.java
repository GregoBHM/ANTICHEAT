package ac.grim.grimac.manager.integrity;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdversarialInputHardeningAuditTest {

    private static String source(String relative) throws Exception {
        return Files.readString(
                Path.of("src/main/java/ac/grim/grimac").resolve(relative)
        );
    }

    @Test
    void autoclickerCoverageIsExcessOnlyAndNeverCancelsInput() throws Exception {
        String source = source(
                "checks/impl/combat/AutoClickerExcess.java"
        );

        assertTrue(source.contains("perTickThreshold"));
        assertTrue(source.contains("sustainedOneSecondLimit"));
        assertTrue(source.contains("IntegritySignal.PACKET_BURST"));
        assertTrue(source.contains("IntegritySignal.SELECTIVE_STALL"));
        assertFalse(source.contains("event.setCancelled"));
        assertFalse(source.contains("player.onPacketCancel"));
    }

    @Test
    void fastPlaceCoverageNeverResyncsOrCancelsBlocks() throws Exception {
        String source = source(
                "checks/impl/scaffolding/FastPlace.java"
        );

        assertTrue(source.contains("requiredStrongWindows"));
        assertTrue(source.contains("IntegritySignal.PACKET_BURST"));
        assertTrue(source.contains("IntegritySignal.SELECTIVE_STALL"));
        assertFalse(source.contains("place.resync()"));
        assertFalse(source.contains("shouldCancel()"));
    }

    @Test
    void heuristicInputChecksCannotEnterAutonomousSanctionAllowlist() throws Exception {
        String punishment = source("manager/PunishmentManager.java");

        assertFalse(punishment.contains(
                "case \"grim.combat.autoclicker_excess\" -> true"
        ));
        assertFalse(punishment.contains(
                "case \"grim.scaffolding.fast_place\" -> true"
        ));
    }

    @Test
    void aimAssistEvidenceIsExperimentalAndNonDestructive() throws Exception {
        String source = source(
                "checks/impl/aim/AimSensitivityConsistency.java"
        );

        assertTrue(source.contains("experimental = true"));
        assertTrue(source.contains("AimProcessor.class"));
        assertTrue(source.contains("minimum-step-residual"));
        assertFalse(source.contains("event.setCancelled"));
        assertFalse(source.contains("executeViolationSetback"));
    }

    @Test
    void checkManagerRegistersBothHardeningChecks() throws Exception {
        String manager = source("manager/CheckManager.java");

        assertTrue(manager.contains(
                ".put(AutoClickerExcess.class, new AutoClickerExcess(player))"
        ));
        assertTrue(manager.contains(
                ".put(FastPlace.class, new FastPlace(player))"
        ));
        assertTrue(manager.contains(
                ".put(AimSensitivityConsistency.class, new AimSensitivityConsistency(player))"
        ));
    }
}
