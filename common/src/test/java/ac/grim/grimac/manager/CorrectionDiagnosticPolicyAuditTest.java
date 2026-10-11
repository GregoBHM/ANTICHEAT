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
        assertFalse(method.contains("flag("));
        assertFalse(method.contains("flagWithSetback("));
        assertFalse(method.contains("[webhook]"));
        assertFalse(method.contains("[proxy]"));
        assertFalse(method.contains("dispatchCommand("));

        assertTrue(method.contains(
                "correctionDiagnosticsMode == CorrectionDiagnosticMode.OFF"
        ));
        assertTrue(method.contains(
                "correctionDiagnosticsMode == CorrectionDiagnosticMode.VERBOSE"
        ));
        assertTrue(method.contains(
                "correctionDiagnosticsMode == CorrectionDiagnosticMode.NORMAL"
        ));
        assertTrue(method.contains("sendVerbose"));
        assertTrue(method.contains("sendAlert"));
        assertTrue(method.contains("stableKey + ".correction""));
        assertTrue(method.contains("AlertAggregationManager.Decision"));
    }

    @Test
    void correctionAndNormalDetectionUseSeparatePresentationBuckets()
            throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/ac/grim/grimac/manager/PunishmentManager.java"
        ));

        assertTrue(source.contains(
                "stableKey + ".correction""
        ));
        assertTrue(source.contains(
                ".evaluate(\n"
                        + "                                            player.uuid,\n"
                        + "                                            check.getStableKey(),"
        ));
    }

    @Test
    void componentPlaceholderHasStableFallback() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/ac/grim/grimac/manager/PunishmentManager.java"
        ));

        assertTrue(source.contains(
                ".replace("%component%", component == null ? "Other" : component)"
        ));
        assertTrue(source.contains("private static String componentFor(Check check)"));
        assertTrue(source.contains("return "Movement";"));
        assertTrue(source.contains("return "Interaction";"));
        assertTrue(source.contains("return "Velocity";"));
        assertTrue(source.contains("return "Other";"));
    }
}
