package ac.grim.grimac.manager.integrity;

import org.junit.jupiter.api.Test;

import static ac.grim.grimac.manager.integrity.MovementCorrectionCoordinator.Priority.AUTHORITATIVE;
import static ac.grim.grimac.manager.integrity.MovementCorrectionCoordinator.Priority.CHECK;
import static ac.grim.grimac.manager.integrity.MovementCorrectionCoordinator.Priority.ROUTINE;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MovementCorrectionCoordinatorTest {

    @Test
    void pendingTeleportAlwaysPreventsAnotherPhysicalCorrection() {
        MovementCorrectionCoordinator coordinator = new MovementCorrectionCoordinator();

        assertFalse(coordinator.canApply(
                AUTHORITATIVE, false, 0L, false, true, 1L
        ));
    }

    @Test
    void connectionEpisodeAllowsOnlyItsOwnerAndOnlyOnce() {
        MovementCorrectionCoordinator coordinator = new MovementCorrectionCoordinator();
        long episode = 100L;

        assertFalse(coordinator.canApply(
                CHECK, false, episode, false, false, 1L
        ));

        assertTrue(coordinator.canApply(
                CHECK, true, episode, false, false, 1L
        ));

        coordinator.markApplied(true, episode);

        assertFalse(coordinator.canApply(
                CHECK, true, episode, false, false, 2L
        ));
    }

    @Test
    void newConnectionEpisodeCanOwnANewCorrection() {
        MovementCorrectionCoordinator coordinator = new MovementCorrectionCoordinator();

        assertTrue(coordinator.canApply(
                CHECK, true, 100L, false, false, 1L
        ));
        coordinator.markApplied(true, 100L);
        coordinator.acknowledge(1L);

        // Use a time safely outside the 250 ms post-ack hold.
        long later = 1L + 1_000_000_000L;

        assertTrue(coordinator.canApply(
                CHECK, true, 200L, false, false, later
        ));
    }

    @Test
    void velocityRecoveryOwnsThePhysicalCorrectionWindow() {
        MovementCorrectionCoordinator coordinator = new MovementCorrectionCoordinator();

        assertFalse(coordinator.canApply(
                CHECK, false, 0L, true, false, 1L
        ));
        assertFalse(coordinator.canApply(
                ROUTINE, false, 0L, true, false, 1L
        ));

        // Critical authoritative state repair remains possible.
        assertTrue(coordinator.canApply(
                AUTHORITATIVE, false, 0L, true, false, 1L
        ));
    }

    @Test
    void postAckHoldPreventsImmediateRubberbandChain() {
        MovementCorrectionCoordinator coordinator = new MovementCorrectionCoordinator();

        assertTrue(coordinator.canApply(
                CHECK, false, 0L, false, false, 1L
        ));
        coordinator.markApplied(false, 0L);
        coordinator.acknowledge(1L);

        assertFalse(coordinator.canApply(
                CHECK, false, 0L, false, false, 100_000_000L
        ));
        assertFalse(coordinator.canApply(
                ROUTINE, false, 0L, false, false, 100_000_000L
        ));

        assertTrue(coordinator.canApply(
                CHECK, false, 0L, false, false, 300_000_000L
        ));
    }

    @Test
    void authoritativeRepairBypassesPostAckHoldButNotPendingTeleport() {
        MovementCorrectionCoordinator coordinator = new MovementCorrectionCoordinator();

        assertTrue(coordinator.canApply(
                CHECK, false, 0L, false, false, 1L
        ));
        coordinator.markApplied(false, 0L);
        coordinator.acknowledge(1L);

        assertTrue(coordinator.canApply(
                AUTHORITATIVE, false, 0L, false, false, 100_000_000L
        ));
        assertFalse(coordinator.canApply(
                AUTHORITATIVE, false, 0L, false, true, 100_000_000L
        ));
    }
}
