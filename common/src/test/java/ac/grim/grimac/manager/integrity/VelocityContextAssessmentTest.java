package ac.grim.grimac.manager.integrity;

import ac.grim.grimac.utils.math.Vector3dm;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VelocityContextAssessmentTest {

    @Test
    void matchingObservedBukkitVelocityDoesNotCreateAntiKbUncertainty() {
        MovementContextManager manager = new MovementContextManager();
        UUID player = UUID.randomUUID();
        Vector3dm velocity = new Vector3dm(0.42D, 0.36D, -0.18D);

        manager.beginObservedVelocity(
                player,
                MovementContextType.KNOCKBACK,
                "Bukkit:Velocity",
                900L,
                velocity,
                "vanilla"
        );

        MovementContextManager.VelocityContextAssessment assessment =
                manager.assessVelocity(player, 42, velocity.clone());

        assertFalse(assessment.isUncertain());
        assertFalse(assessment.hasTrustedMatch());
    }

    @Test
    void trustedPluginVelocityOnlyScopesTrustToMatchingVector() {
        MovementContextManager manager = new MovementContextManager();
        UUID player = UUID.randomUUID();
        Vector3dm dash = new Vector3dm(1.15D, 0.08D, 0.0D);

        manager.beginTrustedVelocity(
                player,
                MovementContextType.DASH,
                "RPGItems:dash",
                500L,
                dash,
                120,
                "power=dash"
        );

        MovementContextManager.VelocityContextAssessment exact =
                manager.assessVelocity(player, 120, dash.clone());
        assertTrue(exact.isUncertain());
        assertTrue(exact.hasTrustedMatch());

        MovementContextManager.VelocityContextAssessment combat =
                manager.assessVelocity(
                        player,
                        122,
                        new Vector3dm(-0.34D, 0.40D, 0.21D)
                );

        assertTrue(combat.isUncertain());
        assertFalse(combat.hasTrustedMatch());
        assertTrue(combat.isAmbiguous());
    }

    @Test
    void unscopedLegacyTrustIsConservativeButNeverAnExactBypass() {
        MovementContextManager manager = new MovementContextManager();
        UUID player = UUID.randomUUID();

        manager.beginTrusted(
                player,
                MovementContextType.LAUNCH,
                "MagicSpells:legacy",
                400L
        );

        MovementContextManager.VelocityContextAssessment assessment =
                manager.assessVelocity(
                        player,
                        10,
                        new Vector3dm(0.2D, 0.7D, 0.1D)
                );

        assertTrue(assessment.isUncertain());
        assertTrue(assessment.isAmbiguous());
        assertFalse(assessment.hasTrustedMatch());
    }
}
