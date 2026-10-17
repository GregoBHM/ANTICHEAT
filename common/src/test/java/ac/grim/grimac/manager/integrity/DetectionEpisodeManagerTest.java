package ac.grim.grimac.manager.integrity;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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


    @Test
    void sharedPhysicalEpisodeCapsCrossCheckCascade() {
        DetectionEpisodeManager manager = new DetectionEpisodeManager();

        DetectionEpisodeManager.SharedEpisode shared =
                manager.resolveSharedEpisode(
                        "movement-special",
                        "cobweb|velocity=observed|item=7",
                        1500L
                );

        assertTrue(manager.evaluate(
                "noslow.cobweb",
                "item-7|cobweb",
                1500L,
                0L,
                1,
                shared,
                2
        ).allowFlag());

        DetectionEpisodeManager.SharedEpisode sameShared =
                manager.resolveSharedEpisode(
                        "movement-special",
                        "cobweb|velocity=observed|item=7",
                        1500L
                );

        assertTrue(manager.evaluate(
                "velocity.cobweb",
                "velocity-42|cobweb",
                1500L,
                0L,
                1,
                sameShared,
                2
        ).allowFlag());

        DetectionEpisodeManager.Decision third = manager.evaluate(
                "simulation.cobweb",
                "simulation|cobweb",
                1500L,
                0L,
                1,
                sameShared,
                2
        );

        assertFalse(third.allowFlag());
        assertTrue(third.sharedFlags() >= 2);
    }

    @Test
    void changedPhysicalContextStartsFreshSharedEpisode() {
        DetectionEpisodeManager manager = new DetectionEpisodeManager();

        DetectionEpisodeManager.SharedEpisode cobweb =
                manager.resolveSharedEpisode(
                        "movement-special",
                        "cobweb|velocity=observed|item=7",
                        1500L
                );
        DetectionEpisodeManager.SharedEpisode liquid =
                manager.resolveSharedEpisode(
                        "movement-special",
                        "water|velocity=none|item=7",
                        1500L
                );

        assertTrue(cobweb.id() != liquid.id());
        assertTrue(manager.evaluate(
                "simulation.liquid",
                "simulation|liquid",
                1500L,
                0L,
                1,
                liquid,
                1
        ).allowFlag());
    }

    @Test
    void thousandsOfEpisodeKeysForOneComponentDoNotGrowStateMap()
            throws Exception {
        DetectionEpisodeManager manager = new DetectionEpisodeManager();

        for (int index = 0; index < 5000; index++) {
            manager.evaluate(
                    "simulation.cobweb",
                    "episode-" + index,
                    1500L,
                    0L,
                    1
            );
        }

        Field field = DetectionEpisodeManager.class.getDeclaredField("states");
        field.setAccessible(true);
        Map<?, ?> states = (Map<?, ?>) field.get(manager);

        assertEquals(1, states.size());

        manager.clearAll();
        assertEquals(0, states.size());
    }


    @Test
    void persistentSharedEpisodeRollsAfterMaximumLifetime() throws Exception {
        DetectionEpisodeManager manager = new DetectionEpisodeManager();

        DetectionEpisodeManager.SharedEpisode first =
                manager.resolveSharedEpisode(
                        "simulation-normal",
                        "simulation.normal",
                        1000L,
                        1L
                );

        Thread.sleep(5L);

        DetectionEpisodeManager.SharedEpisode rolled =
                manager.resolveSharedEpisode(
                        "simulation-normal",
                        "simulation.normal",
                        1000L,
                        1L
                );

        assertTrue(first.id() != rolled.id());
    }

    @Test
    void itemUseSessionAndRecoveryTransitionsStartFreshSharedEpisodes() {
        DetectionEpisodeManager manager = new DetectionEpisodeManager();

        DetectionEpisodeManager.SharedEpisode itemSeven =
                manager.resolveSharedEpisode(
                        "movement-special",
                        "cobweb|velocity=none|item=7",
                        1500L
                );
        DetectionEpisodeManager.SharedEpisode itemEight =
                manager.resolveSharedEpisode(
                        "movement-special",
                        "cobweb|velocity=none|item=8",
                        1500L
                );
        DetectionEpisodeManager.SharedEpisode recovery =
                manager.resolveSharedEpisode(
                        "movement-special",
                        "transition|velocity=none|item=8",
                        1500L
                );

        assertTrue(itemSeven.id() != itemEight.id());
        assertTrue(itemEight.id() != recovery.id());
    }

}
