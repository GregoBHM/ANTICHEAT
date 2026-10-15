package ac.grim.grimac.manager.integrity;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class VelocityIntegrityAuditTest {

    private static String src(String relative) throws Exception {
        return Files.readString(
                Path.of("src/main/java/ac/grim/grimac").resolve(relative)
        );
    }

    @Test
    void antiKbDefaultsReapplyOffAndUsesBoundedComponents() throws Exception {
        String source = src(
                "checks/impl/velocity/KnockbackHandler.java"
        );

        assertTrue(source.contains(
                "getBooleanElse(\"Knockback.enforcement.enabled\", false)"
        ));
        assertTrue(source.contains(".assessVelocity("));
        assertTrue(source.contains("velocityContext.isUncertain()"));
        assertTrue(source.contains("velocityContextUncertain"));
        assertTrue(source.contains("beginPlayerKnockback") || source.contains("addPlayerKnockback"));
        assertTrue(source.contains("flagComponent("));
        assertTrue(source.contains("\"velocity.normal\""));
        assertTrue(source.contains("\"velocity.cobweb\""));
        assertTrue(source.contains("resolveMovementEvidenceEpisode"));
        assertTrue(source.contains("minimumCorrectionRawOffset(contexts)"));
    }

    @Test
    void explosionUsesNeutralDiscardAndScopedOverlapPolicy() throws Exception {
        String source = src(
                "checks/impl/velocity/ExplosionHandler.java"
        );

        int discarded = source.indexOf(
                "if (!predictionComplete.isChecked())"
        );
        int teleport = source.indexOf(
                "if (predictionComplete.getData().isTeleport())",
                discarded
        );
        int forceExempt = source.indexOf("forceExempt();", discarded);

        assertTrue(discarded >= 0);
        assertTrue(teleport > discarded);
        assertTrue(forceExempt >= teleport);
        assertTrue(source.contains(".assessVelocity("));
        assertTrue(source.contains("if (context.isAmbiguous())"));
    }

    @Test
    void simulationTreatsTrustedVelocityAsBoundedSpecialPhysicsEvidence()
            throws Exception {
        String source = src(
                "checks/impl/prediction/OffsetHandler.java"
        );

        assertTrue(source.contains("trustedVelocityContext"));
        assertTrue(source.contains("boolean anyVelocityContext ="));
        assertTrue(source.contains("boolean accepted = flagComponent("));
        assertTrue(source.contains("\"simulation.cobweb\""));
        assertTrue(source.contains("resolveMovementEvidenceEpisode"));
        assertTrue(source.contains("specialEnvironmentFlagStrongTicks"));
    }
}
