package ac.grim.grimac.manager.integrity;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntegrityThreadSafetyAuditTest {

    private static String source(String relative) throws IOException {
        return Files.readString(Path.of("src/main/java/ac/grim/grimac").resolve(relative));
    }

    @Test
    void fallIntegrityUsesEntitySchedulerForNativePlayerState() throws IOException {
        String source = source("manager/integrity/FallIntegrityManager.java");

        assertTrue(source.contains("getEntityScheduler().execute("));
        assertTrue(source.contains("platformApplyScheduled"));
    }

    @Test
    void cancelledBlockPredictionNeverReadsPlatformWorld() throws IOException {
        String manager = source("manager/integrity/CancelledBlockIntegrityManager.java");
        String check = source("checks/impl/exploit/CancelledBlockClimb.java");

        assertFalse(manager.contains("PlatformWorld"));
        assertFalse(manager.contains("getBlockAt("));
        assertTrue(manager.contains("CompensatedWorld"));

        assertFalse(check.contains("platformPlayer.getWorld()"));
        assertTrue(check.contains("player.compensatedWorld"));
    }

    @Test
    void ghostBlockMitigationUsesPacketWorldSnapshot() throws IOException {
        String source = source("checks/impl/misc/GhostBlockMitigation.java");

        assertFalse(source.contains("PlatformWorld"));
        assertFalse(source.contains("platformPlayer.getWorld()"));
        assertTrue(source.contains("player.compensatedWorld"));
    }
}
