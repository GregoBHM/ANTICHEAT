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
    void fastConsumeCannotInheritNoSlowPunishmentIdentity() throws Exception {
        String consume = read(
                "src/main/java/ac/grim/grimac/checks/impl/integrity/ConsumeTiming.java"
        );

        assertTrue(consume.contains("alternativeName = \"FastEat\""));
        assertFalse(consume.contains("alternativeName = \"NoSlow\""));
        assertTrue(consume.contains(
                "stableKey = \"grim.interaction.fast_consume\""
        ));
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
                "src/main/java/ac/grim/grimac/checks/impl/timer/Timer.java",
                "src/main/java/ac/grim/grimac/checks/impl/integrity/ConsumeTiming.java"
        }) {
            assertFalse(
                    read(path).contains("hasTrustedVelocityContext"),
                    path
            );
        }
    }

    @Test
    void correctionDiagnosticUsesPresentationOnlyPath() throws Exception {
        String punishment = read(
                "src/main/java/ac/grim/grimac/manager/PunishmentManager.java"
        );

        int start = punishment.indexOf(
                "public void handleCorrectionDiagnostic("
        );
        int end = punishment.indexOf(
                "public boolean handleAlert(",
                start
        );
        String diagnostic = punishment.substring(start, end);

        assertFalse(diagnostic.contains("handleViolation("));
        assertTrue(diagnostic.contains("sendVerbose"));
    }
}
