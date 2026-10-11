package ac.grim.grimac.manager;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CorrectionDiagnosticPolicyAuditTest {

    @Test
    void diagnosticPathNeverAddsVlOrRunsPunishmentCommands() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/ac/grim/grimac/manager/PunishmentManager.java"
        ));

        int start = source.indexOf("public void handleCorrectionDiagnostic(");
        int end = source.indexOf("public boolean handleAlert(", start);
        assertTrue(start >= 0 && end > start);

        String method = source.substring(start, end);
        assertFalse(method.contains("handleViolation("));
        assertFalse(method.contains("[webhook]"));
        assertFalse(method.contains("[proxy]"));
        assertFalse(method.contains("dispatchCommand("));
        assertTrue(method.contains("sendVerbose"));
        assertTrue(method.contains("CorrectionDiagnosticMode.NORMAL"));
        assertTrue(method.contains("AlertAggregationManager.Decision"));
    }
}
