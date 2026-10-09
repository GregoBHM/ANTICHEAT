package ac.grim.grimac.manager.integrity;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MovementCorrectionOwnershipAuditTest {

    private static String source(String relative) throws IOException {
        return Files.readString(Path.of("src/main/java/ac/grim/grimac").resolve(relative));
    }

    @Test
    void setbackTeleportUtilIsTheFinalArbiter() throws IOException {
        String setback = source("manager/SetbackTeleportUtil.java");

        assertTrue(setback.contains("MovementCorrectionCoordinator"));
        assertTrue(setback.contains("canApply("));
        assertTrue(setback.contains("markApplied("));
        assertTrue(setback.contains("correctionCoordinator.acknowledge("));
    }

    @Test
    void connectionStallExposesAStableCorrectionEpisodeToken() throws IOException {
        String stall = source("checks/impl/timer/ConnectionStall.java");
        assertTrue(stall.contains("public long getCorrectionEpisodeId()"));
        assertTrue(stall.contains("hardReleaseEpisodeActive"));
        assertTrue(stall.contains("stallStartNanos"));
    }

    @Test
    void nobodyElseSendsGrimSetbacksDirectly() throws IOException {
        Path root = Path.of("src/main/java/ac/grim/grimac");
        try (var files = Files.walk(root)) {
            for (Path path : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text = Files.readString(path);
                if (path.endsWith("manager/SetbackTeleportUtil.java")) continue;

                assertFalse(
                        text.contains("PLAYER_SETBACK_CHANNEL.fire"),
                        path.toString()
                );
                assertFalse(
                        text.contains("sendSetback("),
                        path.toString()
                );
            }
        }
    }
}
