package ac.grim.grimac.manager.integrity;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MovementContextManagerTest {

    @Test
    void observedVelocityIsUncertaintyNotTrustedBypass() {
        MovementContextManager manager = new MovementContextManager();
        UUID uuid = UUID.randomUUID();

        manager.beginObserved(
                uuid,
                MovementContextType.DASH,
                "Bukkit:Velocity",
                900L,
                "observed"
        );

        assertTrue(manager.hasObservedVelocityContext(uuid));
        assertTrue(manager.hasAnyVelocityContext(uuid));
        assertFalse(manager.hasTrustedVelocityContext(uuid));
    }

    @Test
    void explicitPluginVelocityCanBeTrustedForBoundedVelocityChecks() {
        MovementContextManager manager = new MovementContextManager();
        UUID uuid = UUID.randomUUID();

        manager.beginTrusted(
                uuid,
                MovementContextType.LAUNCH,
                "RPGItems:Leap",
                1000L,
                "power=Leap"
        );

        assertTrue(manager.hasTrustedVelocityContext(uuid));
        assertTrue(manager.hasAnyVelocityContext(uuid));
        assertFalse(manager.hasObservedVelocityContext(uuid));
    }

    @Test
    void nonVelocityTrustedContextDoesNotBecomeVelocityBypass() {
        MovementContextManager manager = new MovementContextManager();
        UUID uuid = UUID.randomUUID();

        manager.beginTrusted(
                uuid,
                MovementContextType.TELEPORT,
                "MagicSpells:Blink",
                1000L
        );

        assertFalse(manager.hasTrustedVelocityContext(uuid));
        assertFalse(manager.hasObservedVelocityContext(uuid));
        assertFalse(manager.hasAnyVelocityContext(uuid));
    }
}
