package ac.grim.grimac.manager.integrity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DetectionEpisodeManagerTest {

    @Test
    void repeatedEvidenceIsCappedInsideOneEpisode() {
        DetectionEpisodeManager manager = new DetectionEpisodeManager();

        DetectionEpisodeManager.Decision first = manager.evaluate(
                "noslow.cobweb",
                "session-1|cobweb",
                1200L,
                0L,
                1
        );
        DetectionEpisodeManager.Decision repeated = manager.evaluate(
                "noslow.cobweb",
                "session-1|cobweb",
                1200L,
                0L,
                1
        );

        assertTrue(first.allowFlag());
        assertFalse(repeated.allowFlag());
        assertTrue(repeated.observations() >= 2);
    }

    @Test
    void aNewEpisodeCanProduceFreshEvidence() {
        DetectionEpisodeManager manager = new DetectionEpisodeManager();

        assertTrue(manager.evaluate(
                "velocity.cobweb",
                "kb-transaction-10",
                1500L,
                0L,
                1
        ).allowFlag());

        assertTrue(manager.evaluate(
                "velocity.cobweb",
                "kb-transaction-11",
                1500L,
                0L,
                1
        ).allowFlag());
    }

    @Test
    void componentsHaveIndependentEpisodeBudgets() {
        DetectionEpisodeManager manager = new DetectionEpisodeManager();

        assertTrue(manager.evaluate(
                "noslow.cobweb",
                "shared-physical-context",
                1500L,
                0L,
                1
        ).allowFlag());

        assertTrue(manager.evaluate(
                "simulation.cobweb",
                "shared-physical-context",
                1500L,
                0L,
                1
        ).allowFlag());
    }
}
