package ac.grim.grimac.manager.integrity;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SanctionEpisodeManagerTest {

    @Test
    void continuityRecoveryRequiresConfiguredCleanPredictions()
            throws Exception {
        SanctionEpisodeManager manager = new SanctionEpisodeManager();
        setLong(manager, "postContinuityGraceNanos", 0L);
        setInt(manager, "minimumCleanPredictions", 2);

        UUID uuid = UUID.randomUUID();
        manager.markContinuityBreak(uuid, "teleport:test");

        assertTrue(manager.isRecoveryBlocked(uuid));

        manager.noteCleanPrediction(uuid);
        assertTrue(manager.isRecoveryBlocked(uuid));

        manager.noteCleanPrediction(uuid);
        assertFalse(manager.isRecoveryBlocked(uuid));
    }

    @Test
    void aFreshContinuityBreakResetsCleanRecoveryProgress()
            throws Exception {
        SanctionEpisodeManager manager = new SanctionEpisodeManager();
        setLong(manager, "postContinuityGraceNanos", 0L);
        setInt(manager, "minimumCleanPredictions", 2);

        UUID uuid = UUID.randomUUID();
        manager.markContinuityBreak(uuid, "teleport:first");
        manager.noteCleanPrediction(uuid);
        assertTrue(manager.isRecoveryBlocked(uuid));

        manager.markContinuityBreak(uuid, "teleport:second");
        manager.noteCleanPrediction(uuid);
        assertTrue(manager.isRecoveryBlocked(uuid));

        manager.noteCleanPrediction(uuid);
        assertFalse(manager.isRecoveryBlocked(uuid));
    }

    private static void setLong(
            SanctionEpisodeManager manager,
            String name,
            long value
    ) throws Exception {
        Field field = SanctionEpisodeManager.class.getDeclaredField(name);
        field.setAccessible(true);
        field.setLong(manager, value);
    }

    private static void setInt(
            SanctionEpisodeManager manager,
            String name,
            int value
    ) throws Exception {
        Field field = SanctionEpisodeManager.class.getDeclaredField(name);
        field.setAccessible(true);
        field.setInt(manager, value);
    }
    @Test
    void snapshotReportsRecoveryFromOneConsistentState()
            throws Exception {
        SanctionEpisodeManager manager = new SanctionEpisodeManager();
        setLong(manager, "postContinuityGraceNanos", 0L);
        setInt(manager, "minimumCleanPredictions", 1);

        UUID uuid = UUID.randomUUID();
        manager.markContinuityBreak(uuid, "teleport:test");

        SanctionEpisodeManager.Snapshot blocked =
                manager.snapshot(
                        uuid,
                        SanctionEpisodeManager.FAMILY_MOVEMENT
                );

        assertTrue(blocked.recoveryBlocked());
        assertTrue(blocked.recoveryReason().contains("teleport:test"));
        assertTrue(blocked.confirmedEpisodes10m() == 0);
        assertTrue(blocked.confirmedEpisodes2h() == 0);
        assertTrue(blocked.confirmedEpisodesRetention() == 0);

        manager.noteCleanPrediction(uuid);

        SanctionEpisodeManager.Snapshot recovered =
                manager.snapshot(
                        uuid,
                        SanctionEpisodeManager.FAMILY_MOVEMENT
                );

        assertFalse(recovered.recoveryBlocked());
    }


}
