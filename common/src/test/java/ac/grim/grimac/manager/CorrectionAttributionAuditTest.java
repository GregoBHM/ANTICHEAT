package ac.grim.grimac.manager;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CorrectionAttributionAuditTest {

    private static String source(String relative) throws IOException {
        return Files.readString(Path.of("src/main/java/ac/grim/grimac").resolve(relative));
    }

    @Test
    void setbackUtilNeverGuessesCauseFromRecentFlags() throws IOException {
        String setback = source("manager/SetbackTeleportUtil.java");
        assertFalse(setback.contains("getLastViolationTime()"));
        assertFalse(setback.contains("getCorrectionDiagnosticsRecentCheckMillis()"));
        assertFalse(setback.contains("for (AbstractCheck"));
        assertTrue(setback.contains("emitCorrectionDiagnostic(source,"));
    }

    @Test
    void checkOwnedCorrectionsPropagateTheirSource() throws IOException {
        String check = source("checks/Check.java");
        String timer = source("checks/impl/timer/Timer.java");
        String timerLimit = source("checks/impl/timer/TimerLimit.java");
        String connection = source("checks/impl/timer/ConnectionStall.java");
        String offset = source("checks/impl/prediction/OffsetHandler.java");
        String cancelledBlock = source("checks/impl/exploit/CancelledBlockClimb.java");

        assertTrue(check.contains("executeViolationSetback(this)"));
        assertTrue(timer.contains("executeNonSimulatingSetback(this)"));
        assertTrue(timerLimit.contains("apply(player, true, this)"));
        assertTrue(connection.contains("apply(player, true, this)"));
        assertTrue(connection.contains("executeNonSimulatingSetback(this)"));
        assertTrue(offset.contains("executeViolationSetback(this)"));
        assertTrue(offset.contains("apply(player, true, this)"));
        assertTrue(cancelledBlock.contains("executeNonSimulatingForceResync(this)"));
    }
}
