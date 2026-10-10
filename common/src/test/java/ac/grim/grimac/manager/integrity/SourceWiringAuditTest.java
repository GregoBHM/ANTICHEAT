package ac.grim.grimac.manager.integrity;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceWiringAuditTest {

    @Test
    void obsoleteSelectiveEvidenceStateIsRemoved() throws IOException {
        String combat = source("manager/integrity/CombatIntegrityManager.java");
        String fall = source("manager/integrity/FallIntegrityManager.java");
        String stall = source("checks/impl/timer/ConnectionStall.java");

        assertFalse(combat.contains("selectiveEvidence"));
        assertFalse(combat.contains("markSelectiveEvidence"));
        assertFalse(fall.contains("selectiveEvidence"));
        assertFalse(stall.contains("markSelectiveEvidence"));
        assertTrue(stall.contains("markSanctionableEvidence(player.uuid)"));
    }

    @Test
    void correctionCoordinatorExposesOnlyConsumedOperations() throws IOException {
        String coordinator = source("manager/integrity/MovementCorrectionCoordinator.java");

        assertTrue(coordinator.contains("canApply("));
        assertTrue(coordinator.contains("markApplied("));
        assertTrue(coordinator.contains("acknowledge("));
        assertFalse(coordinator.contains("isCorrectionInFlight()"));
        assertFalse(coordinator.contains("getObservedConnectionEpisodeId()"));
        assertFalse(coordinator.contains("public synchronized void clear()"));
    }

    @Test
    void cancelledBlockFlowIsEndToEnd() throws IOException {
        String manager = source("manager/integrity/CancelledBlockIntegrityManager.java");
        String check = source("checks/impl/exploit/CancelledBlockClimb.java");
        String setback = source("manager/SetbackTeleportUtil.java");

        assertTrue(manager.contains("findCancelledSupport("));
        assertTrue(manager.contains("confirmPlacement("));
        assertTrue(manager.contains("AUTHORITATIVE_CANCEL_NANOS"));
        assertTrue(check.contains("getFrom()"));
        assertTrue(check.contains("getTo()"));
        assertTrue(setback.contains("grim.exploit.cancelled_block_climb"));
    }

    private static String source(String relative) throws IOException {
        return Files.readString(Path.of("src/main/java/ac/grim/grimac/" + relative));
    }
}
