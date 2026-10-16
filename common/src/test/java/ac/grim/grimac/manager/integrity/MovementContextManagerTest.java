package ac.grim.grimac.manager.integrity;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MovementContextManagerTest {


    @Test
    void genericBeginIsObservedByDefault() {
        MovementContextManager manager = new MovementContextManager();
        UUID uuid = UUID.randomUUID();

        MovementContextManager.Context context = manager.begin(
                uuid,
                MovementContextType.DASH,
                "Internal:Generic",
                1000L
        );

        assertFalse(context.isTrusted());
        assertTrue(manager.hasObservedVelocityContext(uuid));
        assertFalse(manager.hasTrustedVelocityContext(uuid));
    }

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
    void explicitPluginVelocityRequiresConcreteVectorForTrust() {
        MovementContextManager manager = new MovementContextManager();
        UUID uuid = UUID.randomUUID();

        manager.beginTrusted(
                uuid,
                MovementContextType.LAUNCH,
                "RPGItems:LegacyLeap",
                1000L,
                "power=LegacyLeap"
        );

        assertFalse(manager.hasTrustedVelocityContext(uuid));
        assertTrue(manager.hasObservedVelocityContext(uuid));

        manager.beginTrustedVelocity(
                uuid,
                MovementContextType.LAUNCH,
                "RPGItems:Leap",
                1000L,
                new ac.grim.grimac.utils.math.Vector3dm(0.2D, 0.8D, 0.1D),
                42,
                "power=Leap"
        );

        assertTrue(manager.hasTrustedVelocityContext(uuid));
        assertTrue(manager.hasAnyVelocityContext(uuid));
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

    @Test
    void disablingOnePluginOnlyClearsThatSourcePrefix() {
        MovementContextManager manager = new MovementContextManager();
        UUID uuid = UUID.randomUUID();

        manager.beginTrusted(
                uuid,
                MovementContextType.DASH,
                "RPGItems:Leap",
                1000L
        );
        manager.beginTrusted(
                uuid,
                MovementContextType.LAUNCH,
                "MagicSpells:Jump",
                1000L
        );
        manager.beginTrusted(
                uuid,
                MovementContextType.PULL,
                "CombatPlus:Hook",
                1000L
        );

        UUID other = UUID.randomUUID();
        manager.beginTrusted(
                other,
                MovementContextType.DASH,
                "RPGItems:OtherPlayer",
                1000L
        );

        manager.clearSourcePrefix(uuid, "RPGItems");

        List<MovementContextManager.Context> active = manager.getActive(uuid);
        assertFalse(active.stream().anyMatch(
                context -> context.getSource().startsWith("RPGItems:")
        ));
        assertTrue(active.stream().anyMatch(
                context -> context.getSource().startsWith("MagicSpells:")
        ));
        assertTrue(active.stream().anyMatch(
                context -> context.getSource().startsWith("CombatPlus:")
        ));
        assertTrue(manager.getActive(other).stream().anyMatch(
                context -> context.getSource().startsWith("RPGItems:")
        ));
    }

    @Test
    void perPlayerContextCardinalityIsBounded() {
        MovementContextManager manager = new MovementContextManager();
        UUID uuid = UUID.randomUUID();

        for (int index = 0; index < 256; index++) {
            manager.beginObserved(
                    uuid,
                    MovementContextType.KNOCKBACK,
                    "Plugin:" + index,
                    15_000L
            );
        }

        assertTrue(manager.getActive(uuid).size() <= 64);
    }

    @Test
    void globalPluginCleanupKeepsOtherMovementSourcesForEveryPlayer() {
        MovementContextManager manager = new MovementContextManager();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        manager.beginTrusted(first, MovementContextType.DASH,
                "RPGItems:Leap", 1000L);
        manager.beginTrusted(first, MovementContextType.LAUNCH,
                "MagicSpells:Jump", 1000L);
        manager.beginTrusted(second, MovementContextType.PULL,
                "RPGItems:Hook", 1000L);
        manager.beginTrusted(second, MovementContextType.DASH,
                "CombatPlus:Burst", 1000L);

        manager.clearSourcePrefix("RPGItems");

        assertFalse(manager.getActive(first).stream().anyMatch(
                context -> context.getSource().startsWith("RPGItems:")
        ));
        assertFalse(manager.getActive(second).stream().anyMatch(
                context -> context.getSource().startsWith("RPGItems:")
        ));
        assertTrue(manager.getActive(first).stream().anyMatch(
                context -> context.getSource().startsWith("MagicSpells:")
        ));
        assertTrue(manager.getActive(second).stream().anyMatch(
                context -> context.getSource().startsWith("CombatPlus:")
        ));
    }

}
