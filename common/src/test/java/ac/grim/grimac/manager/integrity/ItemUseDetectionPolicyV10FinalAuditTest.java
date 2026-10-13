package ac.grim.grimac.manager.integrity;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemUseDetectionPolicyV10FinalAuditTest {

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path));
    }

    @Test
    void consumeTimingDetectionIsRemoved() throws Exception {
        String manager = read(
                "src/main/java/ac/grim/grimac/manager/CheckManager.java"
        );
        String tracker = read(
                "src/main/java/ac/grim/grimac/utils/item/ItemUseSessionTracker.java"
        );

        assertFalse(manager.contains("ConsumeTiming"));
        assertFalse(tracker.contains("FastConsume"));
        assertFalse(tracker.contains("expectedDurationMillis"));
    }

    @Test
    void physicalUseAndPredictionSlowStateRemainSeparate() throws Exception {
        String prediction = read(
                "src/main/java/ac/grim/grimac/predictionengine/predictions/PredictionEngine.java"
        );
        String noSlow = read(
                "src/main/java/ac/grim/grimac/checks/impl/movement/NoSlow.java"
        );

        assertTrue(prediction.contains("setSlowedByUsingItem"));
        assertFalse(prediction.contains("itemUseSessionTracker"));
        assertTrue(noSlow.contains("itemUseSessionTracker.activeSession()"));
    }

    @Test
    void trustedMovementDoesNotTouchDeterministicUnrelatedChecks()
            throws Exception {
        for (String path : new String[]{
                "src/main/java/ac/grim/grimac/checks/impl/prediction/Phase.java",
                "src/main/java/ac/grim/grimac/checks/impl/combat/Reach.java",
                "src/main/java/ac/grim/grimac/checks/impl/timer/Timer.java"
        }) {
            assertFalse(read(path).contains("hasTrustedVelocityContext"), path);
        }
    }

    @Test
    void correctionDiagnosticUsesPresentationOnlyPath() throws Exception {
        String punishment = read(
                "src/main/java/ac/grim/grimac/manager/PunishmentManager.java"
        );

        int start = punishment.indexOf("public void handleCorrectionDiagnostic(");
        int end = punishment.indexOf("public boolean handleAlert(", start);
        String diagnostic = punishment.substring(start, end);

        assertFalse(diagnostic.contains("handleViolation("));
        assertTrue(diagnostic.contains("sendVerbose"));
    }

    @Test
    void sprintAndMultiActionChecksRequirePhysicalSession()
            throws Exception {
        for (String path : new String[]{
                "src/main/java/ac/grim/grimac/checks/impl/sprint/SprintC.java",
                "src/main/java/ac/grim/grimac/checks/impl/multiactions/MultiActionsA.java",
                "src/main/java/ac/grim/grimac/checks/impl/multiactions/MultiActionsB.java",
                "src/main/java/ac/grim/grimac/checks/impl/multiactions/MultiActionsE.java"
        }) {
            String source = read(path);
            assertTrue(source.contains("itemUseSessionTracker.isActive()"), path);
            assertTrue(source.contains("packetStateData.isSlowedByUsingItem()"), path);
        }
    }
}
