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
        assertTrue(source.contains(
                "hasTrustedVelocityContext(player.uuid)"
        ));
        assertTrue(source.contains("hasObservedVelocityContext(player.uuid)"));
        assertTrue(source.contains("flagComponent("));
        assertTrue(source.contains("\"velocity.normal\""));
        assertTrue(source.contains("\"velocity.cobweb\""));
        assertTrue(source.contains("resolveMovementEvidenceEpisode"));
        assertTrue(source.contains("minimumCorrectionRawOffset(contexts)"));
    }

    @Test
    void simulationStillFlagsOutsideTrustedVelocityAndBoundsSpecialPhysics()
            throws Exception {
        String source = src(
                "checks/impl/prediction/OffsetHandler.java"
        );

        assertTrue(source.contains("trustedVelocityContext"));
        assertTrue(source.contains("boolean accepted = flagComponent("));
        assertTrue(source.contains("\"simulation.cobweb\""));
        assertTrue(source.contains("resolveMovementEvidenceEpisode"));
        assertTrue(source.contains("specialEnvironmentFlagStrongTicks"));
    }
}
